package dev.rareradar.core.hunting;

import dev.rareradar.core.spawn.EncounterRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shiny-hunt planning from the player's own encounter log plus (optionally) the knowledge-base spawn model.
 * Every encountered wild Pokémon is independently shiny with probability {@code 1/shinyRate} (Cobblemon rolls at
 * spawn), so the number of target shinies is a Poisson process: there is no "due" shiny, and a dry streak does not
 * shorten the remaining wait.
 */
public final class ShinyHuntPlanner {
    private ShinyHuntPlanner() {}

    /** Minimum observation before an area's encounter rate is trusted. */
    public static final double MIN_HOURS = 0.25;
    /** The model share acts as a prior worth this many encounters; observations in the biome then dominate. */
    public static final double MODEL_PRIOR_ENCOUNTERS = 20;

    public enum ShareSource { OBSERVED, MODEL, BLENDED }

    /**
     * @param where             biome id
     * @param encountersPerHour all wild Pokémon first-sighted per hour there (observed); NaN if not measured
     * @param share             fraction of those encounters that are the target species
     * @param targetShinyPerHour expected target shinies per hour; NaN when the rate is unknown
     */
    public record Option(String where, double encountersPerHour, double exposureHours, double share, ShareSource shareSource,
                         double targetShinyPerHour, double expectedHours, double hours50, double hours90) {
        public boolean rateKnown() { return Double.isFinite(targetShinyPerHour); }
    }

    /** Hours until the probability of at least one event reaches {@code p}, for a Poisson rate per hour. */
    public static double hoursForProbability(double ratePerHour, double p) {
        if (!(ratePerHour > 0) || !(p > 0) || p >= 1) return Double.NaN;
        return -Math.log1p(-p) / ratePerHour;
    }

    /** P(at least one) within {@code hours}. */
    public static double probabilityWithin(double ratePerHour, double hours) {
        if (!(ratePerHour >= 0) || !(hours >= 0)) return Double.NaN;
        return -Math.expm1(-ratePerHour * hours);
    }

    /**
     * Ranks places to hunt a shiny {@code species}.
     *
     * @param log          the encounter log
     * @param exposureHours biome → hours spent there (scanner exposure ledger)
     * @param modelShare   biome → modelled share of spawns for the species (may be empty)
     * @param shinyRate    1 in N (Cobblemon default 8192; the server may differ)
     */
    public static List<Option> rank(String species, Collection<EncounterRecord> log, Map<String, Double> exposureHours,
                                    Map<String, Double> modelShare, double shinyRate) {
        Map<String, long[]> perBiome = new java.util.HashMap<>(); // {all, species}
        for (EncounterRecord r : log) {
            long[] c = perBiome.computeIfAbsent(r.biomeKey(), k -> new long[2]);
            c[0]++;
            if (species.equalsIgnoreCase(r.species)) c[1]++;
        }
        java.util.Set<String> biomes = new java.util.TreeSet<>(modelShare.keySet());
        for (var e : perBiome.entrySet()) if (e.getValue()[1] > 0) biomes.add(e.getKey());
        List<Option> out = new ArrayList<>();
        for (String b : biomes) {
            long[] c = perBiome.getOrDefault(b, new long[2]);
            double hours = exposureHours.getOrDefault(b, 0.0);
            double encPerHour = hours >= MIN_HOURS && c[0] > 0 ? c[0] / hours : Double.NaN;
            double share; ShareSource src;
            Double m = modelShare.get(b);
            if (m != null) {
                // Beta-style blend: model share as a prior, updated by what was actually seen here.
                share = (MODEL_PRIOR_ENCOUNTERS * m + c[1]) / (MODEL_PRIOR_ENCOUNTERS + c[0]);
                src = c[0] > 0 ? ShareSource.BLENDED : ShareSource.MODEL;
            } else { share = c[0] == 0 ? 0 : (double) c[1] / c[0]; src = ShareSource.OBSERVED; }
            if (!(share > 0)) continue;
            double rate = encPerHour * share / shinyRate;
            out.add(new Option(b, encPerHour, hours, share, src, rate, Double.isFinite(rate) ? 1 / rate : Double.NaN,
                    hoursForProbability(rate, 0.5), hoursForProbability(rate, 0.9)));
        }
        out.sort(Comparator.comparing((Option o) -> !o.rateKnown())
                .thenComparing(Comparator.comparingDouble((Option o) -> o.rateKnown() ? o.targetShinyPerHour() : o.share()).reversed()));
        return out;
    }

    /**
     * @param sinceLastShiny encounters (of the counted kind) after the most recent shiny, or all if none yet
     * @param pDrySpell      probability of going at least that long without one — how unusual the streak is
     */
    public record Streak(long sinceLastShiny, long shiniesSeen, double pDrySpell, String note) {}

    /** Dry-streak statistics over the log (all species when {@code speciesOrNull} is null). */
    public static Streak streak(List<EncounterRecord> log, String speciesOrNull, double shinyRate) {
        long since = 0, shinies = 0;
        for (EncounterRecord r : log) {
            if (speciesOrNull != null && !speciesOrNull.equalsIgnoreCase(r.species)) continue;
            if (r.shiny) { shinies++; since = 0; } else since++;
        }
        double p = Math.pow(1 - 1 / shinyRate, since);
        String note = String.format(Locale.ROOT, "%d encounters since the last shiny; %.0f%% of hunters would have gone this long without one. "
                + "Shinies are independent — the expected wait from here is still %.0f encounters.", since, 100 * p, shinyRate);
        return new Streak(since, shinies, p, note);
    }
}

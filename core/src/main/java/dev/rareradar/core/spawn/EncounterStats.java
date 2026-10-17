package dev.rareradar.core.spawn;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.*;
import java.util.function.Predicate;

/**
 * Exposure-normalised encounter rates. Observed rates reflect where and when the player looked, how they moved,
 * server population and spawn modifiers; they are NOT true spawn probabilities. Intervals use the Poisson model and
 * Confidence is capped by exposure time.
 */
public final class EncounterStats {
    public record Rate(String key, long events, double hours, Estimate perHour) {}

    private EncounterStats() {}

    public static Estimate rate(long events, double hours, String basis) {
        if (hours <= 0) return Estimate.unknown("no observation time");
        double[] ci = Stats.poissonRate(events, hours, 0.9);
        double v = events / hours;
        Confidence c = Confidence.of((int) events, v > 0 ? (ci[1] - ci[0]) / v : Double.POSITIVE_INFINITY);
        if (hours < 0.5) c = c.min(Confidence.LOW);
        else if (hours < 3) c = c.min(Confidence.MEDIUM);
        return new Estimate(v, ci[0], ci[1], (int) events, c, basis);
    }

    /** Rates per biome for encounters matching {@code filter}. */
    public static List<Rate> byBiome(Collection<EncounterRecord> log, ExposureLedger exp, Predicate<EncounterRecord> filter) {
        Map<String, Long> n = new HashMap<>();
        for (EncounterRecord r : log) if (filter.test(r)) n.merge(r.biomeKey(), 1L, Long::sum);
        Set<String> biomes = new TreeSet<>(exp.biomes());
        biomes.addAll(n.keySet());
        List<Rate> out = new ArrayList<>();
        for (String b : biomes) {
            long k = n.getOrDefault(b, 0L);
            double h = exp.hours(b);
            out.add(new Rate(b, k, h, rate(k, h, String.format(Locale.ROOT, "%d in %.1f h", k, h))));
        }
        return out;
    }

    public static List<Rate> byTime(Collection<EncounterRecord> log, ExposureLedger exp, Predicate<EncounterRecord> filter) {
        Map<TimeBucket, Long> n = new EnumMap<>(TimeBucket.class);
        for (EncounterRecord r : log) if (filter.test(r)) n.merge(r.time, 1L, Long::sum);
        List<Rate> out = new ArrayList<>();
        for (TimeBucket t : TimeBucket.values()) {
            double h = exp.hoursForTime(t);
            long k = n.getOrDefault(t, 0L);
            if (h > 0 || k > 0) out.add(new Rate(t.name(), k, h, rate(k, h, String.format(Locale.ROOT, "%d in %.1f h", k, h))));
        }
        return out;
    }

    /** Species frequency (share of all logged encounters), most frequent first. */
    public static List<Map.Entry<String, Long>> speciesFrequency(Collection<EncounterRecord> log) {
        Map<String, Long> n = new HashMap<>();
        for (EncounterRecord r : log) n.merge(r.species, 1L, Long::sum);
        List<Map.Entry<String, Long>> l = new ArrayList<>(n.entrySet());
        l.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return l;
    }

    /** Session summary: total, rare and shiny encounters per hour over {@code hours}. */
    public static Estimate[] summary(Collection<EncounterRecord> log, double hours) {
        long all = log.size(), rare = 0, shiny = 0;
        for (EncounterRecord r : log) { if (r.rarity != null) rare++; if (r.shiny) shiny++; }
        return new Estimate[]{rate(all, hours, "all encounters"), rate(rare, hours, "rare encounters"), rate(shiny, hours, "shiny encounters")};
    }
}

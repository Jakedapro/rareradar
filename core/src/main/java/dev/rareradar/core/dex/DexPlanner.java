package dev.rareradar.core.dex;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.spawn.SpawnRule;

import java.util.*;
import java.util.function.Function;

/**
 * Pokédex completion dashboard and missing-species planner. Milestones come only from the CobbleGalaxy Dex Rewards
 * data the client received or from user entry — never invented.
 */
public final class DexPlanner {
    public final Set<String> universe = new TreeSet<>();     // species counted by the dex view
    public final Set<String> caught = new TreeSet<>();
    public final Set<String> shinyCaught = new TreeSet<>();
    public final List<DexTier> tiers = new ArrayList<>();
    public long updatedAt;
    public String source = "none";

    public int total() { return universe.size(); }
    public int owned() { int n = 0; for (String s : caught) if (universe.contains(s)) n++; return n; }
    public double percent() { return universe.isEmpty() ? Double.NaN : 100.0 * owned() / universe.size(); }

    public List<String> missing() {
        List<String> m = new ArrayList<>();
        for (String s : universe) if (!caught.contains(s)) m.add(s);
        return m;
    }

    /** Next unclaimed tier above the current percentage and how many more species it needs. */
    public Optional<Map.Entry<DexTier, Integer>> nextTier() {
        double pct = percent();
        if (!Double.isFinite(pct)) return Optional.empty();
        DexTier best = null;
        for (DexTier t : tiers) if (!t.claimed() && t.percentage() > pct && (best == null || t.percentage() < best.percentage())) best = t;
        if (best == null) return Optional.empty();
        int need = (int) Math.ceil(best.percentage() / 100.0 * total() - 1e-9) - owned();
        return Optional.of(Map.entry(best, Math.max(0, need)));
    }

    public List<DexTier> claimable() {
        List<DexTier> c = new ArrayList<>();
        for (DexTier t : tiers) if (t.claimable() && !t.claimed()) c.add(t);
        return c;
    }

    public record Suggestion(String species, double score, String why) {}

    /**
     * Ranks missing species by accessibility: seen recently (by encounter rate), has known spawn rules, and spawn
     * bucket (common < uncommon < rare < ultra-rare). Unknowns rank lower rather than being guessed.
     */
    public List<Suggestion> suggest(Function<String, Double> sightingsPerHour, Function<String, List<SpawnRule>> rules, int limit) {
        List<Suggestion> out = new ArrayList<>();
        for (String s : missing()) {
            double rate = Optional.ofNullable(sightingsPerHour.apply(s)).orElse(Double.NaN);
            List<SpawnRule> r = rules.apply(s);
            double score = 0;
            List<String> why = new ArrayList<>();
            if (Double.isFinite(rate) && rate > 0) { score += 10 + Math.min(40, rate * 10); why.add(String.format(Locale.ROOT, "you see ≈%.1f/h", rate)); }
            if (!r.isEmpty()) {
                score += 5;
                String b = r.get(0).bucket == null ? "" : r.get(0).bucket.toLowerCase(Locale.ROOT);
                score += switch (b) { case "common" -> 8; case "uncommon" -> 5; case "rare" -> 2; default -> 0; };
                why.add(r.get(0).describe());
            } else why.add("spawn conditions unknown (import spawn files)");
            out.add(new Suggestion(s, score, String.join("; ", why)));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed().thenComparing(Suggestion::species));
        return out.subList(0, Math.min(limit, out.size()));
    }

    public void markCaught(String species, boolean shiny) {
        String s = PokemonObservation.norm(species);
        caught.add(s);
        if (shiny) shinyCaught.add(s);
    }
}

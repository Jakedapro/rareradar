package dev.rareradar.core.mega;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.*;

/**
 * Mega Evolution progression. Boss drop rates are estimated only from the user's own logged wins (Jeffreys interval)
 * — the wiki says level 100+ bosses drop "a random Mega Stone" but never gives a frequency, so none is assumed.
 */
public final class MegaPlanner {
    public boolean hasBracelet;
    public Set<String> ownedStones = new TreeSet<>();
    /** User's per-Pokémon preference/competitive utility score 0..10 (manual; no built-in tier list). */
    public Map<String, Double> utility = new HashMap<>();

    /** Drop probability per boss win with a 90% Jeffreys interval. */
    public static Estimate dropRate(List<BossEncounter> log) {
        long wins = 0, drops = 0;
        for (BossEncounter b : log) if (b.outcome == BossEncounter.Outcome.WON) { wins++; if (b.megaStoneDropped) drops++; }
        if (wins == 0) return Estimate.unknown("no boss wins logged");
        double a = drops + 0.5, bb = wins - drops + 0.5;
        double mean = a / (a + bb);
        double[] w = Stats.wilson(drops, wins, 0.9);
        return new Estimate(mean, w[0], w[1], (int) wins, Confidence.of((int) wins, (w[1] - w[0]) / Math.max(mean, 1e-9)),
                drops + " stone(s) from " + wins + " boss win(s)");
    }

    /** Expected boss wins to obtain one specific stone, assuming each drop is uniform over {@code stonePool} stones. */
    public static Estimate winsForSpecificStone(List<BossEncounter> log, int stonePool) {
        Estimate r = dropRate(log);
        if (!r.known() || stonePool <= 0) return Estimate.unknown(r.known() ? "stone pool size unknown" : r.basis());
        double p = r.value() / stonePool, lo = r.low() / stonePool, hi = r.high() / stonePool;
        return new Estimate(1 / p, 1 / hi, lo > 0 ? 1 / lo : Double.POSITIVE_INFINITY, r.samples(), r.confidence().min(Confidence.LOW),
                "assumes uniform drops over " + stonePool + " stones (not documented)");
    }

    public record Candidate(String species, String stone, boolean stoneOwned, double utility, double score, String why) {}

    /** Ranks Mega candidates among the user's Pokémon: owned stone first, then user utility and IV quality. */
    public List<Candidate> rank(Map<String, String> speciesToStone, Map<String, Integer> perfectIvsBySpecies) {
        List<Candidate> out = new ArrayList<>();
        for (var e : speciesToStone.entrySet()) {
            String sp = e.getKey();
            boolean owned = ownedStones.contains(e.getValue());
            double u = utility.getOrDefault(sp, 5.0);
            int iv = perfectIvsBySpecies.getOrDefault(sp, -1);
            double score = (owned ? 100 : 0) + u * 5 + Math.max(0, iv) * 2;
            String why = (owned ? "stone owned" : "needs " + e.getValue()) + ", utility " + u
                    + (iv >= 0 ? ", " + iv + " perfect IVs" : ", IVs unknown") + (hasBracelet ? "" : " · Mega Bracelet still needed");
            out.add(new Candidate(sp, e.getValue(), owned, u, score, why));
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out;
    }
}

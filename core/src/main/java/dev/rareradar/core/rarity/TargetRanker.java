package dev.rareradar.core.rarity;

import java.util.Comparator;

/**
 * Orders candidate targets: watchlist priority first, then rarity score, then distance. Distance is penalised with
 * a configurable half-distance so a slightly rarer Pokémon 300 blocks away doesn't beat a good one 10 blocks away.
 */
public final class TargetRanker {
    public record Candidate<T>(T ref, RarityResult rarity, double distance) {}

    private TargetRanker() {}

    /** Utility in (0, score]; equals score at distance 0, half the score at {@code halfDistance}. */
    public static double utility(RarityResult r, double distance, double halfDistance) {
        if (!r.qualifies()) return 0;
        double h = halfDistance <= 0 ? 64 : halfDistance;
        return r.score() / (1 + Math.max(0, distance) / h);
    }

    public static <T> Comparator<Candidate<T>> comparator(double halfDistance) {
        return Comparator.<Candidate<T>>comparingInt(c -> -c.rarity().watchPriority())
                .thenComparingDouble(c -> -utility(c.rarity(), c.distance(), halfDistance))
                .thenComparingDouble(Candidate::distance);
    }
}

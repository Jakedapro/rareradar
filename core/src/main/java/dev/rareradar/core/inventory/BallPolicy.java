package dev.rareradar.core.inventory;

import java.util.*;

/**
 * Which ball AutoHunt may throw. Default behaviour: protect Master Balls and other configured balls, prefer cheap
 * balls for low-value targets, respect reserves, and never spend a protected ball unless the user explicitly allowed
 * it for that throw. Fixes the original V1 behaviour that threw Master Balls first.
 */
public final class BallPolicy {
    /** Cheapest → most expensive. Unlisted balls are treated as mid-tier. */
    public List<String> order = new ArrayList<>(List.of("poke_ball", "premier_ball", "great_ball", "ultra_ball"));
    public Set<String> protectedBalls = new HashSet<>(Set.of("master_ball", "cherish_ball", "beast_ball", "park_ball"));
    public Map<String, Integer> reserves = new HashMap<>();
    /** Targets with rarity score at or above this may use the best non-protected ball available. */
    public double highValueScore = 4.0;
    /** Maximum balls AutoHunt may throw per target before giving up. */
    public int maxThrowsPerTarget = 8;

    public record Choice(int slot, String ball, String reason) {}

    /**
     * @param hotbar ball id per hotbar slot (null = not a ball), counts across the whole inventory per ball id
     * @param allowProtected explicit, per-throw user permission to use protected balls
     */
    public Choice choose(String[] hotbar, Map<String, Integer> counts, double targetScore, boolean allowProtected) {
        int bestSlot = -1; String best = null; int bestRank = 0;
        boolean wantBest = targetScore >= highValueScore;
        for (int i = 0; i < hotbar.length; i++) {
            String b = hotbar[i];
            if (b == null) continue;
            if (protectedBalls.contains(b) && !allowProtected) continue;
            int have = counts.getOrDefault(b, 0);
            if (have <= reserves.getOrDefault(b, 0)) continue;
            int rank = order.indexOf(b);
            if (rank < 0) rank = protectedBalls.contains(b) ? 100 : order.size() / 2;
            boolean better = best == null || (wantBest ? rank > bestRank : rank < bestRank);
            if (better) { best = b; bestSlot = i; bestRank = rank; }
        }
        if (best == null) return new Choice(-1, null, "no usable ball (protected balls and reserves are respected)");
        return new Choice(bestSlot, best, wantBest ? "high-value target: best unprotected ball" : "low-value target: cheapest ball");
    }
}

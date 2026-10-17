package dev.rareradar.core.automation;

import java.util.*;

/**
 * Pre-flight resource check before automation starts. If the minimum resources cannot be established, the task is
 * refused rather than started with unknown demand.
 */
public final class ResourceCheck {
    public record Result(boolean ok, List<String> problems, List<String> warnings) {}

    private ResourceCheck() {}

    /**
     * @param usableBalls balls the ball policy would allow (protected balls and reserves excluded), by id
     * @param minBalls minimum usable balls to start a hunting run
     */
    public static Result forHunting(Map<String, Integer> usableBalls, int minBalls, float health, float minHealth) {
        List<String> p = new ArrayList<>(), w = new ArrayList<>();
        int total = 0;
        for (int v : usableBalls.values()) total += Math.max(0, v);
        if (total < minBalls) p.add("only " + total + " usable Poké Balls (need at least " + minBalls + "; protected balls and reserves are excluded)");
        else if (total < minBalls * 3) w.add("ball supply is low (" + total + ")");
        if (health < minHealth) p.add("health too low to start");
        return new Result(p.isEmpty(), p, w);
    }
}

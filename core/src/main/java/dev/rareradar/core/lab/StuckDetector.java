package dev.rareradar.core.lab;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pure progress monitor for a walking player. Feed one observation per tick. NO_PROGRESS = barely moving;
 * OSCILLATION = moving a lot while getting no closer to the goal. Replans of the same goal are counted separately.
 */
public final class StuckDetector {
    public enum Reason { NONE, NO_PROGRESS, OSCILLATION, REPEATED_REPLAN }

    private record Obs(int tick, double x, double z, double dist) {}

    private final int windowTicks;
    private final double minProgress;
    private final int maxReplans;
    private final Deque<Obs> window = new ArrayDeque<>();
    private int replans;

    /** @param windowTicks look-back window; @param minProgress blocks the goal must get closer within it. */
    public StuckDetector(int windowTicks, double minProgress, int maxReplans) {
        this.windowTicks = windowTicks;
        this.minProgress = minProgress;
        this.maxReplans = maxReplans;
    }

    public Reason observe(int tick, double x, double z, double distToGoal) {
        window.addLast(new Obs(tick, x, z, distToGoal));
        while (window.size() > 1 && tick - window.peekFirst().tick > windowTicks) window.removeFirst();
        if (replans > maxReplans) return Reason.REPEATED_REPLAN;
        Obs first = window.peekFirst();
        if (tick - first.tick < windowTicks * 0.9) return Reason.NONE;
        double gained = first.dist - distToGoal;
        if (gained >= minProgress) return Reason.NONE;
        double travelled = 0;
        Obs prev = null;
        for (Obs o : window) {
            if (prev != null) travelled += Math.hypot(o.x - prev.x, o.z - prev.z);
            prev = o;
        }
        return travelled < minProgress * 1.5 ? Reason.NO_PROGRESS : Reason.OSCILLATION;
    }

    /** Records that the same goal was replanned; returns true once the limit is exceeded. */
    public boolean noteReplan() { return ++replans > maxReplans; }

    public int replans() { return replans; }

    /** Forget the window (after a recovery action) but keep the replan count. */
    public void resetWindow() { window.clear(); }
}

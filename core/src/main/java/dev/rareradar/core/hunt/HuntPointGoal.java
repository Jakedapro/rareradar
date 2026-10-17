package dev.rareradar.core.hunt;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Progress toward a Hunt Point purchase. The default goal is the Mega Bracelet at 150 Hunt Points, as reported on
 * the CobbleGalaxy wiki Mega Evolution page; both name and cost are user-editable.
 */
public final class HuntPointGoal {
    public String name = "Mega Bracelet";
    public double cost = 150;
    public String costSource = "CobbleGalaxy wiki, Mega Evolution page (reported, verify in /hunt shop)";
    public double currentPoints;

    public double remaining() { return Math.max(0, cost - currentPoints); }

    public record Projection(double remaining, Estimate huntsNeeded, Estimate minutesNeeded) {}

    /** Projects hunts and time required from the user's completed hunts that recorded point rewards. */
    public Projection project(List<HuntObjective> completed) {
        List<Double> pts = new ArrayList<>(), mins = new ArrayList<>();
        for (HuntObjective h : completed) {
            if (h.rewardHuntPoints == null || h.rewardHuntPoints <= 0) continue;
            pts.add(h.rewardHuntPoints);
            if (h.completedAt != null && h.completedAt > h.createdAt) mins.add((h.completedAt - h.createdAt) / 60_000.0);
        }
        double rem = remaining();
        if (rem == 0) return new Projection(0, new Estimate(0, 0, 0, 1, Confidence.HIGH, "goal reached"), new Estimate(0, 0, 0, 1, Confidence.HIGH, "goal reached"));
        if (pts.isEmpty()) return new Projection(rem, Estimate.unknown("no completed hunts with recorded Hunt Points"), Estimate.unknown("no history"));
        double[] p = Stats.toArray(pts);
        double mean = Stats.mean(p);
        double lo = Stats.quantile(p, 0.25), hi = Stats.quantile(p, 0.75);
        Estimate hunts = Estimate.of(Math.ceil(rem / mean), Math.ceil(rem / Math.max(hi, 1e-9)), Math.ceil(rem / Math.max(lo, 1e-9)), p.length, "average " + Math.round(mean) + " points per hunt");
        Estimate time = Estimate.unknown("no hunt durations recorded");
        if (!mins.isEmpty()) {
            double[] m = Stats.toArray(mins);
            double mm = Stats.median(m);
            // Note: hunt durations measured from when the hunt was added until completion, which may include idle time.
            time = Estimate.of(hunts.value() * mm, hunts.low() * Stats.quantile(m, 0.25), hunts.high() * Stats.quantile(m, 0.75), m.length,
                    "median " + Math.round(mm) + " min per hunt (includes time not actively hunting)");
        }
        return new Projection(rem, hunts, time);
    }
}

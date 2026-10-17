package dev.rareradar.core.profit;

import java.util.UUID;

/**
 * One logged stretch of an activity. {@code realisedRevenue} is Pokédollars actually received;
 * {@code estimatedValue} is the estimated worth of things obtained but not sold (Pokémon, items) and is kept
 * separate so realised profit is never inflated by estimates.
 */
public final class ActivityRecord {
    public String id = UUID.randomUUID().toString();
    public Activity activity = Activity.OTHER;
    public long start;
    public double minutes;
    public double realisedRevenue;
    public double estimatedValue;
    public double costs;
    public String note = "";

    public static ActivityRecord of(Activity a, long start, double minutes, double revenue, double estimated, double costs) {
        ActivityRecord r = new ActivityRecord();
        r.activity = a; r.start = start; r.minutes = minutes;
        r.realisedRevenue = revenue; r.estimatedValue = estimated; r.costs = costs;
        return r;
    }

    public double realisedProfit() { return realisedRevenue - costs; }
    public double expectedProfit() { return realisedRevenue + estimatedValue - costs; }

    public String validate() {
        if (activity == null) return "missing activity";
        if (!(minutes > 0) || minutes > 24 * 60) return "duration must be between 0 and 24 hours";
        for (double v : new double[]{realisedRevenue, estimatedValue, costs})
            if (!Double.isFinite(v) || v < 0) return "amounts must be non-negative numbers";
        if (start <= 0) return "missing start time";
        return null;
    }
}

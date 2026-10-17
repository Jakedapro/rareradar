package dev.rareradar.core.profit;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Money;
import dev.rareradar.core.util.Stats;

import java.util.*;

/**
 * Profit per hour by activity: P = R − C, P_h = P / T × 60 (T in minutes).
 * Uses the ratio estimator ΣP/ΣT with a delta-method interval, so long sessions weigh more than short ones.
 */
public final class ProfitEngine {
    /** A comparable option: either measured from logs or supplied by another module (e.g. hunt optimiser). */
    public record Option(String name, Activity activity, Estimate perHour, String explanation, boolean measured) {}

    private ProfitEngine() {}

    /** Profit per hour for a list of records; {@code realisedOnly} excludes estimated inventory value. */
    public static Estimate perHour(List<ActivityRecord> recs, boolean realisedOnly) {
        int n = recs.size();
        if (n == 0) return Estimate.unknown("no logged sessions");
        double sumP = 0, sumT = 0;
        for (ActivityRecord r : recs) { sumP += realisedOnly ? r.realisedProfit() : r.expectedProfit(); sumT += r.minutes; }
        double rate = sumP / sumT; // per minute
        double hours = sumT / 60;
        String basis = n + " session(s), " + String.format(Locale.ROOT, "%.1f", hours) + " h logged"
                + (realisedOnly ? ", realised only" : ", incl. estimated unsold value");
        if (n < 2) return new Estimate(rate * 60, Double.NaN, Double.NaN, 1, Confidence.LOW, basis);
        double tBar = sumT / n, ss = 0;
        for (ActivityRecord r : recs) {
            double p = realisedOnly ? r.realisedProfit() : r.expectedProfit();
            ss += (p - rate * r.minutes) * (p - rate * r.minutes);
        }
        double se = Math.sqrt(ss / (n - 1) / n) / tBar;
        double z = Stats.zFor(0.9);
        Estimate e = Estimate.of(rate * 60, (rate - z * se) * 60, (rate + z * se) * 60, n, basis);
        if (!realisedOnly && hasEstimates(recs)) e = e.withConfidence(e.confidence().min(Confidence.MEDIUM));
        return e;
    }

    private static boolean hasEstimates(List<ActivityRecord> recs) {
        for (ActivityRecord r : recs) if (r.estimatedValue > 0) return true;
        return false;
    }

    /** Per-activity options measured from the user's own log, restricted to records newer than {@code since}. */
    public static List<Option> measured(Collection<ActivityRecord> log, long since, boolean realisedOnly) {
        Map<Activity, List<ActivityRecord>> by = new EnumMap<>(Activity.class);
        for (ActivityRecord r : log) if (r.start >= since) by.computeIfAbsent(r.activity, k -> new ArrayList<>()).add(r);
        List<Option> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            Estimate est = perHour(e.getValue(), realisedOnly);
            out.add(new Option(e.getKey().label, e.getKey(), est, "Measured from your log: " + est.basis(), true));
        }
        return out;
    }

    /**
     * Ranks options by expected profit/hour and writes a plain-language recommendation. Ties within overlapping
     * intervals are reported as uncertain rather than presented as a clear winner.
     */
    public static Recommendation recommend(List<Option> options) {
        List<Option> known = new ArrayList<>();
        for (Option o : options) if (o.perHour().known()) known.add(o);
        known.sort(Comparator.comparingDouble((Option o) -> o.perHour().value()).reversed());
        List<String> assumptions = new ArrayList<>();
        assumptions.add("Past results from your own log are assumed to be representative of the near future.");
        assumptions.add("Estimated values of unsold Pokémon/items are not realised profit until sold.");
        if (known.isEmpty()) {
            return new Recommendation(null, known, "Not enough data yet. Log a few sessions (or GTS prices) to get recommendations.", assumptions);
        }
        Option best = known.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(best.measured() ? "Your recent results suggest " : "Current estimates suggest ")
          .append(best.name()).append(" is the most profitable option at about ")
          .append(Money.fmt(best.perHour().value())).append("/h (").append(best.perHour().confidence().label).append(" confidence).");
        if (known.size() > 1) {
            Option second = known.get(1);
            boolean overlap = overlaps(best.perHour(), second.perHour());
            sb.append(' ').append(overlap
                    ? "It is not clearly better than " + second.name() + " (" + Money.fmt(second.perHour().value()) + "/h): the uncertainty ranges overlap."
                    : "That is clearly ahead of " + second.name() + " (" + Money.fmt(second.perHour().value()) + "/h).");
        }
        if (best.perHour().confidence().ordinal() <= Confidence.LOW.ordinal())
            sb.append(" Treat this as a rough guide until more sessions are logged.");
        return new Recommendation(best, known, sb.toString(), assumptions);
    }

    static boolean overlaps(Estimate a, Estimate b) {
        if (!Double.isFinite(a.low()) || !Double.isFinite(b.high())) return true;
        return a.low() <= b.high();
    }

    public record Recommendation(Option best, List<Option> ranked, String text, List<String> assumptions) {}
}

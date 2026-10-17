package dev.rareradar.core.hunt;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.*;

/**
 * Ranks active hunts by H = expected reward / expected completion time.
 *
 * Completion time for k remaining catches: T = travel + k / (λ·p) hours, where λ is the encounter rate of the
 * required Pokémon (Jeffreys posterior mean (n+½)/t from the user's encounter log) and p the user's catch success
 * rate (Beta posterior). Intervals come from the Poisson rate interval. Hunts with no encounter data are reported
 * as "unknown time" rather than given an invented rate.
 */
public final class HuntOptimizer {
    /** Observed encounters of the target and the hours of observation they were counted over. */
    public record RateInput(long encounters, double exposureHours, String where) {}

    public record Ranked(HuntObjective hunt, Estimate minutes, Estimate rewardValue, Estimate valuePerHour,
                         Estimate pointsPerHour, String explanation) {}

    public static final class Inputs {
        /** Encounter rate lookup for a hunt (by species/aspects); return null if never observed. */
        public java.util.function.Function<HuntObjective, RateInput> rate = h -> null;
        /** Catch attempts and successes from the user's log. */
        public long catchAttempts, catchSuccesses;
        /** Minutes of travel before hunting can start (user setting / route estimate). */
        public double travelMinutes;
        /** User's value of one Hunt Point in Pokédollars (NaN = rank by points/hour instead). */
        public double huntPointValue = Double.NaN;
        /** Average completion minutes from history, used when no rate data exists for a species. */
        public double historicalMinutesPerCatch = Double.NaN;
    }

    private HuntOptimizer() {}

    public static double catchProbability(long attempts, long successes) {
        // Beta(1,1) prior: mean (s+1)/(n+2). With no data this is 0.5 and confidence is NONE.
        return (successes + 1.0) / (attempts + 2.0);
    }

    public static Estimate completionMinutes(HuntObjective h, Inputs in) {
        int k = h.remaining();
        if (k == 0) return new Estimate(0, 0, 0, 1, Confidence.HIGH, "already complete");
        double p = catchProbability(in.catchAttempts, in.catchSuccesses);
        RateInput r = in.rate.apply(h);
        if (r == null || r.exposureHours() <= 0) {
            if (Double.isFinite(in.historicalMinutesPerCatch)) {
                double m = in.travelMinutes + k * in.historicalMinutesPerCatch;
                return new Estimate(m, m * 0.5, m * 2, 0, Confidence.LOW, "no encounter data for this species; using your average minutes per hunt catch");
            }
            return Estimate.unknown("no encounter data for " + h.species + " yet");
        }
        double lambda = (r.encounters() + 0.5) / r.exposureHours(); // Jeffreys posterior mean, per hour
        double[] ci = Stats.poissonRate(r.encounters(), r.exposureHours(), 0.8);
        double eff = lambda * p, effLo = Math.max(1e-9, ci[0] * p), effHi = ci[1] * p;
        double mins = in.travelMinutes + 60.0 * k / eff;
        double hi = in.travelMinutes + 60.0 * k / effLo;
        double lo = in.travelMinutes + 60.0 * k / effHi;
        Confidence c = Confidence.of((int) r.encounters(), (hi - lo) / mins);
        if (in.catchAttempts < 5) c = c.min(Confidence.LOW);
        return new Estimate(mins, lo, Math.min(hi, 60 * 24 * 7), (int) r.encounters(), c,
                String.format(Locale.ROOT, "%d sightings in %.1f h %s; catch rate %.0f%%", r.encounters(), r.exposureHours(), r.where(), p * 100));
    }

    public static Estimate rewardValue(HuntObjective h, Inputs in) {
        double v = 0;
        boolean any = false;
        List<String> parts = new ArrayList<>();
        if (h.rewardMoney != null) { v += h.rewardMoney; any = true; parts.add("money"); }
        if (h.rewardOtherValue != null) { v += h.rewardOtherValue; any = true; parts.add("items (your estimate)"); }
        if (h.rewardHuntPoints != null && Double.isFinite(in.huntPointValue)) {
            v += h.rewardHuntPoints * in.huntPointValue; any = true; parts.add("Hunt Points at your valuation");
        }
        if (!any) return Estimate.unknown("reward value unknown — enter it or set a Hunt Point value");
        return new Estimate(v, v, v, 1, Confidence.MEDIUM, String.join(" + ", parts));
    }

    public static List<Ranked> rank(Collection<HuntObjective> active, Inputs in) {
        List<Ranked> out = new ArrayList<>();
        for (HuntObjective h : active) {
            if (h.remaining() == 0) continue;
            Estimate t = completionMinutes(h, in);
            Estimate rv = rewardValue(h, in);
            Estimate vph = t.known() && rv.known() && t.value() > 0
                    ? new Estimate(rv.value() / t.value() * 60, rv.value() / t.high() * 60, rv.value() / Math.max(1e-9, t.low()) * 60,
                    t.samples(), t.confidence().min(rv.confidence()), "reward ÷ expected time")
                    : Estimate.unknown(!t.known() ? t.basis() : rv.basis());
            Estimate pph = t.known() && h.rewardHuntPoints != null && t.value() > 0
                    ? new Estimate(h.rewardHuntPoints / t.value() * 60, h.rewardHuntPoints / t.high() * 60,
                    h.rewardHuntPoints / Math.max(1e-9, t.low()) * 60, t.samples(), t.confidence(), "Hunt Points ÷ expected time")
                    : Estimate.unknown("points or time unknown");
            String exp = (h.species == null ? h.title : h.species) + ": " + h.remaining() + " left; "
                    + (t.known() ? "≈" + Math.round(t.value()) + " min (" + t.basis() + ")" : t.basis());
            out.add(new Ranked(h, t, rv, vph, pph, exp));
        }
        out.sort(Comparator.comparingDouble((Ranked r) -> score(r)).reversed()
                .thenComparingDouble(r -> r.minutes().known() ? r.minutes().value() : Double.MAX_VALUE));
        return out;
    }

    private static double score(Ranked r) {
        if (r.valuePerHour().known()) return 1e12 + r.valuePerHour().value();
        if (r.pointsPerHour().known()) return 1e6 + r.pointsPerHour().value();
        return r.minutes().known() ? 1.0 / (1 + r.minutes().value()) : -1;
    }
}

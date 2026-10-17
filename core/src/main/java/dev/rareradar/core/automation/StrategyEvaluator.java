package dev.rareradar.core.automation;

import dev.rareradar.core.util.Stats;

import java.util.*;

/**
 * Compares predicted profit/hour with what was realised and derives a calibration factor per strategy. Transparent
 * statistics only: the factor is the median of realised/predicted ratios, shrunk towards 1 when there are few
 * sessions (k/(k+3) weight), and clamped to [0, 2].
 */
public final class StrategyEvaluator {
    public record Outcome(String strategy, double predictedPerHour, double realisedPerHour, long ts) {}

    public record Calibration(String strategy, int n, double factor, double medianErrorPct) {}

    private final List<Outcome> outcomes = new ArrayList<>();

    public void record(Outcome o) {
        if (Double.isFinite(o.predictedPerHour()) && o.predictedPerHour() > 0 && Double.isFinite(o.realisedPerHour())) outcomes.add(o);
    }

    public List<Outcome> outcomes() { return Collections.unmodifiableList(outcomes); }

    public Calibration calibration(String strategy) {
        List<Double> ratios = new ArrayList<>(), errs = new ArrayList<>();
        for (Outcome o : outcomes) if (o.strategy().equals(strategy)) {
            ratios.add(o.realisedPerHour() / o.predictedPerHour());
            errs.add(Math.abs(o.realisedPerHour() - o.predictedPerHour()) / o.predictedPerHour() * 100);
        }
        int n = ratios.size();
        if (n == 0) return new Calibration(strategy, 0, 1.0, Double.NaN);
        double med = Stats.median(Stats.toArray(ratios));
        double w = n / (n + 3.0);
        double f = Math.max(0, Math.min(2, w * med + (1 - w)));
        return new Calibration(strategy, n, f, Stats.median(Stats.toArray(errs)));
    }

    /** Applies the calibration to a new prediction. */
    public double calibrated(String strategy, double predictedPerHour) {
        return predictedPerHour * calibration(strategy).factor();
    }
}

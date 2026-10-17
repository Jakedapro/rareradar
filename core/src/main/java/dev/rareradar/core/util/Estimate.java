package dev.rareradar.core.util;

/**
 * A point estimate with an uncertainty interval, sample size and confidence. {@code value} may be NaN when unknown;
 * callers must render NaN as "unknown", never as zero.
 */
public record Estimate(double value, double low, double high, int samples, Confidence confidence, String basis) {
    public static Estimate unknown(String why) {
        return new Estimate(Double.NaN, Double.NaN, Double.NaN, 0, Confidence.NONE, why);
    }

    public boolean known() { return Double.isFinite(value); }

    public static Estimate of(double value, double low, double high, int samples, String basis) {
        double rel = Math.abs(value) < 1e-9 ? Double.POSITIVE_INFINITY : (high - low) / Math.abs(value);
        return new Estimate(value, low, high, samples, Confidence.of(samples, rel), basis);
    }

    public Estimate withConfidence(Confidence c) { return new Estimate(value, low, high, samples, c, basis); }

    public Estimate scale(double k) {
        return new Estimate(value * k, Math.min(low * k, high * k), Math.max(low * k, high * k), samples, confidence, basis);
    }
}

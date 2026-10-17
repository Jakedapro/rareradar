package dev.rareradar.core.util;

import java.util.Arrays;
import java.util.Collection;

/** Small, allocation-light statistics helpers. All methods treat NaN-free finite input; empty input yields NaN. */
public final class Stats {
    private Stats() {}

    public static double[] toArray(Collection<? extends Number> xs) {
        double[] out = new double[xs.size()];
        int i = 0;
        for (Number n : xs) out[i++] = n.doubleValue();
        return out;
    }

    public static double mean(double[] xs) {
        if (xs.length == 0) return Double.NaN;
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    /** Sample standard deviation (n-1). NaN for fewer than two values. */
    public static double stdev(double[] xs) {
        if (xs.length < 2) return Double.NaN;
        double m = mean(xs), s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return Math.sqrt(s / (xs.length - 1));
    }

    /** Linear-interpolated quantile, q in [0,1]. Does not modify the input. */
    public static double quantile(double[] xs, double q) {
        if (xs.length == 0) return Double.NaN;
        double[] s = xs.clone();
        Arrays.sort(s);
        return quantileSorted(s, q);
    }

    public static double quantileSorted(double[] s, double q) {
        if (s.length == 0) return Double.NaN;
        double pos = Math.max(0, Math.min(1, q)) * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    public static double median(double[] xs) { return quantile(xs, 0.5); }

    /** Median absolute deviation scaled to be comparable with a normal stdev (×1.4826). Robust to outlier listings. */
    public static double mad(double[] xs) {
        if (xs.length == 0) return Double.NaN;
        double m = median(xs);
        double[] d = new double[xs.length];
        for (int i = 0; i < xs.length; i++) d[i] = Math.abs(xs[i] - m);
        return 1.4826 * median(d);
    }

    /** Theil–Sen slope estimator (median of pairwise slopes); robust trend for small noisy samples. NaN if undefined. */
    public static double theilSen(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return Double.NaN;
        double[] slopes = new double[n * (n - 1) / 2];
        int k = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (x[j] != x[i]) slopes[k++] = (y[j] - y[i]) / (x[j] - x[i]);
        return k == 0 ? Double.NaN : median(Arrays.copyOf(slopes, k));
    }

    /** Two-sided standard normal quantile for a confidence level (e.g. 0.95 → 1.96). Acklam's approximation. */
    public static double zFor(double confidence) {
        return normalInv(1 - (1 - confidence) / 2);
    }

    public static double normalInv(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pl = 0.02425, q, r;
        if (p < pl) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1);
        }
        if (p > 1 - pl) {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1);
        }
        q = p - 0.5; r = q * q;
        return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1);
    }

    /** Wilson score interval for a binomial proportion. Returns {low, high}. */
    public static double[] wilson(long successes, long trials, double confidence) {
        if (trials <= 0) return new double[]{0, 1};
        double z = zFor(confidence), n = trials, p = successes / n;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    /**
     * Approximate confidence interval for a Poisson rate (events / exposure), using the
     * Wilson–Hilferty approximation to the exact (Garwood) chi-square bounds. Returns {low, high} per unit exposure.
     */
    public static double[] poissonRate(long events, double exposure, double confidence) {
        if (exposure <= 0) return new double[]{0, Double.POSITIVE_INFINITY};
        double z = zFor(confidence);
        double lo = events == 0 ? 0 : events * Math.pow(1 - 1.0 / (9 * events) - z / (3 * Math.sqrt(events)), 3);
        double k = events + 1;
        double hi = k * Math.pow(1 - 1.0 / (9 * k) + z / (3 * Math.sqrt(k)), 3);
        return new double[]{Math.max(0, lo) / exposure, hi / exposure};
    }
}

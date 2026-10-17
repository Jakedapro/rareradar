package dev.rareradar.core.ml;

import dev.rareradar.core.market.PriceObservation;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Stats;

import java.util.*;

/**
 * Optional learned valuation: ridge regression on log(price) with species one-hot, shiny, IV and listing features.
 * It is used only when k-fold cross-validation shows it beats the heuristic baseline (per-bucket median) by a margin;
 * otherwise {@link #usable()} is false and callers keep the heuristic. Small, local, deterministic, no dependencies.
 */
public final class PriceModel {
    public record Metrics(int n, double modelMae, double baselineMae, double lambda, boolean usable, String summary) {}

    private static final int MIN_SAMPLES = 30, FOLDS = 5, MAX_SPECIES = 40;
    private static final double[] LAMBDAS = {0.1, 1, 10};
    private static final double REQUIRED_IMPROVEMENT = 0.9; // model MAE must be ≤ 90% of baseline MAE

    private List<String> species = List.of();
    private double[] beta;
    private double residLo, residHi;
    private int shinyExamples;
    private Metrics metrics = new Metrics(0, Double.NaN, Double.NaN, Double.NaN, false, "not trained");

    public Metrics metrics() { return metrics; }
    public boolean usable() { return metrics.usable(); }

    /** Trains on confirmed sales/purchases plus listings (listing is a feature, predictions are for a sale). */
    public Metrics train(List<PriceObservation> all, long seed) {
        List<PriceObservation> data = new ArrayList<>();
        for (PriceObservation p : all) if (p.price > 0 && p.kind != PriceObservation.Kind.STS) data.add(p);
        if (data.size() < MIN_SAMPLES) {
            metrics = new Metrics(data.size(), Double.NaN, Double.NaN, Double.NaN, false,
                    "needs ≥" + MIN_SAMPLES + " priced observations (have " + data.size() + "); using heuristic");
            return metrics;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (PriceObservation p : data) counts.merge(p.species, 1, Integer::sum);
        List<String> sp = new ArrayList<>(counts.keySet());
        sp.sort(Comparator.comparingInt((String s) -> -counts.get(s)).thenComparing(s -> s));
        species = sp.subList(0, Math.min(MAX_SPECIES, sp.size()));

        Collections.shuffle(data, new Random(seed));
        double bestLambda = LAMBDAS[0], bestMae = Double.MAX_VALUE, baseMae = Double.NaN;
        List<Double> bestResid = List.of();
        for (double lambda : LAMBDAS) {
            double sumM = 0, sumB = 0;
            List<Double> resid = new ArrayList<>();
            for (int f = 0; f < FOLDS; f++) {
                List<PriceObservation> tr = new ArrayList<>(), te = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) (i % FOLDS == f ? te : tr).add(data.get(i));
                double[] b = fit(tr, lambda);
                Map<String, Double> baseline = baseline(tr);
                double global = globalMedian(tr);
                for (PriceObservation p : te) {
                    double y = Math.log(p.price);
                    double yhat = dot(b, features(p.species, Boolean.TRUE.equals(p.shiny), p.ivs, p.kind == PriceObservation.Kind.LISTING));
                    double base = baseline.getOrDefault(key(p), global);
                    sumM += Math.abs(y - yhat);
                    sumB += Math.abs(y - base);
                    resid.add(y - yhat);
                }
            }
            double mae = sumM / data.size();
            baseMae = sumB / data.size();
            if (mae < bestMae) { bestMae = mae; bestLambda = lambda; bestResid = resid; }
        }
        beta = fit(data, bestLambda);
        shinyExamples = 0;
        for (PriceObservation p : data) if (Boolean.TRUE.equals(p.shiny)) shinyExamples++;
        double[] r = Stats.toArray(bestResid);
        residLo = Stats.quantile(r, 0.1);
        residHi = Stats.quantile(r, 0.9);
        boolean ok = bestMae <= REQUIRED_IMPROVEMENT * baseMae;
        metrics = new Metrics(data.size(), bestMae, baseMae, bestLambda, ok, String.format(Locale.ROOT,
                "%d-fold CV on %d observations: model log-MAE %.3f (≈%.0f%% typical error) vs heuristic %.3f → %s",
                FOLDS, data.size(), bestMae, (Math.exp(bestMae) - 1) * 100, baseMae, ok ? "model enabled" : "heuristic kept"));
        return metrics;
    }

    /** Predicted sale value with an 80% interval from cross-validated residuals; unknown when the model is not usable. */
    public Estimate predict(PokemonObservation o) {
        if (!usable() || beta == null) return Estimate.unknown(metrics.summary());
        // Refuse to extrapolate to things the model never saw: it would silently price them as the average.
        if (!species.contains(o.species)) return Estimate.unknown("model has no training data for " + o.species);
        if (o.form != null) return Estimate.unknown("model does not distinguish forms");
        if (o.isShiny() && shinyExamples < 5) return Estimate.unknown("model has too few shiny examples (" + shinyExamples + ")");
        double y = dot(beta, features(o.species, o.isShiny(), o.ivs, false));
        return new Estimate(Math.exp(y), Math.exp(y + residLo), Math.exp(y + residHi), metrics.n(), Confidence.MEDIUM,
                "ridge model (" + metrics.summary() + ")");
    }

    private int dim() { return 1 + species.size() + 1 + 2 + 1 + 1; }

    double[] features(String sp, boolean shiny, int[] ivs, boolean listing) {
        double[] x = new double[dim()];
        int k = 0;
        x[k++] = 1;
        int idx = species.indexOf(sp);
        if (idx >= 0) x[k + idx] = 1;
        k += species.size();
        x[k++] = shiny ? 1 : 0;
        if (ivs != null) {
            int perfect = 0, tot = 0;
            for (int v : ivs) { if (v == 31) perfect++; tot += v; }
            x[k++] = perfect / 6.0;
            x[k++] = tot / 186.0;
            x[k++] = 0;
        } else { k += 2; x[k++] = 1; } // IVs-missing indicator
        x[k] = listing ? 1 : 0;
        return x;
    }

    private double[] fit(List<PriceObservation> rows, double lambda) {
        int d = dim();
        double[][] a = new double[d][d];
        double[] b = new double[d];
        for (PriceObservation p : rows) {
            double[] x = features(p.species, Boolean.TRUE.equals(p.shiny), p.ivs, p.kind == PriceObservation.Kind.LISTING);
            double y = Math.log(p.price);
            for (int i = 0; i < d; i++) {
                if (x[i] == 0) continue;
                b[i] += x[i] * y;
                for (int j = 0; j < d; j++) a[i][j] += x[i] * x[j];
            }
        }
        for (int i = 1; i < d; i++) a[i][i] += lambda; // don't penalise the intercept
        a[0][0] += 1e-9;
        return solve(a, b);
    }

    /** Gaussian elimination with partial pivoting (matrix is symmetric positive definite after ridge). */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][];
        for (int i = 0; i < n; i++) m[i] = Arrays.copyOf(a[i], n + 1);
        for (int i = 0; i < n; i++) m[i][n] = b[i];
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            double[] t = m[c]; m[c] = m[piv]; m[piv] = t;
            if (Math.abs(m[c][c]) < 1e-12) continue;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                if (f == 0) continue;
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = Math.abs(m[i][i]) < 1e-12 ? 0 : m[i][n] / m[i][i];
        return x;
    }

    private static double dot(double[] b, double[] x) {
        double s = 0;
        for (int i = 0; i < x.length; i++) s += b[i] * x[i];
        return s;
    }

    private static String key(PriceObservation p) { return p.species + "|" + Boolean.TRUE.equals(p.shiny); }

    private static Map<String, Double> baseline(List<PriceObservation> tr) {
        Map<String, List<Double>> m = new HashMap<>();
        for (PriceObservation p : tr) m.computeIfAbsent(key(p), k -> new ArrayList<>()).add(Math.log(p.price));
        Map<String, Double> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, Stats.median(Stats.toArray(v))));
        return out;
    }

    private static double globalMedian(List<PriceObservation> tr) {
        double[] v = new double[tr.size()];
        for (int i = 0; i < v.length; i++) v[i] = Math.log(tr.get(i).price);
        return Stats.median(v);
    }
}

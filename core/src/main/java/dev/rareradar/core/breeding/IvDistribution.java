package dev.rareradar.core.breeding;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact offspring IV distribution as a mixture of scenarios. In each scenario every stat is either a fixed inherited
 * value or uniformly random 0..31. Enumerates power-item choice × inherited-stat subset × parent per stat
 * (at most 2 × 6 × 32 = 384 scenarios), so queries are exact rather than simulated.
 */
public final class IvDistribution {
    /** {@code fixed[i]} is the inherited IV or -1 for a uniformly random stat. */
    public record Scenario(double p, int[] fixed) {}

    private final List<Scenario> scenarios;

    private IvDistribution(List<Scenario> s) { this.scenarios = s; }

    public List<Scenario> scenarios() { return scenarios; }

    public static IvDistribution of(Parent a, Parent b, BreedingRules rules) {
        boolean knot = a.item() == HeldItem.DESTINY_KNOT || b.item() == HeldItem.DESTINY_KNOT;
        int n = knot ? rules.ivsInheritedDestinyKnot : rules.ivsInheritedDefault;
        n = Math.max(0, Math.min(6, n));
        List<Scenario> out = new ArrayList<>();
        // Power item branches: (stat, which parent) with probability.
        List<double[]> power = new ArrayList<>();
        boolean pa = a.item().isPower(), pb = b.item().isPower();
        if (pa && pb) { power.add(new double[]{a.item().stat, 0, 0.5}); power.add(new double[]{b.item().stat, 1, 0.5}); }
        else if (pa) power.add(new double[]{a.item().stat, 0, 1});
        else if (pb) power.add(new double[]{b.item().stat, 1, 1});
        else power.add(new double[]{-1, -1, 1});

        for (double[] pw : power) {
            int lockedStat = (int) pw[0];
            int lockedParent = (int) pw[1];
            int remainingPicks = lockedStat >= 0 ? n - 1 : n;
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < 6; i++) if (i != lockedStat) pool.add(i);
            List<int[]> subsets = combinations(pool, Math.max(0, remainingPicks));
            double pSubset = 1.0 / subsets.size();
            for (int[] subset : subsets) {
                int k = subset.length;
                for (int mask = 0; mask < (1 << k); mask++) {
                    int[] fixed = {-1, -1, -1, -1, -1, -1};
                    if (lockedStat >= 0) fixed[lockedStat] = (lockedParent == 0 ? a : b).ivs()[lockedStat];
                    for (int j = 0; j < k; j++) fixed[subset[j]] = (((mask >> j) & 1) == 0 ? a : b).ivs()[subset[j]];
                    out.add(new Scenario(pw[2] * pSubset / (1 << k), fixed));
                }
            }
        }
        return new IvDistribution(out);
    }

    private static List<int[]> combinations(List<Integer> pool, int k) {
        List<int[]> out = new ArrayList<>();
        rec(pool, k, 0, new int[k], 0, out);
        if (out.isEmpty()) out.add(new int[0]);
        return out;
    }

    private static void rec(List<Integer> pool, int k, int start, int[] cur, int depth, List<int[]> out) {
        if (depth == k) { out.add(cur.clone()); return; }
        for (int i = start; i < pool.size(); i++) { cur[depth] = pool.get(i); rec(pool, k, i + 1, cur, depth + 1, out); }
    }

    /** P(IV of stat i ≥ min[i] for every stat), min[i] ≤ 0 meaning "don't care". */
    public double probAtLeast(int[] min) {
        double total = 0;
        for (Scenario s : scenarios) {
            double p = s.p();
            for (int i = 0; i < 6 && p > 0; i++) {
                int m = min[i];
                if (m <= 0) continue;
                p *= s.fixed()[i] >= 0 ? (s.fixed()[i] >= m ? 1 : 0) : (32.0 - Math.min(32, m)) / 32.0;
            }
            total += p;
        }
        return total;
    }

    /** Distribution of the number of 31 IVs: result[k] = P(exactly k perfect IVs). */
    public double[] perfectCountDistribution() {
        double[] res = new double[7];
        for (Scenario s : scenarios) {
            double[] dp = new double[7];
            dp[0] = 1;
            for (int i = 0; i < 6; i++) {
                double q = s.fixed()[i] >= 0 ? (s.fixed()[i] == 31 ? 1 : 0) : 1 / 32.0;
                for (int c = 6; c >= 0; c--) dp[c] = dp[c] * (1 - q) + (c > 0 ? dp[c - 1] * q : 0);
            }
            for (int c = 0; c <= 6; c++) res[c] += s.p() * dp[c];
        }
        return res;
    }

    public double probPerfectAtLeast(int k) {
        double[] d = perfectCountDistribution();
        double p = 0;
        for (int c = Math.max(0, k); c <= 6; c++) p += d[c];
        return p;
    }

    /** Expected IV for each stat. */
    public double[] expectedIvs() {
        double[] e = new double[6];
        for (Scenario s : scenarios)
            for (int i = 0; i < 6; i++) e[i] += s.p() * (s.fixed()[i] >= 0 ? s.fixed()[i] : 15.5);
        return e;
    }
}

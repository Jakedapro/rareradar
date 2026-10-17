package dev.rareradar.core.breeding;

import java.util.*;

/** Compatibility, offspring probabilities and expected profit for breeding pairs. */
public final class BreedingAnalyzer {
    /** What the user is breeding for. Null nature = any. */
    public record Target(int[] minIvs, int minPerfect, String nature) {}

    /** Economics per egg. All values are user/market supplied; NaN = unknown. */
    public record Economics(double valueIfHit, double valueIfMiss, double costPerEgg, double minutesPerEgg, int pastures) {}

    public record Result(Parent a, Parent b, boolean compatible, String reason, double pHit, double pNature,
                         double[] perfectDist, double[] expectedIvs, double expectedProfitPerEgg, double expectedProfitPerHour,
                         List<String> assumptions) {}

    private final BreedingRules rules;

    public BreedingAnalyzer(BreedingRules rules) { this.rules = rules; }

    /** Standard compatibility: shared egg group + opposite genders, or Ditto with a non-Ditto; Undiscovered never breeds. */
    public static String incompatibility(Parent a, Parent b) {
        if (hasGroup(a, "undiscovered") || hasGroup(b, "undiscovered")) return "Undiscovered egg group cannot breed";
        if (a.isDitto() && b.isDitto()) return "two Ditto cannot breed";
        if (a.isDitto() || b.isDitto()) return null;
        if (a.eggGroups().isEmpty() || b.eggGroups().isEmpty()) return "egg groups unknown";
        boolean shared = false;
        for (String g : a.eggGroups()) for (String h : b.eggGroups()) if (g.equalsIgnoreCase(h)) shared = true;
        if (!shared) return "no shared egg group";
        if ("genderless".equalsIgnoreCase(a.gender()) || "genderless".equalsIgnoreCase(b.gender())) return "genderless Pokémon need Ditto";
        if (a.gender() == null || b.gender() == null) return "gender unknown";
        if (a.gender().equalsIgnoreCase(b.gender())) return "same gender";
        return null;
    }

    private static boolean hasGroup(Parent p, String g) {
        for (String x : p.eggGroups()) if (x.equalsIgnoreCase(g)) return true;
        return false;
    }

    public double natureProbability(Parent a, Parent b, String nature) {
        if (nature == null) return 1;
        if (rules.everstonePassesNature) {
            boolean ea = a.item() == HeldItem.EVERSTONE, eb = b.item() == HeldItem.EVERSTONE;
            if (ea && eb) return 0.5 * (eq(a.nature(), nature) + eq(b.nature(), nature));
            if (ea) return eq(a.nature(), nature);
            if (eb) return eq(b.nature(), nature);
        }
        return 1.0 / rules.natureCount;
    }

    private static double eq(String a, String b) { return a != null && a.equalsIgnoreCase(b) ? 1 : 0; }

    public Result analyze(Parent a, Parent b, Target t, Economics e) {
        List<String> assumptions = new ArrayList<>(List.of(rules.source,
                "Offspring IVs are independent of hidden abilities, shiny status and egg moves (not modelled)."));
        String inc = incompatibility(a, b);
        IvDistribution d = IvDistribution.of(a, b, rules);
        double pIv = d.probAtLeast(t.minIvs() == null ? new int[6] : t.minIvs());
        if (t.minPerfect() > 0) {
            // Joint probability: both the per-stat minimums and the perfect-count requirement, scenario by scenario.
            pIv = joint(d, t);
        }
        double pNat = natureProbability(a, b, t.nature());
        double pHit = pIv * pNat;
        double perEgg = Double.NaN, perHour = Double.NaN;
        if (e != null && Double.isFinite(e.valueIfHit()) && Double.isFinite(e.costPerEgg())) {
            double miss = Double.isFinite(e.valueIfMiss()) ? e.valueIfMiss() : 0;
            if (!Double.isFinite(e.valueIfMiss())) assumptions.add("Non-target eggs valued at 0 (no salvage value entered).");
            perEgg = pHit * e.valueIfHit() + (1 - pHit) * miss - e.costPerEgg();
            if (e.minutesPerEgg() > 0) perHour = perEgg * 60 / e.minutesPerEgg() * Math.max(1, e.pastures());
        }
        if (inc != null) { perEgg = Double.NaN; perHour = Double.NaN; }
        return new Result(a, b, inc == null, inc == null ? "compatible" : inc, pHit, pNat, d.perfectCountDistribution(),
                d.expectedIvs(), perEgg, perHour, assumptions);
    }

    private static double joint(IvDistribution d, Target t) {
        double total = 0;
        int[] min = t.minIvs() == null ? new int[6] : t.minIvs();
        for (IvDistribution.Scenario s : d.scenarios()) {
            double[] dp = new double[7];
            dp[0] = s.p();
            for (int i = 0; i < 6; i++) {
                int f = s.fixed()[i], m = Math.max(0, min[i]);
                double pPerfect, pOkNotPerfect;
                if (f >= 0) { pPerfect = f == 31 ? 1 : 0; pOkNotPerfect = f >= m && f != 31 ? 1 : 0; }
                else { pPerfect = 1 / 32.0; pOkNotPerfect = Math.max(0, 31 - m) / 32.0; }
                double[] nd = new double[7];
                for (int c = 0; c <= 6; c++) {
                    if (dp[c] == 0) continue;
                    nd[c] += dp[c] * pOkNotPerfect;
                    if (c < 6) nd[c + 1] += dp[c] * pPerfect;
                }
                dp = nd;
            }
            for (int c = t.minPerfect(); c <= 6; c++) total += dp[c];
        }
        return total;
    }

    /** Best compatible pairs among the user's Pokémon for a target, by expected profit per egg (or hit chance). */
    public List<Result> bestPairs(List<Parent> owned, Target t, Economics e, int limit) {
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < owned.size(); i++)
            for (int j = i + 1; j < owned.size(); j++) {
                Result r = analyze(owned.get(i), owned.get(j), t, e);
                if (r.compatible()) out.add(r);
            }
        out.sort(Comparator.comparingDouble((Result r) -> Double.isFinite(r.expectedProfitPerEgg()) ? r.expectedProfitPerEgg() : -1e18)
                .thenComparingDouble(Result::pHit).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }
}

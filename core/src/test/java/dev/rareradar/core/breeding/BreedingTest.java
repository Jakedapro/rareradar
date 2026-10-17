package dev.rareradar.core.breeding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BreedingTest {
    static final int[] P6 = {31, 31, 31, 31, 31, 31};
    static final int[] Z6 = {0, 0, 0, 0, 0, 0};
    static Parent p(String sp, int[] iv, String g, HeldItem item) { return new Parent(sp, iv, "adamant", g, List.of("monster"), item); }

    @Test void probabilitiesSumToOne() {
        IvDistribution d = IvDistribution.of(p("a", P6, "male", HeldItem.DESTINY_KNOT), p("b", Z6, "female", HeldItem.POWER_ANKLET), new BreedingRules());
        double s = 0;
        for (var sc : d.scenarios()) s += sc.p();
        assertEquals(1, s, 1e-12);
        double t = 0;
        for (double x : d.perfectCountDistribution()) t += x;
        assertEquals(1, t, 1e-12);
    }

    @Test void twoPerfectParentsWithKnot() {
        // 5 inherited (all 31), 1 random: P(6 perfect) = 1/32
        IvDistribution d = IvDistribution.of(p("a", P6, "male", HeldItem.DESTINY_KNOT), p("b", P6, "female", HeldItem.NONE), new BreedingRules());
        assertEquals(1 / 32.0, d.perfectCountDistribution()[6], 1e-12);
        assertEquals(1.0, d.probPerfectAtLeast(5), 1e-12);
    }

    @Test void withoutKnotThreeInherited() {
        IvDistribution d = IvDistribution.of(p("a", P6, "male", HeldItem.NONE), p("b", P6, "female", HeldItem.NONE), new BreedingRules());
        assertEquals(1.0, d.probPerfectAtLeast(3), 1e-12);
        assertEquals(Math.pow(1 / 32.0, 3), d.perfectCountDistribution()[6], 1e-12);
    }

    @Test void powerItemLocksStat() {
        int[] speOnly = {0, 0, 0, 0, 0, 31};
        IvDistribution d = IvDistribution.of(p("a", speOnly, "male", HeldItem.POWER_ANKLET), p("b", Z6, "female", HeldItem.NONE), new BreedingRules());
        assertEquals(1.0, d.probAtLeast(new int[]{0, 0, 0, 0, 0, 31}), 1e-12);
    }

    @Test void matchesMonteCarlo() {
        int[] a = {31, 20, 31, 5, 31, 0}, b = {0, 31, 10, 31, 31, 31};
        Parent pa = p("a", a, "male", HeldItem.DESTINY_KNOT), pb = p("b", b, "female", HeldItem.POWER_LENS);
        IvDistribution d = IvDistribution.of(pa, pb, new BreedingRules());
        double exact = d.probPerfectAtLeast(4);
        Random rnd = new Random(7);
        int hits = 0, N = 200_000;
        for (int it = 0; it < N; it++) {
            int[] child = new int[6];
            boolean[] done = new boolean[6];
            // power lens on b → SpA from b
            child[3] = b[3]; done[3] = true;
            int need = 4;
            while (need > 0) {
                int s = rnd.nextInt(6);
                if (done[s]) continue;
                child[s] = rnd.nextBoolean() ? a[s] : b[s]; done[s] = true; need--;
            }
            int perfect = 0;
            for (int i = 0; i < 6; i++) { if (!done[i]) child[i] = rnd.nextInt(32); if (child[i] == 31) perfect++; }
            if (perfect >= 4) hits++;
        }
        assertEquals(exact, hits / (double) N, 0.005);
    }

    @Test void compatibility() {
        assertNull(BreedingAnalyzer.incompatibility(p("a", Z6, "male", HeldItem.NONE), p("b", Z6, "female", HeldItem.NONE)));
        assertEquals("same gender", BreedingAnalyzer.incompatibility(p("a", Z6, "male", HeldItem.NONE), p("b", Z6, "male", HeldItem.NONE)));
        Parent ditto = new Parent("ditto", Z6, null, "genderless", List.of("ditto"), HeldItem.NONE);
        assertNull(BreedingAnalyzer.incompatibility(ditto, p("b", Z6, "male", HeldItem.NONE)));
        Parent legend = new Parent("mewtwo", Z6, null, "genderless", List.of("undiscovered"), HeldItem.NONE);
        assertNotNull(BreedingAnalyzer.incompatibility(ditto, legend));
        Parent other = new Parent("x", Z6, null, "female", List.of("fairy"), HeldItem.NONE);
        assertEquals("no shared egg group", BreedingAnalyzer.incompatibility(p("a", Z6, "male", HeldItem.NONE), other));
    }

    @Test void natureAndProfit() {
        BreedingAnalyzer an = new BreedingAnalyzer(new BreedingRules());
        Parent a = new Parent("a", P6, "jolly", "male", List.of("monster"), HeldItem.DESTINY_KNOT);
        Parent b = new Parent("b", P6, "adamant", "female", List.of("monster"), HeldItem.EVERSTONE);
        var r = an.analyze(a, b, new BreedingAnalyzer.Target(null, 5, "adamant"),
                new BreedingAnalyzer.Economics(10_000, 1_000, 500, 10, 4));
        assertEquals(1.0, r.pNature(), 1e-12);
        assertEquals(1.0, r.pHit(), 1e-12);
        assertEquals(10_000 - 500, r.expectedProfitPerEgg(), 1e-9);
        assertEquals(9_500 * 6 * 4, r.expectedProfitPerHour(), 1e-6);
        var r2 = an.analyze(a, new Parent("b", P6, "adamant", "female", List.of("monster"), HeldItem.NONE),
                new BreedingAnalyzer.Target(null, 0, "adamant"), null);
        assertEquals(1 / 25.0, r2.pNature(), 1e-12);
        assertTrue(Double.isNaN(r2.expectedProfitPerEgg()));
    }

    @Test void jointTargetConsistentWithMarginals() {
        BreedingAnalyzer an = new BreedingAnalyzer(new BreedingRules());
        Parent a = p("a", new int[]{31, 31, 31, 0, 0, 0}, "male", HeldItem.DESTINY_KNOT);
        Parent b = p("b", new int[]{0, 0, 0, 31, 31, 31}, "female", HeldItem.NONE);
        IvDistribution d = IvDistribution.of(a, b, new BreedingRules());
        var r = an.analyze(a, b, new BreedingAnalyzer.Target(new int[6], 4, null), null);
        assertEquals(d.probPerfectAtLeast(4), r.pHit(), 1e-12);
        var bp = an.bestPairs(List.of(a, b, p("c", P6, "female", HeldItem.NONE)), new BreedingAnalyzer.Target(new int[6], 5, null), null, 5);
        assertEquals("c", bp.get(0).b().species());
    }
}

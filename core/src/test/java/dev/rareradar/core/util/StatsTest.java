package dev.rareradar.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {
    @Test void medianAndQuantiles() {
        assertEquals(3, Stats.median(new double[]{5, 1, 3}));
        assertEquals(2.5, Stats.median(new double[]{1, 2, 3, 4}));
        assertEquals(1, Stats.quantile(new double[]{1, 2, 3}, 0));
        assertTrue(Double.isNaN(Stats.median(new double[0])));
    }

    @Test void madIgnoresSingleOutlier() {
        double[] xs = {100, 102, 98, 101, 99, 1_000_000};
        assertTrue(Stats.mad(xs) < 5, "a single absurd listing must not inflate volatility");
    }

    @Test void theilSenRecoversSlope() {
        double[] x = {0, 1, 2, 3, 4, 5};
        double[] y = {10, 12, 14, 16, 18, 500}; // outlier at the end
        assertEquals(2.0, Stats.theilSen(x, y), 1e-9);
    }

    @Test void normalQuantile() {
        assertEquals(1.959964, Stats.zFor(0.95), 1e-4);
        assertEquals(0, Stats.normalInv(0.5), 1e-9);
    }

    @Test void wilsonContainsTruthAndIsBounded() {
        double[] ci = Stats.wilson(0, 10, 0.95);
        assertEquals(0, ci[0], 1e-12);
        assertTrue(ci[1] > 0.2 && ci[1] < 0.35); // known value ≈ 0.278
        ci = Stats.wilson(50, 100, 0.95);
        assertTrue(ci[0] < 0.5 && ci[1] > 0.5);
    }

    @Test void poissonIntervalMatchesExactBounds() {
        // Exact 95% Garwood bounds for k=10: [4.795, 18.39]
        double[] ci = Stats.poissonRate(10, 1, 0.95);
        assertEquals(4.795, ci[0], 0.05);
        assertEquals(18.39, ci[1], 0.1);
        // k=0 → upper ≈ 3.689
        assertEquals(3.689, Stats.poissonRate(0, 1, 0.95)[1], 0.05);
        assertEquals(0, Stats.poissonRate(0, 1, 0.95)[0]);
    }

    @Test void confidenceIsConservative() {
        assertEquals(Confidence.NONE, Confidence.of(0, 0));
        assertEquals(Confidence.LOW, Confidence.of(3, 0.01));
        assertEquals(Confidence.MEDIUM, Confidence.of(10, 0.2));
        assertEquals(Confidence.HIGH, Confidence.of(50, 0.2));
    }

    @Test void moneyNeverRendersUnknownAsZero() {
        assertEquals("?", Money.fmt(Double.NaN));
        assertEquals("$1.50M", Money.fmt(1_500_000));
        assertEquals("-$500", Money.fmt(-500));
        assertEquals("unknown", Money.range(Estimate.unknown("x")));
    }
}

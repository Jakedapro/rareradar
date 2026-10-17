package dev.rareradar.core.hunting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HuntingValueEstimatorTest {
    @Test void shinyProbabilityBlendsDocumentedRateWithObservations() {
        double[] none = HuntingValueEstimator.shinyProbability(0, 0, 8192, 8192);
        assertEquals(1 / 8192.0, none[0], 1e-9);
        double[] lucky = HuntingValueEstimator.shinyProbability(3, 2000, 8192, 8192);
        assertTrue(lucky[0] > 1 / 8192.0 && lucky[0] < 3 / 2000.0, "pulled towards observations but not all the way");
        assertTrue(lucky[1] <= lucky[0] && lucky[0] <= lucky[2]);
    }

    @Test void expectedValueUsesOnlyKnownValues() {
        var counts = List.of(new HuntingValueEstimator.SpeciesCount("gible", 20), new HuntingValueEstimator.SpeciesCount("pidgey", 80));
        Map<String, Double> v = Map.of("gible", 10_000.0);
        var r = HuntingValueEstimator.estimate(counts, 2, 1, s -> v.getOrDefault(s, Double.NaN), Double.NaN);
        assertEquals(20 / 2.0 * 10_000, r.perHour().value(), 1e-6);
        assertEquals(0.2, r.coverage(), 1e-9);
        assertTrue(r.assumptions().stream().anyMatch(a -> a.contains("capture success not measured")));
        assertTrue(r.perHour().low() < r.perHour().value());
        var shiny = HuntingValueEstimator.estimate(counts, 2, 1 / 8192.0, s -> v.getOrDefault(s, Double.NaN), 0.5);
        assertEquals(10 * 10_000 / 8192.0 * 0.5, shiny.perHour().value(), 1e-6);
    }

    @Test void unknownWithoutValuesOrTime() {
        var counts = List.of(new HuntingValueEstimator.SpeciesCount("pidgey", 10));
        assertFalse(HuntingValueEstimator.estimate(counts, 1, 1, s -> Double.NaN, 1).perHour().known());
        assertFalse(HuntingValueEstimator.estimate(counts, 0, 1, s -> 5.0, 1).perHour().known());
    }
}

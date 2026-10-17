package dev.rareradar.core.pokebuilder;

import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenPlannerTest {
    static Estimate v(double x) { return new Estimate(x, x * 0.8, x * 1.2, 10, Confidence.MEDIUM, "t"); }
    static UpgradeOption o(String id, String label, double cost, double before, double after) {
        return new UpgradeOption(id, label, cost, v(before), v(after));
    }

    @Test void knapsackBeatsGreedyRatio() {
        // Budget 10: A(6 tokens, +60), B(5,+45), C(5,+45) → best is B+C = 90, greedy-by-ratio would pick A (ratio 10).
        var plan = TokenPlanner.plan(List.of(o("a", "A", 6, 0, 60), o("b", "B", 5, 0, 45), o("c", "C", 5, 0, 45)), 10, Double.NaN);
        assertEquals(90, plan.expectedGain(), 1e-9);
        assertEquals(10, plan.tokensUsed(), 1e-9);
    }

    @Test void oneOptionPerPokemon() {
        var plan = TokenPlanner.plan(List.of(o("a", "IVs", 3, 0, 30), o("a", "IVs+nature", 5, 0, 70), o("b", "B", 5, 0, 20)), 10, Double.NaN);
        assertEquals(2, plan.chosen().size());
        assertEquals("IVs+nature", plan.chosen().get(0).label());
    }

    @Test void rejectsUnknownAndUneconomical() {
        var unknown = new UpgradeOption("x", "X", 1, Estimate.unknown("no data"), v(100));
        var plan = TokenPlanner.plan(List.of(unknown, o("y", "Y", 10, 100, 105)), 100, 1.0);
        assertTrue(plan.chosen().isEmpty());
        assertEquals(2, plan.rejected().size());
        assertTrue(plan.rejected().get(1).contains("does not justify"));
    }

    @Test void fractionalCosts() {
        var plan = TokenPlanner.plan(List.of(o("a", "A", 2.5, 0, 10), o("b", "B", 2.5, 0, 10)), 5, Double.NaN);
        assertEquals(2, plan.chosen().size());
    }

    @Test void ivCostHelper() {
        PokeBuilderCosts c = new PokeBuilderCosts();
        assertTrue(Double.isNaN(c.ivUpgradeCost(new int[]{0, 0, 0, 0, 0, 0}, 31)));
        c.costPerIv = 2;
        assertEquals((31 - 20 + 31 - 30) * 2, c.ivUpgradeCost(new int[]{20, 30, 31, 31, 31, 31}, 31), 1e-9);
    }
}

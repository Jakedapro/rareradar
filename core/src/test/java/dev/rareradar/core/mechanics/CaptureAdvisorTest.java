package dev.rareradar.core.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptureAdvisorTest {
    @Test void rowsAreOrderedAndConsistentWithCaptureMath() {
        var rows = CaptureAdvisor.advise(45, 45, 20);
        assertEquals(9, rows.size());
        for (var r : rows) {
            assertTrue(r.probability() > 0 && r.probability() <= 1);
            assertEquals(1 / r.probability(), r.expectedBalls(), 1e-9);
        }
        // Overworld, full HP, no status, Poké Ball: the reviewer's hand calculation (m = 7.5 → ≈ 0.071).
        var thrown = rows.stream().filter(r -> r.scenario() == CaptureAdvisor.Scenario.THROWN_FULL_HP && r.ball().equals("poke_ball")).findFirst().orElseThrow();
        assertEquals(0.071, thrown.probability(), 0.002);
        // In battle the 0.5 overworld modifier is gone, so the chance must be higher.
        var battlePoke = rows.stream().filter(r -> r.scenario() == CaptureAdvisor.Scenario.BATTLE_FULL_HP && r.ball().equals("poke_ball")).findFirst().orElseThrow();
        assertTrue(thrown.probability() < battlePoke.probability());
        // Better balls and weakened/asleep targets only help.
        var ultraAsleep = rows.stream().filter(r -> r.scenario() == CaptureAdvisor.Scenario.BATTLE_LOW_HP_ASLEEP && r.ball().equals("ultra_ball")).findFirst().orElseThrow();
        assertTrue(ultraAsleep.probability() > battlePoke.probability());
    }

    @Test void easyCatchesAreCertainAndInvalidInputGivesNothing() {
        var rows = CaptureAdvisor.advise(255, 40, 2);
        assertEquals(1.0, CaptureAdvisor.cheapestWithin(rows, CaptureAdvisor.Scenario.BATTLE_LOW_HP_ASLEEP, 1.0).probability(), 1e-12);
        assertTrue(CaptureAdvisor.advise(0, 40, 5).isEmpty());
        assertNull(CaptureAdvisor.cheapestWithin(CaptureAdvisor.advise(3, 80, 70), CaptureAdvisor.Scenario.THROWN_FULL_HP, 2));
    }

    @Test void ownedBallsRankedAndConditionalOnesNotGuessed() {
        var ctx = BallContext.builder().inBattle(false).level(20).types(java.util.Set.of("bug")).build();
        var rows = CaptureAdvisor.rankOwned(45, 45, 20, ctx, java.util.List.of("poke_ball", "net_ball", "ultra_ball", "master_ball", "mystery_ball"));
        assertEquals("master_ball", rows.get(0).ball());
        assertTrue(rows.get(0).guaranteed());
        // Net Ball ×3 on a Bug type beats Ultra ×2.
        assertEquals("net_ball", rows.get(1).ball());
        assertEquals("ultra_ball", rows.get(2).ball());
        assertEquals("poke_ball", rows.get(3).ball());
        assertTrue(Double.isNaN(rows.get(4).probability()));
        // Without types the Net Ball's condition is unknown, so it is not given a bonus.
        var noTypes = CaptureAdvisor.rankOwned(45, 45, 20, BallContext.builder().inBattle(false).build(), java.util.List.of("net_ball"));
        assertFalse(noTypes.get(0).missing().isEmpty());
    }
}

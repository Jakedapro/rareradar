package dev.rareradar.core.mechanics;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Worked examples recomputed by hand from CobblemonCaptureCalculator.kt (Cobblemon 1.8.2). Line numbers refer to that
 * file. ps = S/65537, pc = c/256, P = pc*ps + (1-pc)*ps^4.
 */
class CaptureMathTest {

    private static CaptureInputs.Builder base() {
        return CaptureInputs.builder().catchRate(45).hp(100, 100).level(20).status(PersistentStatus.NONE)
                .inBattle(false).ball(BallEffect.plain()).caughtCount(0);
    }

    // ---------------------------------------------------------------- worked examples

    @Test void workedExampleFullHpOutOfBattlePokeBall() {
        // catch rate 45, 100/100 HP, out of battle, Poké Ball (x1, valid), no status, level 20, 0 species owned.
        // numerator = (3*100 - 2*100) * darkGrass 1 * 45 * inBattle 0.5 = 100 * 45 * 0.5 = 2250      (line 77)
        // m = 2250 * 1 / (3*100) = 7.5; * bonusStatus 1 * bonusLevel 1 = 7.5                       (lines 77-78)
        // S = round(65536 / (255/7.5)^0.1875) = round(65536 / 34^0.1875) = round(65536 / 1.937103) = round(33831.997) = 33832
        // critical: caughtCount 0 <= 30 -> multiplier 0 -> c = round(0/6) = 0 -> never critical
        // P = (33832/65537)^4 = 0.5162274745^4 = 0.0710173494; E = 1/P = 14.0810662
        CaptureResult r = CaptureMath.evaluate(base().build());
        assertTrue(r.known());
        assertEquals(7.5F, r.modifiedCatchRate());
        assertEquals(33832, r.shakeThreshold());
        assertEquals(0, r.criticalThreshold());
        double ps = 33832.0 / 65537.0;
        assertEquals(ps * ps * ps * ps, r.captureProbability(), 1e-15);
        assertEquals(0.0710173494, r.captureProbability(), 1e-9);
        assertEquals(14.0810662, r.expectedBalls(), 1e-6);
    }

    @Test void workedExampleOneHpSleepingLowLevelInBattleUltraBallIsCertain() {
        // catch rate 45, 1/100 HP, in battle (x1), Ultra Ball x2, asleep (x2.5), level 5 (bonusLevel (36-10)/10 = 2).
        // numerator = (300 - 2) * 45 * 1 = 13410; m = 13410 * 2 / 300 = 89.4; m *= 2.5 * 2 -> 447.0
        // S = round(65536 / (255/447)^0.1875) = round(65536 / 0.570470^0.1875) = round(65536 / 0.900103) = 72809
        // S >= 65537 so every nextInt(65537) < S: ps = 1 and P = 1 whatever the critical roll.
        CaptureResult r = CaptureMath.evaluate(base().hp(1, 100).inBattle(true).ball(BallEffect.multiplier(2F, true))
                .status(PersistentStatus.SLEEP).level(5).caughtCount(null).build());
        assertTrue(r.known(), "caughtCount is irrelevant when ps = 1");
        assertEquals(447.0F, r.modifiedCatchRate(), 1e-3F);
        assertEquals(72809, r.shakeThreshold());
        assertEquals(1.0, r.captureProbability());
        assertEquals(1.0, r.expectedBalls());
    }

    @Test void workedExampleCriticalCapture() {
        // catch rate 3, 300/300 HP, in battle, Ultra Ball x2, no status, level 70, 400 species owned.
        // numerator = (900 - 600) * 3 * 1 = 900; m = 900 * 2 / 900 = 2.0
        // critical: 400 owned -> multiplier 1.5 (<= 450); b = 2.0 * 1.5 = 3.0; c = round(3.0 / 6) = round(0.5) = 1
        //   (Math.round rounds .5 up) -> pc = 1/256
        // S = round(65536 / (255/2)^0.1875) = round(65536 / 127.5^0.1875) = round(65536 / 2.481852) = round(26405.64) = 26406
        // P = (1/256) * ps + (255/256) * ps^4 with ps = 26406/65537 = 0.4029174 -> P = 0.0278260211
        CaptureResult r = CaptureMath.evaluate(base().catchRate(3).hp(300, 300).inBattle(true)
                .ball(BallEffect.multiplier(2F, true)).level(70).caughtCount(400).build());
        assertEquals(2.0F, r.modifiedCatchRate());
        assertEquals(1, r.criticalThreshold());
        assertEquals(26406, r.shakeThreshold());
        double ps = 26406.0 / 65537.0;
        double expected = ps / 256 + (255.0 / 256) * Math.pow(ps, 4);
        assertEquals(expected, r.captureProbability(), 1e-15);
        assertEquals(0.0278260211, r.captureProbability(), 1e-9);
        assertEquals(1 / expected, r.expectedBalls(), 1e-9);
    }

    @Test void modifiedCatchRateOf255IsNotCertain() {
        // catch rate 255, 1/1 HP, in battle, ball x3: numerator (3-2)*255*1 = 255; m = 255*3/3 = 255.0
        // S = round(65536 / 1^0.1875) = 65536; nextInt(65537) can return 65536, so ps = 65536/65537 < 1.
        CaptureResult r = CaptureMath.evaluate(base().catchRate(255).hp(1, 1).inBattle(true)
                .ball(BallEffect.multiplier(3F, true)).build());
        assertEquals(255.0F, r.modifiedCatchRate());
        assertEquals(65536, r.shakeThreshold());
        assertEquals(Math.pow(65536.0 / 65537.0, 4), r.captureProbability(), 1e-15);
        assertTrue(r.captureProbability() < 1);
    }

    @Test void zeroCatchRateNeverCaptures() {
        // m = 0 -> 255/0 = +Inf -> Inf^0.1875 = Inf -> 65536/Inf = 0 -> S = 0 -> ps = 0
        CaptureResult r = CaptureMath.evaluate(base().catchRate(0).build());
        assertEquals(0, r.shakeThreshold());
        assertEquals(0.0, r.captureProbability());
        assertEquals(Double.POSITIVE_INFINITY, r.expectedBalls());
    }

    // ---------------------------------------------------------------- individual terms

    @Test void levelBonusUsesIntegerDivision() {
        // max((36 - 2L) / 10, 1) for L < 13 (line 70): L1 34/10=3, L3 30/10=3, L4 28/10=2, L8 20/10=2, L9 18/10=1
        int[] expected = {0, 3, 3, 3, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1};
        for (int level = 1; level <= 14; level++) assertEquals(expected[level], CaptureMath.levelBonus(level), "level " + level);
        assertEquals(1, CaptureMath.levelBonus(100));
    }

    @Test void statusBonuses() {
        // full-HP Poké Ball baseline m = 7.5 (see worked example 1)
        assertEquals(7.5F * 2.5F, CaptureMath.evaluate(base().status(PersistentStatus.SLEEP).build()).modifiedCatchRate());
        assertEquals(7.5F * 2.5F, CaptureMath.evaluate(base().status(PersistentStatus.FROZEN).build()).modifiedCatchRate());
        for (PersistentStatus s : List.of(PersistentStatus.PARALYSIS, PersistentStatus.BURN, PersistentStatus.POISON, PersistentStatus.POISON_BADLY)) {
            assertEquals(7.5F * 1.5F, CaptureMath.evaluate(base().status(s).build()).modifiedCatchRate(), s.name());
        }
    }

    @Test void inBattleDoublesTheOutOfBattleRate() {
        // inBattleModifier 1 vs 0.5 (line 62): 7.5 -> 15.0; S = round(65536 / 17^0.1875) = round(38527.49) = 38527
        CaptureResult r = CaptureMath.evaluate(base().inBattle(true).build());
        assertEquals(15.0F, r.modifiedCatchRate());
        assertEquals(38527, r.shakeThreshold());
    }

    @Test void invalidBallConditionMeansBonusOne() {
        // isValid false -> ballBonus = 1F (line 73), whatever the value
        CaptureResult invalid = CaptureMath.evaluate(base().ball(BallEffect.multiplier(3F, false)).build());
        assertEquals(7.5F, invalid.modifiedCatchRate());
        CaptureResult valid = CaptureMath.evaluate(base().ball(BallEffect.multiplier(3F, true)).build());
        assertEquals(22.5F, valid.modifiedCatchRate());
        assertEquals(41571, valid.shakeThreshold()); // round(65536 / (255/22.5)^0.1875) = round(41570.75)
        // Beast Ball on a non-Ultra Beast is valid with value 0.1: 7.5 * 0.1
        assertEquals(7.5F * 0.1F, CaptureMath.evaluate(base().ball(BallEffect.multiplier(0.1F, true)).build()).modifiedCatchRate(), 1e-6F);
    }

    @Test void criticalMultiplierTiers() {
        assertEquals(0F, CaptureMath.criticalCaughtMultiplier(0));
        assertEquals(0F, CaptureMath.criticalCaughtMultiplier(30));
        assertEquals(0.5F, CaptureMath.criticalCaughtMultiplier(31));
        assertEquals(0.5F, CaptureMath.criticalCaughtMultiplier(150));
        assertEquals(1F, CaptureMath.criticalCaughtMultiplier(151));
        assertEquals(1F, CaptureMath.criticalCaughtMultiplier(300));
        assertEquals(1.5F, CaptureMath.criticalCaughtMultiplier(450));
        assertEquals(2F, CaptureMath.criticalCaughtMultiplier(600));
        assertEquals(2.5F, CaptureMath.criticalCaughtMultiplier(601));
        // m = 2, 31 owned: b = 1, 1/6 = 0.167 -> c = 0
        assertEquals(0, CaptureMath.criticalThreshold(2F, 31));
        // m = 255, 601 owned: b = 637.5, /6 = 106.25 -> c = 106
        assertEquals(106, CaptureMath.criticalThreshold(255F, 601));
    }

    @Test void levelDifferencePenaltyUsesIntegerDivision() {
        // 1F - ((level - ref) / (100 / 2)) with Int division, clamped to [0.1, 1] (line 83)
        assertEquals(1F, CaptureMath.levelDifferenceFactor(60, null, 100));
        assertEquals(1F, CaptureMath.levelDifferenceFactor(60, 60, 100));
        assertEquals(1F, CaptureMath.levelDifferenceFactor(60, 11, 100)); // gap 49 -> 49/50 = 0
        assertEquals(0.1F, CaptureMath.levelDifferenceFactor(60, 10, 100)); // gap 50 -> 1 -> 1-1 = 0 -> max(0.1, 0)
        CaptureResult r = CaptureMath.evaluate(base().level(60).penaltyReferenceLevel(10).build());
        assertEquals(0.75F, r.modifiedCatchRate(), 1e-6F);
    }

    @Test void guaranteedBallNeedsNoOtherInput() {
        CaptureResult r = CaptureMath.evaluate(CaptureInputs.builder().ball(BallEffect.guaranteedCapture()).build());
        assertTrue(r.known());
        assertTrue(r.guaranteed());
        assertEquals(1.0, r.captureProbability());
        assertEquals(1.0, r.expectedBalls());
        BallEffect master = BallCatalog.get("cobblemon:master_ball").orElseThrow().effect(BallContext.builder().build());
        assertTrue(CaptureMath.evaluate(CaptureInputs.builder().ball(master).build()).guaranteed());
    }

    // ---------------------------------------------------------------- unknown inputs

    @Test void unknownInputsAreListedNotDefaulted() {
        CaptureResult r = CaptureMath.evaluate(CaptureInputs.builder().catchRate(45).level(20).build());
        assertFalse(r.known());
        assertTrue(Double.isNaN(r.captureProbability()));
        assertEquals(List.of("maxHp", "currentHp", "status", "inBattle", "ball.conditionApplies", "ball.value"), r.missing());

        CaptureResult hpOnly = CaptureMath.evaluate(base().hp(null, 100).build());
        assertEquals(List.of("currentHp"), hpOnly.missing());

        CaptureResult ballValueIrrelevant = CaptureMath.evaluate(base().ball(new BallEffect(false, null, false)).build());
        assertTrue(ballValueIrrelevant.known(), "value is not needed when the condition is known not to apply");
    }

    @Test void caughtCountRequiredOnlyWhenItMatters() {
        CaptureResult r = CaptureMath.evaluate(base().caughtCount(null).build());
        assertFalse(r.known());
        assertEquals(List.of("caughtCount"), r.missing());
        assertTrue(CaptureMath.evaluate(base().catchRate(0).caughtCount(null).build()).known()); // ps = 0
    }

    @Test void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.evaluate(base().hp(101, 100).build()));
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.evaluate(base().hp(0, 0).build()));
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.shakeThreshold(-1F));
    }

    // ---------------------------------------------------------------- expected balls

    @Test void outOfBattleSleepOnlyHelpsTheFirstThrow() {
        // EmptyPokeBallEntity.breakFree clears SLEEP after a failed out-of-battle capture.
        // asleep: m = 7.5 * 2.5 = 18.75, S = round(65536 / 13.6^0.1875) ; awake p0 = worked example 1
        CaptureResult asleep = CaptureMath.evaluate(base().status(PersistentStatus.SLEEP).build());
        double p1 = asleep.captureProbability();
        double p0 = CaptureMath.evaluate(base().build()).captureProbability();
        assertEquals(1 + (1 - p1) / p0, asleep.expectedBalls(), 1e-12);
        assertTrue(asleep.expectedBalls() > 1 / p1);
        // in battle the status is not cleared by a failed throw: plain geometric
        CaptureResult inBattle = CaptureMath.evaluate(base().status(PersistentStatus.SLEEP).inBattle(true).build());
        assertEquals(1 / inBattle.captureProbability(), inBattle.expectedBalls(), 1e-12);
    }

    @Test void probabilityWithinN() {
        assertEquals(0.75, CaptureMath.probabilityWithin(0.5, 2), 1e-15);
        assertEquals(0, CaptureMath.probabilityWithin(0.5, 0));
    }

    // ---------------------------------------------------------------- properties

    @Test void probabilityIsWithinUnitIntervalAndMonotoneInHp() {
        Random rnd = new Random(1234);
        PersistentStatus[] statuses = PersistentStatus.values();
        for (int trial = 0; trial < 400; trial++) {
            int maxHp = 1 + rnd.nextInt(400);
            CaptureInputs.Builder b = CaptureInputs.builder().catchRate(rnd.nextInt(256)).level(1 + rnd.nextInt(100))
                    .status(statuses[rnd.nextInt(statuses.length)]).inBattle(rnd.nextBoolean())
                    .ball(BallEffect.multiplier(new float[]{0.1F, 1F, 1.5F, 2F, 3.5F, 5F}[rnd.nextInt(6)], rnd.nextBoolean()))
                    .caughtCount(rnd.nextInt(800));
            double previous = Double.POSITIVE_INFINITY;
            float previousM = Float.POSITIVE_INFINITY;
            for (int cur = 1; cur <= maxHp; cur++) {
                CaptureResult r = CaptureMath.evaluate(b.hp(cur, maxHp).build());
                assertTrue(r.captureProbability() >= 0 && r.captureProbability() <= 1, r.toString());
                assertTrue(r.modifiedCatchRate() <= previousM, "m must not increase with current HP");
                assertTrue(r.captureProbability() <= previous, "P must not increase with current HP");
                assertTrue(r.expectedBalls() >= 1);
                previous = r.captureProbability();
                previousM = r.modifiedCatchRate();
            }
        }
    }

    @Test void probabilityIsMonotoneInCaughtCount() {
        double previous = -1;
        for (int caught = 0; caught <= 700; caught += 10) {
            // in battle, full HP, catch rate 200: m = 200/3 = 66.67, so c reaches round(66.67 * 2.5 / 6) = 28
            double p = CaptureMath.evaluate(base().catchRate(200).inBattle(true).caughtCount(caught).build()).captureProbability();
            assertTrue(p >= previous);
            previous = p;
        }
    }

    // ---------------------------------------------------------------- bounds

    @Test void boundsMatchExhaustiveEnumeration() {
        // Small ranges, brute-forced with evaluate(); bounds() must equal the exact min and max.
        int catchRate = 45;
        CaptureRanges ranges = CaptureRanges.builder().catchRate(catchRate).maxHp(38, 42).currentHp(1, 42).level(5, 14)
                .statuses(EnumSet.allOf(PersistentStatus.class)).inBattle(Set.of(true, false)).ballBonus(1F, 1.5F)
                .caughtCount(0, 700).build();
        CaptureBounds bounds = CaptureMath.bounds(ranges);
        assertTrue(bounds.known());
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int maxHp = 38; maxHp <= 42; maxHp++)
            for (int cur = 1; cur <= maxHp; cur++)
                for (int level = 5; level <= 14; level++)
                    for (PersistentStatus st : PersistentStatus.values())
                        for (boolean ib : new boolean[]{true, false})
                            for (float ball : new float[]{1F, 1.5F})
                                for (int caught : new int[]{0, 31, 151, 301, 451, 601, 700}) {
                                    double p = CaptureMath.evaluate(CaptureInputs.builder().catchRate(catchRate).hp(cur, maxHp)
                                            .level(level).status(st).inBattle(ib).ball(BallEffect.multiplier(ball, true))
                                            .caughtCount(caught).build()).captureProbability();
                                    min = Math.min(min, p);
                                    max = Math.max(max, p);
                                }
        assertEquals(min, bounds.minProbability(), 0.0);
        assertEquals(max, bounds.maxProbability(), 0.0);
        assertTrue(bounds.minExpectedBalls() <= bounds.maxExpectedBalls());
    }

    @Test void boundsForFullHpWildUseStatRange() {
        // A wild level-20 Pokémon with base HP 50: IV 0..31, EV 0 -> max HP 50..56 (see StatMathTest), assumed at full HP.
        int[] hp = StatMath.hpRange(50, 20, 0, 31, 0, 0);
        CaptureBounds b = CaptureMath.bounds(CaptureRanges.builder().catchRate(45).maxHp(hp[0], hp[1]).hpFraction(1, 1)
                .level(20, 20).statuses(EnumSet.of(PersistentStatus.NONE)).inBattle(Set.of(false)).caughtCount(0, 0).build());
        // At full HP m = maxHp * 45 * 0.5 / (3 * maxHp) = 7.5 for every max HP -> min = max = worked example 1
        assertEquals(0.0710173494, b.minProbability(), 1e-9);
        assertEquals(b.minProbability(), b.maxProbability(), 0.0);
    }

    @Test void boundsUnknownWithoutCatchRateAndCertainWithMasterBall() {
        CaptureBounds unknown = CaptureMath.bounds(CaptureRanges.builder().maxHp(10, 20).level(5, 5).build());
        assertFalse(unknown.known());
        assertEquals(List.of("catchRate"), unknown.missing());
        CaptureBounds master = CaptureMath.bounds(CaptureRanges.builder().ballGuaranteed(true).build());
        assertEquals(1, master.minProbability());
        assertEquals(1, master.maxProbability());
    }

    @Test void boundsContainPointEstimates() {
        Random rnd = new Random(99);
        CaptureRanges ranges = CaptureRanges.builder().catchRate(120).maxHp(60, 75).hpFraction(0.2, 0.6).level(30, 35)
                .statuses(EnumSet.of(PersistentStatus.NONE, PersistentStatus.PARALYSIS)).inBattle(Set.of(true))
                .ballMaybeApplies(3F).caughtCount(100, 500).build();
        CaptureBounds b = CaptureMath.bounds(ranges);
        for (int i = 0; i < 2000; i++) {
            int maxHp = 60 + rnd.nextInt(16);
            int lo = Math.max(1, (int) Math.floor(0.2 * maxHp)), hi = (int) Math.ceil(0.6 * maxHp);
            int cur = lo + rnd.nextInt(hi - lo + 1);
            double p = CaptureMath.evaluate(CaptureInputs.builder().catchRate(120).hp(cur, maxHp).level(30 + rnd.nextInt(6))
                    .status(rnd.nextBoolean() ? PersistentStatus.NONE : PersistentStatus.PARALYSIS).inBattle(true)
                    .ball(BallEffect.multiplier(3F, rnd.nextBoolean())).caughtCount(100 + rnd.nextInt(401)).build())
                    .captureProbability();
            assertTrue(p >= b.minProbability() && p <= b.maxProbability());
        }
    }

    /** Independent hand check (lead developer review against CobblemonCaptureCalculator.kt lines 61-98). */
    @org.junit.jupiter.api.Test void reviewerHandCalculation() {
        // catchRate 45, full HP 50/50, out of battle (0.5), Poke Ball x1, level 20 (bonusLevel 1), no status:
        // m = ((3*50 - 2*50) * 1 * 45 * 0.5 * 1) / (3*50) = 1125 / 150 = 7.5
        float m = CaptureMath.modifiedCatchRate(45F, 50, 50, 20, PersistentStatus.NONE, false, 1F, null, 100);
        org.junit.jupiter.api.Assertions.assertEquals(7.5F, m, 1e-6F);
        // S = round(65536 / (255/7.5)^0.1875) = round(65536 / 34^0.1875)
        int s = CaptureMath.shakeThreshold(m);
        org.junit.jupiter.api.Assertions.assertEquals(Math.round(65536F / (float) Math.pow(34F, 0.1875F)), s);
        // caughtCount <= 30 → caughtMultiplier 0 → no critical: P = (S/65537)^4 ≈ 0.071
        double p = CaptureMath.captureProbability(m, 0);
        org.junit.jupiter.api.Assertions.assertEquals(Math.pow(s / 65537.0, 4), p, 1e-12);
        org.junit.jupiter.api.Assertions.assertEquals(0.071, p, 0.002);
    }
}

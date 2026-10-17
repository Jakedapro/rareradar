package dev.rareradar.core.mechanics;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of Cobblemon 1.8.2's default capture calculator.
 *
 * <p>Source of truth: Cobblemon tag 1.8.2 (commit 643bf9068b51c829316429916373e5aa9864b8b9),
 * {@code common/src/main/kotlin/com/cobblemon/mod/common/pokeball/catching/calculators/CobblemonCaptureCalculator.kt}
 * (the default: {@code var captureCalculator: CaptureCalculator = CobblemonCaptureCalculator}, CobblemonConfig.kt:240-241),
 * together with {@code api/pokeball/catching/calculators/CriticalCaptureProvider.kt}. Line numbers in comments refer
 * to CobblemonCaptureCalculator.kt unless stated otherwise. See docs/MECHANICS.md.
 *
 * <p>All intermediate values are computed in {@code float} in the same order as the Kotlin source (Java and Kotlin/JVM
 * use the same IEEE-754 binary32 arithmetic), and integer divisions stay integer divisions, so the integer
 * thresholds compared against {@code Random.nextInt} match Cobblemon exactly.
 *
 * <p>The per-throw model, for a non-guaranteed ball:
 * <pre>
 *   S  = round(65536 / (255 / m)^0.1875)              line 87
 *   ps = clamp(S, 0, 65537) / 65537                    nextInt(65537) &lt; S, line 90-91
 *   c  = round(m * caughtMultiplier / 6)               CriticalCaptureProvider.kt:44-46
 *   pc = clamp(c, 0, 256) / 256                        nextInt(256) &lt; c, CriticalCaptureProvider.kt:47
 *   P  = pc * ps + (1 - pc) * ps^4                     critical: one shake decides (line 94-95); else 4 shakes (line 98)
 * </pre>
 * The Pokédex "owned" influence (PokedexStatusCaptureInfluencer) only relabels an already successful capture as
 * critical with one shake; it does not change P.
 */
public final class CaptureMath {
    private CaptureMath() {}

    /** {@code Random.nextInt(65537)} in the shake loop (line 90). */
    public static final int SHAKE_RANGE = 65537;
    /** {@code Random.nextInt(256)} in CriticalCaptureProvider.kt:47. */
    public static final int CRITICAL_RANGE = 256;
    /** {@code maxPokemonLevel = 100} (CobblemonConfig.kt:44). */
    public static final int DEFAULT_MAX_POKEMON_LEVEL = 100;
    private static final int MAX_HP_CANDIDATES = 100_000;

    // ---------------------------------------------------------------- individual terms

    /** {@code inBattleModifier = if (target.battleId != null) 1F else 0.5F} (line 62). */
    public static float inBattleModifier(boolean inBattle) {
        return inBattle ? 1F : 0.5F;
    }

    /**
     * {@code bonusLevel = if (pokemon.level < 13) max((36 - (2 * pokemon.level)) / 10, 1) else 1} (line 70).
     * Kotlin {@code Int / Int} is integer division, so the result is 3 (level 1-3), 2 (level 4-8) or 1 (level 9+).
     */
    public static int levelBonus(int level) {
        return level < 13 ? Math.max((36 - (2 * level)) / 10, 1) : 1;
    }

    /**
     * The level-difference factor of lines 79-85, or 1 when it is not applied
     * ({@code highestLevelThrower == null || highestLevelThrower >= pokemon.level}).
     * {@code (pokemon.level - highestLevelThrower) / (config.maxPokemonLevel / 2)} is integer division, so with the
     * default max level 100 the factor is 1 for a gap below 50 and 0.1 for a gap of 50 or more.
     */
    public static float levelDifferenceFactor(int targetLevel, Integer referenceLevel, int maxPokemonLevel) {
        if (referenceLevel == null || referenceLevel >= targetLevel) return 1F;
        return Math.max(0.1F, Math.min(1F, 1F - ((targetLevel - referenceLevel) / (maxPokemonLevel / 2))));
    }

    /** {@code caughtMultiplier} of CriticalCaptureProvider.kt:35-43. */
    public static float criticalCaughtMultiplier(int caughtCount) {
        if (caughtCount <= 30) return 0F;
        if (caughtCount <= 150) return 0.5F;
        if (caughtCount <= 300) return 1F;
        if (caughtCount <= 450) return 1.5F;
        if (caughtCount <= 600) return 2F;
        return 2.5F;
    }

    /**
     * {@code modifiedCatchRate} after lines 75-85, in float exactly as Cobblemon computes it.
     *
     * @param catchRate  catch rate after the POKEMON_CATCH_RATE event (vanilla: species/form catch rate)
     * @param ballBonus  the effective {@code ballBonus} (1F when the ball's condition does not apply, line 73)
     */
    public static float modifiedCatchRate(float catchRate, int maxHp, int currentHp, int level, PersistentStatus status,
                                          boolean inBattle, float ballBonus, Integer penaltyReferenceLevel,
                                          int maxPokemonLevel) {
        float darkGrass = 1F;                                   // line 61 (the Pokédex multiplier is commented out)
        float inBattleModifier = inBattleModifier(inBattle);    // line 62
        float bonusStatus = status.captureBonus;                // line 65-69
        int bonusLevel = levelBonus(level);                     // line 70
        // line 75-77: behavior MULTIPLY -> (numerator * ballBonus) / (3F * maxHealth)
        float numerator = (3F * maxHp - 2F * currentHp) * darkGrass * catchRate * inBattleModifier;
        float modified = (numerator * ballBonus) / (3F * maxHp);
        modified *= bonusStatus * bonusLevel;                   // line 78
        if (penaltyReferenceLevel != null && penaltyReferenceLevel < level) { // line 81
            modified *= levelDifferenceFactor(level, penaltyReferenceLevel, maxPokemonLevel); // line 83
        }
        return modified;
    }

    /**
     * {@code shakeProbability = (65536F / (255F / modifiedCatchRate).pow(0.1875F)).roundToInt()} (line 87).
     * Kotlin's {@code Float.pow} computes in double and narrows to float; {@code roundToInt} is {@code Math.round}
     * and throws on NaN, which happens for a negative modified catch rate.
     */
    public static int shakeThreshold(float modifiedCatchRate) {
        float ratio = 255F / modifiedCatchRate;
        float powered = (float) Math.pow(ratio, 0.1875F);
        float q = 65536F / powered;
        if (Float.isNaN(q)) throw new IllegalArgumentException("Cobblemon would throw: cannot round NaN (modified catch rate " + modifiedCatchRate + ")");
        return Math.round(q);
    }

    /** {@code c = ((modifiedCatchRate * caughtMultiplier * 1F) / 6F).roundToInt()} (CriticalCaptureProvider.kt:44-46). */
    public static int criticalThreshold(float modifiedCatchRate, int caughtCount) {
        float b = modifiedCatchRate * criticalCaughtMultiplier(caughtCount);
        float q = (b * 1F) / 6F;
        if (Float.isNaN(q)) throw new IllegalArgumentException("Cobblemon would throw: cannot round NaN");
        return Math.round(q);
    }

    /** P({@code Random.nextInt(65537) < threshold}). */
    public static double shakeProbability(int threshold) {
        return Math.min(Math.max(threshold, 0), SHAKE_RANGE) / (double) SHAKE_RANGE;
    }

    /** P({@code Random.nextInt(256) < threshold}). */
    public static double criticalProbability(int threshold) {
        return Math.min(Math.max(threshold, 0), CRITICAL_RANGE) / (double) CRITICAL_RANGE;
    }

    /**
     * Per-throw success: with probability pc the throw is critical and succeeds iff the first shake check passes
     * (line 94-95); otherwise all four shake checks must pass (line 89-98).
     */
    public static double captureProbability(double shakeProbability, double criticalProbability) {
        double ps4 = shakeProbability * shakeProbability * shakeProbability * shakeProbability;
        return criticalProbability * shakeProbability + (1 - criticalProbability) * ps4;
    }

    /** Per-throw probability from a modified catch rate and a known CaughtCount. */
    public static double captureProbability(float modifiedCatchRate, int caughtCount) {
        double ps = shakeProbability(shakeThreshold(modifiedCatchRate));
        if (ps == 0 || ps == 1) return ps;
        return captureProbability(ps, criticalProbability(criticalThreshold(modifiedCatchRate, caughtCount)));
    }

    /** Geometric expectation 1/p for independent, identical throws; +Infinity when p == 0. */
    public static double expectedBalls(double p) {
        if (p < 0 || p > 1 || Double.isNaN(p)) throw new IllegalArgumentException("p=" + p);
        return p == 0 ? Double.POSITIVE_INFINITY : 1 / p;
    }

    /**
     * Expected balls out of battle when the target starts asleep. A failed out-of-battle capture clears SLEEP
     * ({@code EmptyPokeBallEntity.breakFree}, EmptyPokeBallEntity.kt:402-404), so only the first throw gets the
     * sleep bonus: E = 1 + (1 - pFirst) * (1 / pAfter).
     */
    public static double expectedBallsOutOfBattleSleep(double pFirst, double pAfter) {
        if (pFirst == 1) return 1;
        return 1 + (1 - pFirst) * expectedBalls(pAfter);
    }

    /** Probability of at least one capture within n throws of identical probability p. */
    public static double probabilityWithin(double p, int n) {
        if (n <= 0) return 0;
        return 1 - Math.pow(1 - p, n);
    }

    // ---------------------------------------------------------------- full evaluation

    /**
     * Evaluates one throw. Returns an unknown result listing the missing inputs when any input the formula needs is
     * {@code null}; nothing is defaulted silently. A guaranteed ball (Master Ball, Ancient Origin Ball) needs no
     * other input (line 57-59).
     */
    public static CaptureResult evaluate(CaptureInputs in) {
        BallEffect ball = in.ball() == null ? BallEffect.unknown() : in.ball();
        if (ball.guaranteed()) {
            return new CaptureResult(true, List.of(), true, Float.NaN, -1, 1, -1, Double.NaN, 1, 1,
                    "guaranteed: isGuaranteed() short-circuits the calculator");
        }
        List<String> missing = new ArrayList<>();
        if (in.catchRate() == null) missing.add("catchRate");
        if (in.maxHp() == null) missing.add("maxHp");
        if (in.currentHp() == null) missing.add("currentHp");
        if (in.level() == null) missing.add("level");
        if (in.status() == null) missing.add("status");
        if (in.inBattle() == null) missing.add("inBattle");
        if (ball.applies() == null) missing.add("ball.conditionApplies");
        if (!Boolean.FALSE.equals(ball.applies()) && ball.value() == null) missing.add("ball.value");
        if (!missing.isEmpty()) return CaptureResult.unknown(missing);

        validate(in.catchRate(), in.maxHp(), in.currentHp(), in.level(), in.maxPokemonLevel());
        float bonus = ball.effectiveBonus();
        float m = modifiedCatchRate(in.catchRate(), in.maxHp(), in.currentHp(), in.level(), in.status(),
                in.inBattle(), bonus, in.penaltyReferenceLevel(), in.maxPokemonLevel());
        int s = shakeThreshold(m);
        double ps = shakeProbability(s);
        int c = -1;
        double pc = Double.NaN;
        if (in.caughtCount() != null) {
            c = criticalThreshold(m, in.caughtCount());
            pc = criticalProbability(c);
        }
        double p;
        if (ps == 0 || ps == 1) {
            p = ps; // with ps in {0,1} the critical branch cannot change the outcome
        } else if (in.caughtCount() == null) {
            return CaptureResult.unknown(List.of("caughtCount"));
        } else {
            p = captureProbability(ps, pc);
        }

        double expected;
        String model;
        if (in.status() == PersistentStatus.SLEEP && !in.inBattle() && p < 1) {
            float m0 = modifiedCatchRate(in.catchRate(), in.maxHp(), in.currentHp(), in.level(), PersistentStatus.NONE,
                    false, bonus, in.penaltyReferenceLevel(), in.maxPokemonLevel());
            double ps0 = shakeProbability(shakeThreshold(m0));
            if (ps0 != 0 && ps0 != 1 && in.caughtCount() == null) return CaptureResult.unknown(List.of("caughtCount"));
            double p0 = (ps0 == 0 || ps0 == 1) ? ps0 : captureProbability(m0, in.caughtCount());
            expected = expectedBallsOutOfBattleSleep(p, p0);
            model = "out-of-battle sleep: first throw asleep, later throws without status (sleep is cleared on a failed capture)";
        } else {
            expected = expectedBalls(p);
            model = in.inBattle()
                    ? "geometric 1/p assuming HP, status and ball bonus stay the same between throws (in battle they can change each turn)"
                    : "geometric 1/p: out of battle a failed throw does not change HP or a non-sleep status";
        }
        return new CaptureResult(true, List.of(), false, m, s, ps, c, pc, p, expected, model);
    }

    private static void validate(int catchRate, int maxHp, int currentHp, int level, int maxPokemonLevel) {
        if (catchRate < 0) throw new IllegalArgumentException("catchRate < 0");
        if (maxHp < 1) throw new IllegalArgumentException("maxHp < 1");
        if (currentHp < 0 || currentHp > maxHp) throw new IllegalArgumentException("currentHp outside 0..maxHp");
        if (level < 1) throw new IllegalArgumentException("level < 1");
        if (maxPokemonLevel < 2) throw new IllegalArgumentException("maxPokemonLevel < 2 (Cobblemon divides by maxPokemonLevel / 2)");
    }

    // ---------------------------------------------------------------- bounds

    /**
     * Exact min/max of the per-throw capture probability over all input combinations in {@code r}.
     *
     * <p>Soundness: for a fixed max HP, every step of {@link #modifiedCatchRate} is a float multiplication or division
     * by a positive quantity (or an exact integer-valued subtraction), and IEEE rounding is monotone, so the modified
     * catch rate is non-increasing in current HP and level and non-decreasing in ball bonus, status bonus and
     * in-battle. {@code S} and {@code c} are monotone in it, the critical multiplier is monotone in CaughtCount, and
     * {@code P = pc*ps + (1-pc)*ps^4} is non-decreasing in both ps and pc (because ps &gt;= ps^4). Max HP is not
     * monotone after float rounding, so every candidate max HP is evaluated; statuses and in-battle values are
     * enumerated so that the out-of-battle sleep rule for expected balls is handled exactly.
     */
    public static CaptureBounds bounds(CaptureRanges r) {
        if (r.ballGuaranteed()) return new CaptureBounds(true, List.of(), 1, 1, 1, 1);
        List<String> missing = new ArrayList<>();
        if (r.catchRate() == null) missing.add("catchRate");
        if (r.maxHpMin() < 1 || r.maxHpMax() < r.maxHpMin()) missing.add("maxHp range");
        if (r.levelMin() < 1 || r.levelMax() < r.levelMin()) missing.add("level range");
        if (r.statuses().isEmpty()) missing.add("status set");
        if (r.inBattle().isEmpty()) missing.add("inBattle set");
        if (!missing.isEmpty()) return CaptureBounds.unknown(missing);
        if ((long) r.maxHpMax() - r.maxHpMin() >= MAX_HP_CANDIDATES) throw new IllegalArgumentException("max HP range too wide");
        if (r.catchRate() < 0 || r.ballBonusMin() < 0 || r.ballBonusMax() < r.ballBonusMin()
                || r.caughtCountMax() < r.caughtCountMin() || r.maxPokemonLevel() < 2
                || r.hpFractionMin() < 0 || r.hpFractionMax() > 1 || r.hpFractionMax() < r.hpFractionMin()) {
            throw new IllegalArgumentException("invalid ranges: " + r);
        }

        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;
        double minE = Double.POSITIVE_INFINITY, maxE = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (int maxHp = r.maxHpMin(); maxHp <= r.maxHpMax(); maxHp++) {
            int lo, hi;
            if (r.currentHpMin() != null && r.currentHpMax() != null) {
                lo = Math.max(1, r.currentHpMin());
                hi = Math.min(maxHp, r.currentHpMax());
            } else {
                lo = Math.max(1, (int) Math.floor(r.hpFractionMin() * maxHp));
                hi = Math.min(maxHp, (int) Math.ceil(r.hpFractionMax() * maxHp));
            }
            if (lo > hi) continue; // this max HP is inconsistent with the current-HP range
            for (PersistentStatus st : r.statuses()) {
                for (boolean ib : r.inBattle()) {
                    any = true;
                    // lowest probability: most HP, highest level, smallest bonuses, fewest owned species
                    float mLow = modifiedCatchRate(r.catchRate(), maxHp, hi, r.levelMax(), st, ib, r.ballBonusMin(),
                            r.penaltyReferenceLevel(), r.maxPokemonLevel());
                    float mHigh = modifiedCatchRate(r.catchRate(), maxHp, lo, r.levelMin(), st, ib, r.ballBonusMax(),
                            r.penaltyReferenceLevel(), r.maxPokemonLevel());
                    double pLow = captureProbability(mLow, r.caughtCountMin());
                    double pHigh = captureProbability(mHigh, r.caughtCountMax());
                    minP = Math.min(minP, pLow);
                    maxP = Math.max(maxP, pHigh);
                    double eHigh, eLow;
                    if (st == PersistentStatus.SLEEP && !ib) {
                        float m0Low = modifiedCatchRate(r.catchRate(), maxHp, hi, r.levelMax(), PersistentStatus.NONE, false,
                                r.ballBonusMin(), r.penaltyReferenceLevel(), r.maxPokemonLevel());
                        float m0High = modifiedCatchRate(r.catchRate(), maxHp, lo, r.levelMin(), PersistentStatus.NONE, false,
                                r.ballBonusMax(), r.penaltyReferenceLevel(), r.maxPokemonLevel());
                        eHigh = expectedBallsOutOfBattleSleep(pLow, captureProbability(m0Low, r.caughtCountMin()));
                        eLow = expectedBallsOutOfBattleSleep(pHigh, captureProbability(m0High, r.caughtCountMax()));
                    } else {
                        eHigh = expectedBalls(pLow);
                        eLow = expectedBalls(pHigh);
                    }
                    minE = Math.min(minE, eLow);
                    maxE = Math.max(maxE, eHigh);
                }
            }
        }
        if (!any) return CaptureBounds.unknown(List.of("no max HP is consistent with the current HP range"));
        return new CaptureBounds(true, List.of(), minP, maxP, minE, maxE);
    }
}

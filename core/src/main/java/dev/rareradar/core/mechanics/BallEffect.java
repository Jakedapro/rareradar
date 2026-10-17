package dev.rareradar.core.mechanics;

/**
 * What a Poké Ball's {@code CatchRateModifier} contributes to one throw, as consumed by Cobblemon 1.8.2's default
 * calculator (CobblemonCaptureCalculator.kt:57, 64, 73-77).
 *
 * <ul>
 *   <li>{@code guaranteed}: {@code catchRateModifier.isGuaranteed()} (Master Ball, Ancient Origin Ball). The calculator
 *       returns a successful capture before reading any other input (line 57-59).</li>
 *   <li>{@code value}: {@code catchRateModifier.value(thrower, pokemon)}; {@code null} when unknown.</li>
 *   <li>{@code applies}: {@code catchRateModifier.isValid(thrower, pokemon)}; {@code null} when unknown. When false the
 *       calculator uses {@code ballBonus = 1F} (line 73).</li>
 * </ul>
 * Every modifier registered in PokeBalls.kt 1.8.2 has behaviour MULTIPLY, so the ball bonus multiplies the
 * numerator (line 75-77). This type therefore models multiplication only.
 */
public record BallEffect(boolean guaranteed, Float value, Boolean applies) {

    public BallEffect {
        if (value != null && (Float.isNaN(value) || value < 0F)) throw new IllegalArgumentException("ball value must be >= 0: " + value);
    }

    /** {@code GuaranteedModifier} (value 255F, isGuaranteed() = true; GuaranteedModifier.kt). */
    public static BallEffect guaranteedCapture() { return new BallEffect(true, 255F, true); }

    /** The default {@code MultiplierModifier(1F) { _, _ -> true }} of PokeBalls.kt:334. */
    public static BallEffect plain() { return new BallEffect(false, 1F, true); }

    public static BallEffect multiplier(float value, boolean applies) { return new BallEffect(false, value, applies); }

    public static BallEffect unknown() { return new BallEffect(false, null, null); }

    /**
     * The {@code ballBonus} the calculator will use (line 73), or {@code null} when it cannot be determined.
     * When the condition is known not to apply the value is irrelevant and the bonus is 1F.
     */
    public Float effectiveBonus() {
        if (applies == null) return null;
        if (!applies) return 1F;
        return value;
    }
}

package dev.rareradar.core.mechanics;

import java.util.List;

/**
 * Result of {@link CaptureMath#evaluate}. When {@link #known()} is false every numeric field is NaN / -1 and
 * {@link #missing()} lists the inputs that would be needed; callers must show "unknown", never a default.
 *
 * @param modifiedCatchRate  the calculator's {@code modifiedCatchRate} (float, as in Cobblemon); NaN if not computed
 * @param shakeThreshold     {@code shakeProbability} integer (each shake succeeds when {@code nextInt(65537) < it}); -1 if n/a
 * @param shakeProbability   probability of one shake check succeeding
 * @param criticalThreshold  {@code c} of CriticalCaptureProvider (critical when {@code nextInt(256) < c}); -1 if n/a
 * @param criticalProbability probability the throw is a critical capture
 * @param captureProbability probability this single throw captures
 * @param expectedBalls      expected balls thrown until capture, see {@code expectedBallsModel}
 * @param expectedBallsModel how {@code expectedBalls} was derived
 */
public record CaptureResult(
        boolean known,
        List<String> missing,
        boolean guaranteed,
        float modifiedCatchRate,
        int shakeThreshold,
        double shakeProbability,
        int criticalThreshold,
        double criticalProbability,
        double captureProbability,
        double expectedBalls,
        String expectedBallsModel) {

    public CaptureResult {
        missing = List.copyOf(missing);
    }

    public static CaptureResult unknown(List<String> missing) {
        return new CaptureResult(false, missing, false, Float.NaN, -1, Double.NaN, -1, Double.NaN, Double.NaN,
                Double.NaN, "unknown");
    }
}

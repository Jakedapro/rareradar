package dev.rareradar.core.mechanics;

import java.util.List;

/**
 * Exact minimum/maximum per-throw capture probability over a {@link CaptureRanges} set, and the matching range of
 * expected balls. {@code known == false} means no meaningful bound exists (e.g. catch rate unknown).
 */
public record CaptureBounds(
        boolean known,
        List<String> missing,
        double minProbability,
        double maxProbability,
        double minExpectedBalls,
        double maxExpectedBalls) {

    public CaptureBounds {
        missing = List.copyOf(missing);
    }

    public static CaptureBounds unknown(List<String> missing) {
        return new CaptureBounds(false, missing, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }
}

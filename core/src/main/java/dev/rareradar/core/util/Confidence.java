package dev.rareradar.core.util;

/** Coarse, user-facing confidence label. Always shown next to estimates so speculation is never mistaken for fact. */
public enum Confidence {
    NONE("No data"), LOW("Low"), MEDIUM("Medium"), HIGH("High");

    public final String label;

    Confidence(String label) { this.label = label; }

    /**
     * Confidence from sample size and relative interval width ((high-low)/|estimate|).
     * Thresholds are deliberately conservative: a handful of observations is never "High".
     */
    public static Confidence of(int samples, double relativeWidth) {
        if (samples <= 0) return NONE;
        if (samples < 5 || !Double.isFinite(relativeWidth) || relativeWidth > 1.0) return LOW;
        if (samples < 20 || relativeWidth > 0.4) return MEDIUM;
        return HIGH;
    }

    public Confidence min(Confidence o) { return ordinal() <= o.ordinal() ? this : o; }
}

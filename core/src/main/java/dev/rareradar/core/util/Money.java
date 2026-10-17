package dev.rareradar.core.util;

import java.util.Locale;

/** Formatting for Pokédollar amounts. Unknown values are rendered as "?" rather than 0. */
public final class Money {
    private Money() {}

    public static String fmt(double v) {
        if (!Double.isFinite(v)) return "?";
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000) s = String.format(Locale.ROOT, "%.2fM", a / 1_000_000);
        else if (a >= 10_000) s = String.format(Locale.ROOT, "%.1fk", a / 1_000);
        else s = String.format(Locale.ROOT, "%,.0f", a);
        return (v < 0 ? "-$" : "$") + s;
    }

    public static String range(Estimate e) {
        if (!e.known()) return "unknown";
        return fmt(e.value()) + " (" + fmt(e.low()) + "–" + fmt(e.high()) + ", " + e.confidence().label + ")";
    }
}

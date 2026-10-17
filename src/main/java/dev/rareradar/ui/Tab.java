package dev.rareradar.ui;

import dev.rareradar.core.report.Line;

import java.util.List;

/** One dashboard page: optional input widgets plus text lines (built by core DashboardText where possible). */
public interface Tab {
    String name();

    /** Adds widgets in the strip at the top of the content area; returns the height used. */
    default int widgets(DashboardScreen s, int x, int y, int w) { return 0; }

    void lines(List<Line> out);

    static int color(Line.Kind k) {
        return switch (k) {
            case HEADER -> 0x8FD3FF;
            case TEXT -> 0xE6E6E6;
            case DIM -> 0x8A8A8A;
            case WARN -> 0xFFB347;
            case GOOD -> 0x7CFC00;
            case BLANK -> 0;
        };
    }
}

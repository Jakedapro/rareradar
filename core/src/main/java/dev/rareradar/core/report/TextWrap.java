package dev.rareradar.core.report;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Greedy word wrap for dashboard lines. Width is supplied by the caller (pixels in-game, characters in tests). */
public final class TextWrap {
    private TextWrap() {}

    /**
     * Splits {@code text} into lines no wider than {@code avail}. Continuation lines are indented by two spaces and keep
     * the last § colour code seen. Always makes progress, so a single over-long word is broken rather than looping.
     */
    public static List<String> wrap(String text, int avail, ToIntFunction<String> width) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty() || width.applyAsInt(text) <= avail) { out.add(text); return out; }
        String carry = "";
        String rest = text;
        boolean first = true;
        for (int guard = 0; !rest.isEmpty() && guard < 60; guard++) {
            String cand = (first ? "" : "  ") + carry + rest;
            if (width.applyAsInt(cand) <= avail) { out.add(cand); return out; }
            int cut = cutPoint(cand, avail, width);
            String head = cand.substring(0, cut).stripTrailing();
            out.add(head);
            int sect = head.lastIndexOf('§');
            if (sect >= 0 && sect + 1 < head.length()) carry = head.substring(sect, sect + 2);
            rest = cand.substring(cut).stripLeading();
            first = false;
        }
        if (!rest.isEmpty()) out.add("  " + rest);
        return out;
    }

    /** Break index: the widest prefix that fits, pulled back to a space when that does not waste over half the line. */
    public static int cutPoint(String s, int avail, ToIntFunction<String> width) {
        int lo = 1, hi = s.length(), best = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (width.applyAsInt(s.substring(0, mid)) <= avail) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        int sp = s.lastIndexOf(' ', best);
        return sp > best / 2 ? sp : best;
    }
}

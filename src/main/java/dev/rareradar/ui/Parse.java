package dev.rareradar.ui;

import java.util.Locale;

/** Lenient parsing of user-typed numbers: "12k", "1.5m", "$3,000". NaN when blank or invalid. */
public final class Parse {
    private Parse() {}

    public static double money(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().toLowerCase(Locale.ROOT).replace("$", "").replace(",", "").replace("_", "");
        if (t.isEmpty()) return Double.NaN;
        double mul = 1;
        if (t.endsWith("k")) { mul = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { mul = 1e6; t = t.substring(0, t.length() - 1); }
        try { double v = Double.parseDouble(t) * mul; return Double.isFinite(v) && v >= 0 ? v : Double.NaN; }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    public static int integer(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    /** "31/31/31/0/31/31" or "31 31 31 0 31 31" → int[6]; null if blank/invalid. */
    public static int[] ivs(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.trim().split("[/\\s,]+");
        if (p.length != 6) return null;
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            v[i] = integer(p[i], -1);
            if (v[i] < 0 || v[i] > 31) return null;
        }
        return v;
    }
}

package dev.rareradar.core.report;

/** One dashboard text line with a semantic kind (mapped to a colour by the UI). */
public record Line(Kind kind, String text) {
    public enum Kind { HEADER, TEXT, DIM, WARN, GOOD, BLANK }

    public static Line h(String t) { return new Line(Kind.HEADER, t); }
    public static Line t(String t) { return new Line(Kind.TEXT, t); }
    public static Line dim(String t) { return new Line(Kind.DIM, t); }
    public static Line warn(String t) { return new Line(Kind.WARN, t); }
    public static Line good(String t) { return new Line(Kind.GOOD, t); }
    public static Line blank() { return new Line(Kind.BLANK, ""); }
}

package dev.rareradar.core.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSV import/export for price observations. Header:
 * {@code timestamp,species,form,shiny,kind,price,ivs,nature,ability,note}; IVs as "31/31/31/0/31/31".
 */
public final class MarketCsv {
    public static final String HEADER = "timestamp,species,form,shiny,kind,price,ivs,nature,ability,note";

    private MarketCsv() {}

    public static String export(List<PriceObservation> rows) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (PriceObservation p : rows) {
            sb.append(p.timestamp).append(',').append(esc(p.species)).append(',').append(esc(p.form)).append(',')
              .append(Boolean.TRUE.equals(p.shiny)).append(',').append(p.kind).append(',')
              .append(String.format(Locale.ROOT, "%.2f", p.price)).append(',')
              .append(p.ivs == null ? "" : ivs(p.ivs)).append(',').append(esc(p.nature)).append(',')
              .append(esc(p.ability)).append(',').append(esc(p.note)).append('\n');
        }
        return sb.toString();
    }

    public record Parsed(List<PriceObservation> rows, List<String> errors) {}

    public static Parsed parse(String csv) {
        List<PriceObservation> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("timestamp"))) continue;
            try {
                List<String> f = split(line);
                while (f.size() < 10) f.add("");
                PriceObservation p = new PriceObservation();
                p.timestamp = Long.parseLong(f.get(0).trim());
                p.species = f.get(1);
                p.form = f.get(2).isBlank() ? null : f.get(2);
                p.shiny = Boolean.parseBoolean(f.get(3).trim());
                p.kind = PriceObservation.Kind.valueOf(f.get(4).trim().toUpperCase(Locale.ROOT));
                p.price = Double.parseDouble(f.get(5).trim().replace("$", "").replace("_", ""));
                if (!f.get(6).isBlank()) {
                    String[] parts = f.get(6).trim().split("/");
                    int[] v = new int[parts.length];
                    for (int j = 0; j < parts.length; j++) v[j] = Integer.parseInt(parts[j].trim());
                    p.ivs = v;
                }
                p.nature = blankToNull(f.get(7));
                p.ability = blankToNull(f.get(8));
                p.note = f.get(9);
                p.source = PriceObservation.Source.IMPORT;
                p.normalize();
                String err = p.validate();
                if (err != null) errors.add("line " + (i + 1) + ": " + err);
                else out.add(p);
            } catch (RuntimeException e) {
                errors.add("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new Parsed(out, errors);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String ivs(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append('/'); sb.append(v[i]); }
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}

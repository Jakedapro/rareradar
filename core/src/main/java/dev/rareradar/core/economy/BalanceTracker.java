package dev.rareradar.core.economy;

import dev.rareradar.core.market.GtsTextParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the player's balance from the server's scoreboard sidebar text and turns changes into observed money flow.
 * The first reading of a session is a baseline, never income. A reading is used only when exactly one sidebar line
 * matches the balance label and carries one amount, so other numbers on the sidebar are never mistaken for money.
 */
public final class BalanceTracker {
    /** Default label: a sidebar line mentioning balance/money/Pokédollars, or a $ amount. Configurable. */
    public static final String DEFAULT_LABEL = "(?i)(balance|money|bal\\b|pok[eé]\\s?dollars?|\\$|₽)";

    public record Change(long ts, double before, double after) {
        public double delta() { return after - before; }
    }

    private Pattern label;
    private double current = Double.NaN, sessionStart = Double.NaN;
    private long lastReadingTs;
    private String lastLine;
    private final List<Change> changes = new ArrayList<>();

    public BalanceTracker(String labelRegex) { setLabel(labelRegex); }

    public void setLabel(String regex) {
        try { label = Pattern.compile(regex == null || regex.isBlank() ? DEFAULT_LABEL : regex); }
        catch (java.util.regex.PatternSyntaxException e) { label = Pattern.compile(DEFAULT_LABEL); }
    }

    /** Parses the balance from sidebar lines; NaN when absent or ambiguous. */
    public double parse(List<String> sidebarLines) {
        double found = Double.NaN;
        String line = null;
        for (String raw : sidebarLines) {
            String l = GtsTextParser.clean(raw);
            if (!label.matcher(l).find()) continue;
            double v = GtsTextParser.singlePrice(l);
            if (!(v >= 0)) v = labelledNumber(l);
            if (!(v >= 0)) continue;
            if (!Double.isNaN(found)) return Double.NaN; // two money lines: ambiguous
            found = v; line = l;
        }
        if (!Double.isNaN(found)) lastLine = line;
        return found;
    }

    private static final Pattern LABELLED = Pattern.compile("(?i)(?:balance|money|bal)\\s*[:=]?\\s*(\\d[\\d,. ]*)\\s*([km])?");

    private static double labelledNumber(String l) {
        var m = LABELLED.matcher(l);
        if (!m.find()) return Double.NaN;
        double v = TransactionRule.parse(m.group(1).trim());
        if (m.group(2) != null) v *= m.group(2).equalsIgnoreCase("k") ? 1e3 : 1e6;
        return v;
    }

    /** Feeds one reading; returns the change it represents, or null (no change, baseline or unreadable). */
    public Change observe(List<String> sidebarLines, long now) {
        double v = parse(sidebarLines);
        if (Double.isNaN(v)) return null;
        lastReadingTs = now;
        if (Double.isNaN(current)) {
            current = v;
            if (Double.isNaN(sessionStart)) sessionStart = v;
            return null;
        }
        if (Math.abs(v - current) < 1e-6) return null;
        Change c = new Change(now, current, v);
        current = v;
        changes.add(c);
        if (changes.size() > 2000) changes.remove(0);
        return c;
    }

    /** Disconnect/world change: the next reading is a new baseline (no jump counted as income). */
    public void resetSession() { current = Double.NaN; sessionStart = Double.NaN; changes.clear(); }

    public double current() { return current; }
    public double sessionNet() { return Double.isNaN(current) || Double.isNaN(sessionStart) ? Double.NaN : current - sessionStart; }
    public long lastReadingTs() { return lastReadingTs; }
    public String lastLine() { return lastLine; }
    public List<Change> changes() { return Collections.unmodifiableList(changes); }

    /**
     * Marks transactions whose signed amount equals a balance change within {@code windowMs} as balance-confirmed.
     * Returns the number newly confirmed.
     */
    public static int reconcile(List<Transaction> txs, List<Change> changes, long windowMs) {
        int n = 0;
        boolean[] used = new boolean[changes.size()];
        for (Transaction t : txs) {
            if (t.balanceConfirmed) continue;
            for (int i = 0; i < changes.size(); i++) {
                Change c = changes.get(i);
                if (used[i] || Math.abs(c.ts() - t.ts) > windowMs) continue;
                if (Math.abs(c.delta() - t.signed()) < 0.5) { used[i] = true; t.balanceConfirmed = true; n++; break; }
            }
        }
        return n;
    }
}

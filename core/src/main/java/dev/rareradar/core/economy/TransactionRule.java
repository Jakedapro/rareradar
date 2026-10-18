package dev.rareradar.core.economy;

import dev.rareradar.core.market.GtsTextParser;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A message format the player labelled from a real server message. The rule is the example with its money amount
 * (and, when present, the Pokémon species) turned into capture groups and every other number generalised. It is
 * anchored to the whole message, so player chat relayed as "&lt;name&gt; …" cannot imitate it.
 */
public final class TransactionRule {
    public String id;
    public TransactionKind kind;
    public String regex;
    /** The real message this rule was learned from (kept for audit). */
    public String example;
    public long created;
    public boolean enabled = true;

    private transient Pattern compiled;

    public record Match(double amount, String subject) {}

    static final Pattern NUMBER = Pattern.compile("\\d+(?:[,. ]\\d+)*");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'.-]*");

    /**
     * Learns a rule from one real message.
     *
     * @return null when the message does not contain exactly one money amount
     */
    public static TransactionRule learn(String rawMessage, TransactionKind kind, Predicate<String> isSpecies, long now) {
        String msg = GtsTextParser.clean(rawMessage);
        double amount = GtsTextParser.singlePrice(msg);
        if (!(amount > 0)) return null;
        // Locate the money token: the numeric run whose parsed value (with optional k/m) equals the amount.
        Matcher n = NUMBER.matcher(msg);
        int aStart = -1, aEnd = -1;
        while (n.find()) {
            int end = n.end();
            double v = parse(n.group());
            if (end < msg.length() && "kKmM".indexOf(msg.charAt(end)) >= 0) {
                v *= Character.toLowerCase(msg.charAt(end)) == 'k' ? 1e3 : 1e6;
                end++;
            }
            if (Math.abs(v - amount) < 1e-6) { aStart = n.start(); aEnd = end; break; }
        }
        if (aStart < 0) return null;
        // Optional species token (a word that is a known species), outside the amount.
        int sStart = -1, sEnd = -1;
        Matcher w = WORD.matcher(msg);
        while (w.find()) {
            String t = w.group().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
            if (!t.isEmpty() && isSpecies.test(t) && (w.end() <= aStart || w.start() >= aEnd)) { sStart = w.start(); sEnd = w.end(); break; }
        }
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < msg.length()) {
            if (i == aStart) { re.append("(?<amount>\\d+(?:[,. ]\\d+)*(?:[kKmM])?)"); i = aEnd; continue; }
            if (i == sStart) { re.append("(?<subject>[A-Za-z][A-Za-z'.-]*(?: [A-Za-z][A-Za-z'.-]*)?)"); i = sEnd; continue; }
            Matcher num = NUMBER.matcher(msg).region(i, msg.length());
            if (num.lookingAt() && (aStart < 0 || num.end() <= aStart || i >= aEnd)) {
                re.append("\\d+(?:[,. ]\\d+)*");
                i = num.end();
                continue;
            }
            char c = msg.charAt(i);
            if (Character.isWhitespace(c)) {
                re.append("\\s+");
                while (i < msg.length() && Character.isWhitespace(msg.charAt(i))) i++;
                continue;
            }
            re.append(Pattern.quote(String.valueOf(c)));
            i++;
        }
        re.append("$");
        TransactionRule r = new TransactionRule();
        r.id = Long.toString(now, 36) + "-" + Integer.toHexString(msg.hashCode());
        r.kind = kind;
        r.regex = re.toString();
        r.example = msg;
        r.created = now;
        return r.compile() ? r : null;
    }

    public boolean compile() {
        if (regex == null || kind == null) return false;
        try { compiled = Pattern.compile(regex); return true; }
        catch (PatternSyntaxException e) { compiled = null; return false; }
    }

    /** Matches a cleaned server message; null when this rule does not apply. */
    public Match match(String cleanedMessage) {
        if (!enabled || (compiled == null && !compile())) return null;
        Matcher m = compiled.matcher(cleanedMessage);
        if (!m.matches()) return null;
        String a = m.group("amount");
        double v = parse(a.replaceAll("[kKmM]$", ""));
        char last = a.charAt(a.length() - 1);
        if (last == 'k' || last == 'K') v *= 1e3; else if (last == 'm' || last == 'M') v *= 1e6;
        if (!(v > 0) || v > 1e12) return null;
        String subject = null;
        try { subject = m.group("subject"); } catch (IllegalArgumentException ignored) { /* rule has no subject group */ }
        return new Match(v, subject == null ? null : subject.toLowerCase(Locale.ROOT).trim());
    }

    /** "12,500" / "12.500" / "12 500" → 12500; "12.5" → 12.5 (a single separator followed by 1–2 digits is decimal). */
    static double parse(String s) {
        String t = s.trim();
        if (t.matches("\\d+[.,]\\d{1,2}")) return Double.parseDouble(t.replace(',', '.'));
        try { return Double.parseDouble(t.replaceAll("[,. ]", "")); } catch (NumberFormatException e) { return Double.NaN; }
    }
}

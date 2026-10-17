package dev.rareradar.core.market;

import dev.rareradar.core.rarity.PokemonObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GTS prices from text the client already displays: server chat announcements and the item names/lore of a
 * chest-style /gts menu. CobbleGalaxy's exact wording is not documented, so the rules are deliberately conservative:
 * <ul>
 *   <li>exactly one unambiguous money amount is required, otherwise the line is rejected (never guessed);</li>
 *   <li>everything is an asking price (LISTING) unless the text explicitly says the item was sold/bought and does
 *       not also contain listing words — so a listing can never be stored as a completed sale;</li>
 *   <li>the subject must resolve to a known Pokémon species (via the supplied lookup) or an explicit item phrase.</li>
 * </ul>
 * Every outcome carries a reason so rejected lines can be logged and the patterns improved from real samples.
 */
public final class GtsTextParser {
    public enum Status { PARSED, IGNORED, REJECTED }

    public record Result(Status status, String reason, String subject, boolean pokemon, boolean shiny, int[] ivs,
                         double price, PriceObservation.Kind kind) {
        static Result ignored(String why) { return new Result(Status.IGNORED, why, null, false, false, null, Double.NaN, null); }
        static Result rejected(String why) { return new Result(Status.REJECTED, why, null, false, false, null, Double.NaN, null); }
    }

    private static final Pattern GTS = Pattern.compile("(?i)(?<![a-z])gts(?![a-z])");
    // $12,500 | $12.5k | 12,500 Pokédollars | 12500 PokeDollars | ₽12,500 | 1.2m coins
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:[$₽]\\s?(\\d{1,3}(?:[,. ]\\d{3})+|\\d+(?:\\.\\d+)?)\\s?([km])?(?![\\w]))"
                    + "|(?:(\\d{1,3}(?:[,. ]\\d{3})+|\\d+(?:\\.\\d+)?)\\s?([km])?\\s?(?:pok[eé]\\s?dollars?|pd\\b|coins?\\b|[$₽]))");
    private static final Pattern PRICE_LABEL = Pattern.compile("(?i)\\b(price|cost|buy(?:\\s*now)?|asking)\\b");
    private static final Pattern SALE_WORDS = Pattern.compile("(?i)\\b(sold|bought|purchased)\\b");
    private static final Pattern LISTING_WORDS = Pattern.compile("(?i)\\b(listed|listing|lists|selling|for\\s+sale|put\\s+up|added|is\\s+offering)\\b");
    private static final Pattern IVS = Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})/(\\d{1,2})/(\\d{1,2})/(\\d{1,2})/(\\d{1,2})(?!\\d)");
    private static final Pattern ITEM_PHRASE = Pattern.compile(
            "(?i)\\b(?:listed|listing|selling|sold|bought|purchased|put\\s+up)\\s+(?:a\\s+|an\\s+|(\\d+)\\s*x?\\s+)?(.+?)\\s+(?:for|at)\\b");
    private static final Pattern FORMAT_CODES = Pattern.compile("§.");

    private GtsTextParser() {}

    /** Parses one server (system) chat message. Messages without a GTS marker are IGNORED, not rejected. */
    public static Result parseAnnouncement(String raw, Predicate<String> isSpecies) {
        if (raw == null) return Result.ignored("empty");
        String s = clean(raw);
        if (!GTS.matcher(s).find()) return Result.ignored("no GTS marker");
        double price = singlePrice(s);
        if (Double.isNaN(price)) return Result.rejected("no price found");
        if (price < 0) return Result.rejected("more than one price in message");
        PriceObservation.Kind kind = kindOf(s);
        String species = findSpecies(s, isSpecies);
        boolean shiny = s.toLowerCase(Locale.ROOT).contains("shiny");
        int[] ivs = ivs(s);
        if (species != null) return new Result(Status.PARSED, "ok", species, true, shiny, ivs, price, kind);
        Matcher m = ITEM_PHRASE.matcher(s);
        if (m.find()) {
            String item = itemKey(m.group(2));
            if (item != null) return new Result(Status.PARSED, "ok (item)", item, false, false, null, price, kind);
        }
        return Result.rejected("could not identify the Pokémon or item");
    }

    /**
     * Parses one entry of a chest-style GTS menu: the stack's display name, its lore lines and, if the stack is a
     * Cobblemon Pokémon item, the species it carries. Always an asking price (LISTING).
     */
    public static Result parseMenuEntry(String name, List<String> lore, String speciesFromItem, Predicate<String> isSpecies) {
        List<String> lines = new ArrayList<>();
        for (String l : lore) lines.add(clean(l));
        double price = Double.NaN;
        int found = 0;
        for (String l : lines) {
            if (!PRICE_LABEL.matcher(l).find()) continue;
            double p = singlePrice(l);
            if (Double.isNaN(p)) p = labelledNumber(l); // e.g. "Price: 12,500" without a currency symbol
            if (p >= 0 && !Double.isNaN(p)) { price = p; found++; }
        }
        if (found == 0) return Result.rejected("no labelled price line in lore");
        if (found > 1) return Result.rejected("several price lines in lore");
        String cleanName = clean(name == null ? "" : name);
        String all = cleanName + "\n" + String.join("\n", lines);
        boolean shiny = all.toLowerCase(Locale.ROOT).contains("shiny");
        int[] ivs = ivs(all);
        String species = speciesFromItem != null ? PokemonObservation.norm(speciesFromItem) : findSpecies(cleanName, isSpecies);
        if (species != null) return new Result(Status.PARSED, "ok", species, true, shiny, ivs, price, PriceObservation.Kind.LISTING);
        String item = itemKey(cleanName);
        if (item == null) return Result.rejected("entry has no name");
        return new Result(Status.PARSED, "ok (item)", item, false, false, null, price, PriceObservation.Kind.LISTING);
    }

    private static final Pattern LABELLED_NUMBER = Pattern.compile(
            "(?i)\\b(?:price|cost|buy(?:\\s*now)?|asking)\\b\\s*[:=\\-»>]?\\s*(\\d{1,3}(?:[,. ]\\d{3})+|\\d+(?:\\.\\d+)?)\\s?([km])?(?![\\w/])");

    static double labelledNumber(String l) {
        Matcher m = LABELLED_NUMBER.matcher(l);
        if (!m.find()) return Double.NaN;
        double v = number(m.group(1));
        if (m.group(2) != null) v *= m.group(2).equalsIgnoreCase("k") ? 1e3 : 1e6;
        return v > 0 && v <= 1e12 ? v : Double.NaN;
    }

    /** SALE only for explicit completed-transaction wording without any listing wording; otherwise LISTING. */
    static PriceObservation.Kind kindOf(String s) {
        if (SALE_WORDS.matcher(s).find() && !LISTING_WORDS.matcher(s).find()) return PriceObservation.Kind.SALE;
        return PriceObservation.Kind.LISTING;
    }

    /** The single money amount in the text; NaN if none, -1 if more than one distinct amount. */
    static double singlePrice(String s) {
        Matcher m = MONEY.matcher(s);
        double found = Double.NaN;
        while (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(3);
            String suf = m.group(1) != null ? m.group(2) : m.group(4);
            double v = number(num);
            if (suf != null) v *= suf.equalsIgnoreCase("k") ? 1e3 : 1e6;
            if (!(v > 0) || v > 1e12) continue;
            if (!Double.isNaN(found) && Math.abs(found - v) > 1e-6) return -1;
            found = v;
        }
        return found;
    }

    private static double number(String n) {
        String t = n.trim();
        // "12,500" / "12.500" / "12 500" with 3-digit groups are thousands separators; "12.5" is a decimal.
        if (t.matches("\\d{1,3}([,. ]\\d{3})+")) t = t.replaceAll("[,. ]", "");
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
    }

    static String findSpecies(String s, Predicate<String> isSpecies) {
        if (isSpecies == null) return null;
        String[] w = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9'\\- ]", " ").trim().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            // two-word names first (e.g. "mr mime", "tapu koko"), then single words
            if (i + 1 < w.length) {
                String two = id(w[i] + w[i + 1]);
                if (two.length() > 3 && isSpecies.test(two)) return two;
            }
            String one = id(w[i]);
            if (one.length() >= 3 && !STOP.contains(one) && isSpecies.test(one)) return one;
        }
        return null;
    }

    private static final java.util.Set<String> STOP = java.util.Set.of("gts", "shiny", "the", "for", "has", "listed", "sold", "bought", "price", "level", "lvl");

    private static String id(String w) { return w.replaceAll("[^\\p{L}0-9]", "").toLowerCase(Locale.ROOT); }

    static String itemKey(String phrase) {
        if (phrase == null) return null;
        String p = phrase.replaceAll("(?i)\\b(shiny)\\b", "").replaceAll("[^\\p{L}0-9 '\\-]", " ").trim().replaceAll("\\s+", " ");
        if (p.isEmpty() || p.length() > 48) return null;
        return "item:" + p.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    static int[] ivs(String s) {
        Matcher m = IVS.matcher(s);
        if (!m.find()) return null;
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) { v[i] = Integer.parseInt(m.group(i + 1)); if (v[i] > 31) return null; }
        return v;
    }

    static String clean(String s) { return FORMAT_CODES.matcher(s).replaceAll("").replace(' ', ' ').trim(); }
}

package dev.rareradar.core.hunt;

import dev.rareradar.core.rarity.PokemonObservation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a CobbleGalaxy "Pinned Hunts" overlay entry (plain strings already rendered by the server) into a
 * {@link HuntObjective}. Text parsing is best-effort: anything not recognised stays unknown.
 */
public final class OverlayHuntParser {
    private static final Pattern PROGRESS = Pattern.compile("(\\d{1,5})\\s*/\\s*(\\d{1,5})");
    private static final Pattern POINTS = Pattern.compile("(\\d[\\d,]*)\\s*(?:hunt\\s*points?|hp\\b|points?|pts)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile("(?:\\$|₽|pokédollars?\\s*)\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM])?|(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM])?\\s*(?:pokédollars?|pokedollars?|coins)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STARS = Pattern.compile("(★{1,3})|(\\d)\\s*(?:★|stars?)", Pattern.CASE_INSENSITIVE);

    private OverlayHuntParser() {}

    /** Identity of an overlay hunt; progress counters ("1/3") and timers are stripped so updates don't create duplicates. */
    static String key(String group, String title, String species) {
        String t = (title == null ? "" : title).replaceAll("\\d+\\s*/\\s*\\d+", "").replaceAll("\\d+\\s*[hms]\\b", "").replaceAll("\\s+", " ").trim();
        return ((group == null ? "" : group.trim()) + "|" + t + "|" + species).toLowerCase(Locale.ROOT);
    }

    public static HuntObjective parse(String group, String title, String speciesId, List<String> aspects,
                                      List<String> details, long remainingMillis, long now) {
        HuntObjective h = new HuntObjective();
        h.source = HuntObjective.Source.OVERLAY;
        h.group = group == null ? "" : group;
        h.title = title == null ? "" : title;
        h.species = speciesId == null || speciesId.isBlank() ? null
                : PokemonObservation.norm(speciesId.contains(":") ? speciesId.substring(speciesId.indexOf(':') + 1) : speciesId);
        if (aspects != null) h.aspects.addAll(aspects);
        if (details != null) h.details.addAll(details);
        h.createdAt = now;
        h.deadline = remainingMillis > 0 ? now + remainingMillis : null;
        h.lastSeen = now;
        h.overlayKey = key(h.group, h.title, h.species);

        StringBuilder all = new StringBuilder(h.title).append('\n').append(h.group);
        for (String d : h.details) all.append('\n').append(d);
        String text = all.toString();

        Matcher m = PROGRESS.matcher(text);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            if (b > 0 && a <= b) { h.progress = a; h.required = b; }
        }
        m = POINTS.matcher(text);
        if (m.find()) h.rewardHuntPoints = Double.parseDouble(m.group(1).replace(",", ""));
        m = MONEY.matcher(text);
        if (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(3);
            String suf = m.group(1) != null ? m.group(2) : m.group(4);
            double v = Double.parseDouble(num.replace(",", ""));
            if (suf != null) v *= suf.equalsIgnoreCase("k") ? 1_000 : 1_000_000;
            h.rewardMoney = v;
        }
        m = STARS.matcher(text);
        if (m.find()) h.stars = m.group(1) != null ? m.group(1).length() : Math.min(3, Integer.parseInt(m.group(2)));
        return h;
    }
}

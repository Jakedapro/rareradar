package dev.rareradar.core.daily;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual daily checklist. RareRadar only reminds; it never runs /dailyrewards, /calendar or /vote.
 * Items reset when the local date changes.
 */
public final class DailyChecklist {
    public static final String[] DEFAULT_ITEMS = {
            "Daily rewards claimed (/dailyrewards)",
            "Calendar rewards claimed (/calendar)",
            "Voting completed (/vote)",
            "Hunt objectives checked (/hunt)",
            "GTS prices reviewed",
            "Pokédex progress reviewed"
    };

    /** item → ISO date it was last completed. */
    public Map<String, String> lastDone = new LinkedHashMap<>();

    public DailyChecklist() { for (String s : DEFAULT_ITEMS) lastDone.putIfAbsent(s, ""); }

    public static String today(ZoneId zone, long nowMillis) {
        return java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString();
    }

    public boolean done(String item, String today) { return today.equals(lastDone.get(item)); }

    public void toggle(String item, String today) {
        lastDone.put(item, done(item, today) ? "" : today);
    }

    public int remaining(String today) {
        int n = 0;
        for (String k : lastDone.keySet()) if (!done(k, today)) n++;
        return n;
    }

    public void sanitize() {
        if (lastDone == null) lastDone = new LinkedHashMap<>();
        for (String s : DEFAULT_ITEMS) lastDone.putIfAbsent(s, "");
        lastDone.replaceAll((k, v) -> v == null || !v.matches("\\d{4}-\\d{2}-\\d{2}|") ? "" : v);
    }

    public static LocalDate parse(String d) { return d == null || d.isEmpty() ? null : LocalDate.parse(d); }
}

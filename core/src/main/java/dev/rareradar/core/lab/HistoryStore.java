package dev.rareradar.core.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.rareradar.core.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Keeps one summary per past session and compares a new session with the latest compatible one. */
public final class HistoryStore {
    public static final int MAX_KEPT = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private final Path dir;

    public HistoryStore(Path dir) { this.dir = dir; }

    public void save(Summary s) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(s.sessionId + ".json"), GSON.toJson(s), StandardCharsets.UTF_8);
            prune();
        } catch (IOException e) { Log.warn("Lab: cannot save history", e); }
    }

    /** Most recent earlier session with the same suite name, or null. */
    public Summary previousFor(String sessionId, String suite) {
        List<Path> files = list();
        for (int i = files.size() - 1; i >= 0; i--) {
            Path p = files.get(i);
            if (p.getFileName().toString().equals(sessionId + ".json")) continue;
            try {
                Summary s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Summary.class);
                if (s != null && suite.equals(s.suite) && s.sessionId != null && s.sessionId.compareTo(sessionId) < 0) return s;
            } catch (IOException | RuntimeException e) { Log.warn("Lab: unreadable history file " + p); }
        }
        return null;
    }

    private List<Path> list() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> st = Files.list(dir)) {
            List<Path> l = new ArrayList<>(st.filter(p -> p.getFileName().toString().endsWith(".json")).toList());
            l.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            return l;
        } catch (IOException e) { return List.of(); }
    }

    private void prune() {
        List<Path> l = list();
        for (int i = 0; i < l.size() - MAX_KEPT; i++) {
            try { Files.deleteIfExists(l.get(i)); } catch (IOException ignored) { /* best effort */ }
        }
    }

    /** Human-readable comparison lines. Only scenarios present (with counted tests) in both sessions are compared. */
    public static List<String> compare(Summary prev, Summary cur) {
        List<String> out = new ArrayList<>();
        if (prev == null) { out.add("No earlier session of suite '" + cur.suite + "' to compare with."); return out; }
        out.add(String.format(Locale.ROOT, "Previous: %s (mod %s, seed %d) · Current: %s (mod %s, seed %d)",
                prev.sessionId, prev.modVersion, prev.seed, cur.sessionId, cur.modVersion, cur.seed));
        if (prev.seed != cur.seed) out.add("Note: seeds differ, so individual destinations/targets differ; compare trends, not single runs.");
        int compared = 0;
        for (var e : cur.families.entrySet()) {
            Summary.Family c = e.getValue(), p = prev.families.get(e.getKey());
            if (p == null || Double.isNaN(c.passRate()) || Double.isNaN(p.passRate())) continue;
            compared++;
            double dp = (c.passRate() - p.passRate()) * 100;
            String small = (c.passed + c.failed < 10 || p.passed + p.failed < 10) ? " (small sample: <10 tests)" : "";
            out.add(String.format(Locale.ROOT, "%s success rate: previous %.0f%% (n=%d) → current %.0f%% (n=%d) · %s%.1f percentage points%s",
                    e.getKey(), p.passRate() * 100, p.passed + p.failed, c.passRate() * 100, c.passed + c.failed,
                    dp >= 0 ? "+" : "", dp, small));
            if (p.avgMs > 0 && c.avgMs > 0)
                out.add(String.format(Locale.ROOT, "  average test time: %.0f ms → %.0f ms (%+.0f%%)", p.avgMs, c.avgMs, (c.avgMs - p.avgMs) / p.avgMs * 100));
        }
        if (compared == 0) out.add("No scenario ran with counted results in both sessions, so nothing is comparable.");
        for (String k : new String[]{"fps_avg", "scanner_max_ms", "heap_slope_mb_per_min"}) {
            Object a = prev.performance.get(k), b = cur.performance.get(k);
            if (a instanceof Number x && b instanceof Number y) out.add(String.format(Locale.ROOT, "%s: %.2f → %.2f", k, x.doubleValue(), y.doubleValue()));
        }
        out.add(String.format(Locale.ROOT, "Failed tests: %d → %d · critical failures: %d → %d", prev.failed, cur.failed, prev.criticalFailures, cur.criticalFailures));
        return out;
    }
}

package dev.rareradar.core.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes one session's diagnostic package. All files are size-bounded: when a file passes its cap it is rotated to
 * {@code name.1} (one backup) and a fresh file is started, so a long or runaway session cannot fill the disk.
 * Disk work happens on the AsyncWriter thread.
 */
public final class SessionRecorder {
    public static final long DEFAULT_MAX_BYTES = 2_000_000;
    public static final int MAX_CSV_ROWS = 20_000;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private final Path dir;
    private final AsyncWriter io;
    private final String sessionId;
    private final boolean detail;
    private final long maxBytes;
    private final Map<String, Long> sizes = new HashMap<>();
    private int csvRows;
    private int rotations;
    private boolean csvHeader;

    public SessionRecorder(Path dir, String sessionId, AsyncWriter io, boolean detail, long maxBytes) {
        this.dir = dir;
        this.sessionId = sessionId;
        this.io = io;
        this.detail = detail;
        this.maxBytes = maxBytes;
        try { Files.createDirectories(dir); } catch (IOException e) { Log.warn("Lab: cannot create " + dir, e); }
    }

    public Path dir() { return dir; }
    public String sessionId() { return sessionId; }
    public int rotations() { return rotations; }

    public void event(Map<String, Object> m) {
        stamp(m);
        appendLine("events.jsonl", GSON.toJson(scrub(m)));
    }

    public void domain(String file, Map<String, Object> m) {
        if (!detail) return;
        stamp(m);
        appendLine(file, GSON.toJson(scrub(m)));
        // Domain lines are also summarised in events.jsonl by the controller; keep the files independent.
    }

    public void perf(PerfSample s, String scenario) {
        if (!detail || csvRows >= MAX_CSV_ROWS) return;
        if (!csvHeader) {
            csvHeader = true;
            appendLine("performance.csv", "t_ms,scenario,fps,lab_tick_ms,heap_used_mb,pokemon_entities,loaded_chunks,scanner_last_ms,scanner_max_ms,pathfind_ms");
        }
        csvRows++;
        appendLine("performance.csv", String.join(",", Long.toString(s.tMillis()), scenario == null ? "" : scenario, Integer.toString(s.fps()),
                f(s.labTickMs()), f(s.heapUsedMb()), Integer.toString(s.pokemonEntities()), Integer.toString(s.loadedChunks()),
                f(s.scannerLastMs()), f(s.scannerMaxMs()), f(s.pathfindMs())));
    }

    public void writeJson(String name, Object o) { writeText(name, PRETTY.toJson(o)); }

    public void writeText(String name, String text) {
        Path p = dir.resolve(name);
        io.submit(() -> {
            try { Files.writeString(p, text, StandardCharsets.UTF_8); }
            catch (IOException e) { Log.warn("Lab: write failed for " + p, e); }
        });
    }

    private void appendLine(String file, String line) {
        long add = line.length() + 1L;
        long cur = sizes.getOrDefault(file, 0L);
        Path p = dir.resolve(file);
        if (cur + add > maxBytes) {
            rotations++;
            sizes.put(file, 0L);
            cur = 0;
            Path bak = dir.resolve(file + ".1");
            io.submit(() -> {
                try { Files.move(p, bak, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { /* nothing to rotate */ }
            });
        }
        sizes.put(file, cur + add);
        io.submit(() -> {
            try { Files.writeString(p, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { Log.warn("Lab: append failed for " + p, e); }
        });
    }

    private void stamp(Map<String, Object> m) {
        m.putIfAbsent("ts", System.currentTimeMillis());
        m.putIfAbsent("session", sessionId);
    }

    private static Map<String, Object> scrub(Map<String, Object> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof String s ? Redact.clean(s) : v));
        return out;
    }

    private static String f(double d) { return Double.isNaN(d) ? "" : String.format(java.util.Locale.ROOT, "%.3f", d); }
}

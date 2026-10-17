package dev.rareradar.core.spawn;

import com.google.gson.reflect.TypeToken;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;
import dev.rareradar.core.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Seconds the player has spent observing each biome / time bucket. Encounter counts are only meaningful relative
 * to this exposure: a biome visited for 5 minutes cannot be compared with one visited for 5 hours.
 */
public final class ExposureLedger {
    private final Path file;
    private final AsyncWriter writer;
    private final Map<String, Double> seconds = new HashMap<>();

    public ExposureLedger(Path file, AsyncWriter writer) {
        this.file = file;
        this.writer = writer;
        if (file != null && Files.exists(file)) {
            try {
                Map<String, Double> m = JsonlStore.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Double>>() {}.getType());
                if (m != null) m.forEach((k, v) -> { if (v != null && Double.isFinite(v) && v >= 0) seconds.put(k, v); });
            } catch (Exception e) {
                Log.warn("Exposure ledger unreadable, starting fresh: " + e);
            }
        }
    }

    static String key(String biome, TimeBucket t) { return (biome == null ? "unknown" : biome) + "|" + t; }

    public void add(String biome, TimeBucket t, double secs) {
        if (!(secs > 0) || secs > 3600) return;
        seconds.merge(key(biome, t), secs, Double::sum);
    }

    public double hours(String biome, TimeBucket t) { return seconds.getOrDefault(key(biome, t), 0.0) / 3600; }

    public double hours(String biome) {
        double s = 0;
        for (TimeBucket t : TimeBucket.values()) s += seconds.getOrDefault(key(biome, t), 0.0);
        return s / 3600;
    }

    public double hoursForTime(TimeBucket t) {
        double s = 0;
        for (var e : seconds.entrySet()) if (e.getKey().endsWith("|" + t)) s += e.getValue();
        return s / 3600;
    }

    public double totalHours() {
        double s = 0;
        for (double v : seconds.values()) s += v;
        return s / 3600;
    }

    public java.util.Set<String> biomes() {
        java.util.Set<String> b = new java.util.TreeSet<>();
        for (String k : seconds.keySet()) b.add(k.substring(0, k.lastIndexOf('|')));
        return b;
    }

    public void save() {
        if (file == null) return;
        String json = JsonlStore.GSON.toJson(new HashMap<>(seconds));
        writer.submit(() -> {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.error("Could not save exposure ledger", e);
            }
        });
    }
}

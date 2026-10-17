package dev.rareradar.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.rareradar.core.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Append-only JSON Lines file for one record type. Reads tolerate corrupt lines (skipped, counted, original backed up);
 * rewrites are atomic (temp file + move) and keep a {@code .bak} of the previous version.
 */
public final class JsonlStore<T> {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private final Path file;
    private final Class<T> type;
    private final AsyncWriter writer;
    private int lastCorrupt;

    public JsonlStore(Path file, Class<T> type, AsyncWriter writer) {
        this.file = file;
        this.type = type;
        this.writer = writer;
    }

    public Path file() { return file; }

    /** Number of unreadable lines found by the last {@link #load()}. */
    public int lastCorruptLines() { return lastCorrupt; }

    /** Synchronous read; call at startup or from the IO thread, not every tick. */
    public List<T> load() {
        List<T> out = new ArrayList<>();
        lastCorrupt = 0;
        if (!Files.exists(file)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("Could not read " + file + "; starting with an empty dataset", e);
            backup("unreadable");
            return out;
        }
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.isEmpty()) continue;
            try {
                T v = GSON.fromJson(l, type);
                if (v != null) out.add(v); else lastCorrupt++;
            } catch (JsonParseException | IllegalStateException e) {
                lastCorrupt++;
                if (lastCorrupt <= 3) Log.warn(file.getFileName() + " line " + (i + 1) + " is corrupt and was skipped: " + e.getMessage());
            }
        }
        if (lastCorrupt > 0) {
            Log.warn(file.getFileName() + ": skipped " + lastCorrupt + " corrupt line(s); original kept as backup");
            backup("corrupt");
        }
        return out;
    }

    public void append(T record) {
        String line = GSON.toJson(record);
        writer.submit(() -> appendLines(List.of(line)));
    }

    public void appendAll(Collection<T> records) {
        List<String> lines = records.stream().map(GSON::toJson).toList();
        writer.submit(() -> appendLines(lines));
    }

    /** Replaces the whole file atomically (used for edits and deletions). */
    public void rewrite(Collection<T> records) {
        List<String> lines = records.stream().map(GSON::toJson).toList();
        writer.submit(() -> {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, lines, StandardCharsets.UTF_8);
                if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Log.error("Could not rewrite " + file, e);
            }
        });
    }

    private void appendLines(List<String> lines) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String l : lines) { w.write(l); w.newLine(); }
            }
        } catch (IOException e) {
            Log.error("Could not append to " + file, e);
        }
    }

    private void backup(String why) {
        try {
            Path b = file.resolveSibling(file.getFileName() + "." + why + "-" + System.currentTimeMillis() + ".bak");
            Files.copy(file, b);
        } catch (IOException e) {
            Log.warn("Could not back up " + file + ": " + e);
        }
    }
}

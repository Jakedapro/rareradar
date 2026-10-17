package dev.rareradar.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonlStoreTest {
    record Row(String name, int n) {}

    @TempDir Path dir;

    @Test void appendAndReload() {
        JsonlStore<Row> s = new JsonlStore<>(dir.resolve("a/rows.jsonl"), Row.class, AsyncWriter.inline());
        s.append(new Row("x", 1));
        s.appendAll(List.of(new Row("y", 2), new Row("z", 3)));
        assertEquals(List.of(new Row("x", 1), new Row("y", 2), new Row("z", 3)), s.load());
    }

    @Test void corruptLinesAreSkippedAndBackedUp() throws Exception {
        Path f = dir.resolve("rows.jsonl");
        Files.writeString(f, "{\"name\":\"ok\",\"n\":1}\n{garbage\n\n{\"name\":\"ok2\",\"n\":2}\n");
        JsonlStore<Row> s = new JsonlStore<>(f, Row.class, AsyncWriter.inline());
        List<Row> rows = s.load();
        assertEquals(2, rows.size());
        assertEquals(1, s.lastCorruptLines());
        try (var st = Files.list(dir)) {
            assertTrue(st.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test void rewriteIsAtomicAndKeepsBackup() throws Exception {
        Path f = dir.resolve("rows.jsonl");
        JsonlStore<Row> s = new JsonlStore<>(f, Row.class, AsyncWriter.inline());
        s.append(new Row("old", 1));
        s.rewrite(List.of(new Row("new", 2)));
        assertEquals(List.of(new Row("new", 2)), s.load());
        assertTrue(Files.readString(dir.resolve("rows.jsonl.bak")).contains("old"));
    }

    @Test void backgroundWriterFlushes() {
        try (AsyncWriter w = AsyncWriter.background(64)) {
            JsonlStore<Row> s = new JsonlStore<>(dir.resolve("bg.jsonl"), Row.class, w);
            for (int i = 0; i < 20; i++) s.append(new Row("r", i));
            w.flush(5000);
            assertEquals(20, s.load().size());
        }
    }
}

package dev.rareradar.core.stats;

import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class StatsModuleTest {
    static final long DAY = 86_400_000L;
    @TempDir Path dir;

    @Test void sessionLifecycleAndPersistence() {
        SessionTracker t = new SessionTracker(dir.resolve("s.jsonl"), AsyncWriter.inline());
        t.begin(DAY, "singleplayer");
        t.encounter(true, true, "minecraft:plains");
        t.encounter(false, false, "minecraft:plains");
        t.caught();
        t.reward(1_000);
        t.spend(200);
        t.end(DAY + 30 * 60_000);
        t.begin(2 * DAY, "x");
        t.end(2 * DAY + 1_000); // too short and empty → discarded
        SessionTracker again = new SessionTracker(dir.resolve("s.jsonl"), AsyncWriter.inline());
        assertEquals(1, again.history().size());
        SessionRecord r = again.history().get(0);
        assertEquals(2, r.encountered);
        assertEquals(1, r.shinyEncounters);
        assertEquals(800, r.realisedProfit(), 1e-9);
        assertEquals(30, r.minutes(), 1e-9);
    }

    @Test void crashedSessionIsRecoveredFromCheckpoint() {
        SessionTracker t = new SessionTracker(dir.resolve("c.jsonl"), AsyncWriter.inline());
        t.begin(DAY, "w");
        t.encounter(false, true, "b");
        t.touch(DAY + 600_000);
        t.checkpoint();
        // simulated crash: no end() call
        SessionTracker after = new SessionTracker(dir.resolve("c.jsonl"), AsyncWriter.inline());
        assertEquals(1, after.history().size());
        assertEquals(1, after.history().get(0).shinyEncounters);
        assertEquals(10, after.history().get(0).minutes(), 1e-9);
        assertEquals(1, new SessionTracker(dir.resolve("c.jsonl"), AsyncWriter.inline()).history().size(), "recovered only once");
    }

    @Test void dailyWeeklyAndTrend() {
        List<SessionRecord> s = new ArrayList<>();
        for (int d = 0; d < 10; d++) {
            SessionRecord r = new SessionRecord();
            r.start = d * DAY + 1; r.end = r.start + 3_600_000; r.rewardsEarned = 1_000 * (d + 1); r.encountered = 10;
            s.add(r);
        }
        var daily = Reports.daily(s, ZoneOffset.UTC);
        assertEquals(10, daily.size());
        assertEquals(1_000, daily.get(0).profitPerHour(), 1e-9);
        assertEquals(1_000, Reports.trend(daily, Reports.Period::realisedProfit), 1e-9);
        var weekly = Reports.weekly(s, ZoneOffset.UTC);
        assertTrue(weekly.size() >= 2 && weekly.size() <= 3);
        assertEquals(10, weekly.stream().mapToInt(Reports.Period::sessions).sum());
    }

    @Test void csvExportEscapesAndNeutralisesFormulas() {
        SessionRecord r = new SessionRecord();
        r.world = "=HYPERLINK(\"evil\")";
        r.biomes.add("a"); r.biomes.add("b");
        String csv = DataExport.toCsv(List.of(r), SessionRecord.class);
        assertTrue(csv.startsWith("id,"));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"evil\"\")\""));
        assertTrue(csv.contains("a|b"));
        assertTrue(DataExport.toJson(List.of(r)).contains("\"world\""));
    }

    @Test void backupRestoreRoundTripAndZipSlip() throws Exception {
        Path data = dir.resolve("data");
        Files.createDirectories(data.resolve("sub"));
        Files.writeString(data.resolve("market.jsonl"), "one");
        Files.writeString(data.resolve("sub/x.json"), "two");
        Path zip = dir.resolve("b.zip");
        assertEquals(2, DataExport.backup(data, zip));
        Files.writeString(data.resolve("market.jsonl"), "changed");
        assertEquals(2, DataExport.restore(zip, data));
        assertEquals("one", Files.readString(data.resolve("market.jsonl")));
        try (var s = Files.list(data.resolve("backups"))) { assertEquals(1, s.count()); }

        Path evil = dir.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../escaped.txt")); z.write(1); z.closeEntry();
        }
        assertEquals(0, DataExport.restore(evil, data));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }
}

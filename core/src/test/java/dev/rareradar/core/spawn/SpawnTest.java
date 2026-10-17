package dev.rareradar.core.spawn;

import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Confidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpawnTest {
    @TempDir Path dir;

    static EncounterRecord rec(String species, String biome, boolean shiny, TimeBucket t) {
        EncounterRecord r = new EncounterRecord();
        r.ts = 1; r.species = species; r.biome = biome; r.shiny = shiny; r.time = t;
        return r;
    }

    @Test void timeBuckets() {
        assertEquals(TimeBucket.DAY, TimeBucket.of(6000L));
        assertEquals(TimeBucket.NIGHT, TimeBucket.of(18000L));
        assertEquals(TimeBucket.DAWN, TimeBucket.of(23500L));
        assertEquals(TimeBucket.DUSK, TimeBucket.of(12500L));
        assertEquals(TimeBucket.UNKNOWN, TimeBucket.of(null));
        assertEquals(TimeBucket.DAY, TimeBucket.of(24000L + 6000));
    }

    @Test void encounterDedupeAndPersistence() {
        EncounterLog log = new EncounterLog(dir.resolve("e.jsonl"), AsyncWriter.inline());
        UUID id = UUID.randomUUID();
        assertTrue(log.record(id, rec("zubat", "cave", false, TimeBucket.NIGHT)));
        assertFalse(log.record(id, rec("zubat", "cave", false, TimeBucket.NIGHT)));
        assertTrue(log.record(UUID.randomUUID(), rec("zubat", "cave", true, TimeBucket.NIGHT)));
        EncounterLog reloaded = new EncounterLog(dir.resolve("e.jsonl"), AsyncWriter.inline());
        assertEquals(2, reloaded.all().size());
        assertSame(reloaded.all().get(0).species, reloaded.all().get(1).species, "repeated strings are shared");
    }

    @Test void exposureNormalisationAndPersistence() {
        ExposureLedger ex = new ExposureLedger(dir.resolve("x.json"), AsyncWriter.inline());
        ex.add("cave", TimeBucket.NIGHT, 3600);
        ex.add("cave", TimeBucket.DAY, 1800);
        ex.add("plains", TimeBucket.DAY, 600);
        ex.add("plains", TimeBucket.DAY, 99999); // rejected as implausible single tick
        ex.save();
        ExposureLedger again = new ExposureLedger(dir.resolve("x.json"), AsyncWriter.inline());
        assertEquals(1.5, again.hours("cave"), 1e-9);
        assertEquals(600 / 3600.0 + 0.5, again.hoursForTime(TimeBucket.DAY), 1e-9);
    }

    @Test void rateConfidenceCappedByExposure() {
        assertEquals(Confidence.LOW, EncounterStats.rate(100, 0.2, "").confidence());
        assertFalse(EncounterStats.rate(0, 0, "").known());
        var r = EncounterStats.rate(0, 10, "");
        assertEquals(0, r.value());
        assertTrue(r.high() > 0, "zero observed must still allow a positive true rate");
    }

    @Test void biomeRecommendation() {
        ExposureLedger ex = new ExposureLedger(null, AsyncWriter.inline());
        ex.add("cave", TimeBucket.NIGHT, 3600 * 1.0);
        for (int i = 0; i < 9; i++) ex.add("cave", TimeBucket.NIGHT, 3600);
        for (int i = 0; i < 10; i++) ex.add("plains", TimeBucket.DAY, 3600);
        ex.add("swamp", TimeBucket.DAY, 300);
        EncounterLog log = new EncounterLog(dir.resolve("e.jsonl"), AsyncWriter.inline());
        for (int i = 0; i < 40; i++) log.record(UUID.randomUUID(), rec("zubat", "cave", false, TimeBucket.NIGHT));
        for (int i = 0; i < 2; i++) log.record(UUID.randomUUID(), rec("zubat", "plains", false, TimeBucket.DAY));
        log.record(UUID.randomUUID(), rec("zubat", "swamp", false, TimeBucket.DAY));
        var s = BiomeRecommender.recommend(log.all(), ex, r -> r.species.equals("zubat"), "Zubat", 1.0);
        assertEquals("cave", s.get(0).biome());
        assertEquals("swamp", s.get(s.size() - 1).biome());
        assertTrue(Double.isNaN(s.get(s.size() - 1).perHour()));
        assertTrue(BiomeRecommender.headline(s, "Zubat").startsWith("Based on your previous encounters, cave"));
        var freq = EncounterStats.speciesFrequency(log.all());
        assertEquals(43L, freq.get(0).getValue());
    }

    @Test void teleportDetection() {
        TeleportDetector d = new TeleportDetector(64);
        assertFalse(d.update("overworld", 0, 0));
        assertFalse(d.update("overworld", 5, 5));
        assertTrue(d.update("overworld", 5000, 5));
        assertTrue(d.update("nether", 5000, 5));
        assertFalse(d.update("nether", 5001, 5));
    }

    @Test void parsesCobblemonSpawnPoolFormat() {
        String json = """
            {"enabled": true, "spawns": [
              {"id":"dratini-1","pokemon":"dratini","presets":["water"],"type":"pokemon","spawnablePositionType":"surface",
               "bucket":"rare","level":"5-30","weight":37.5,
               "condition":{"minSkyLight":0,"maxSkyLight":7,"biomes":["#cobblemon:is_overworld"],"maxY":32,"minY":-31,"timeRange":"night"},
               "anticondition":{"biomes":["#cobblemon:is_deep_dark"]}},
              {"id":"x","type":"npc","pokemon":"ignored"}
            ]}""";
        SpawnRuleDatabase db = new SpawnRuleDatabase();
        assertEquals(1, db.parse(json, "0147_dratini.json"));
        SpawnRule r = db.forSpecies("Dratini").get(0);
        assertEquals("rare", r.bucket);
        assertEquals(32, r.maxY);
        assertEquals("night", r.timeRange);
        assertTrue(r.biomeAllowed(Set.of("minecraft:river", "#cobblemon:is_overworld")));
        assertFalse(r.biomeAllowed(Set.of("#cobblemon:is_overworld", "#cobblemon:is_deep_dark")));
        assertTrue(r.describe().contains("time night"));
        assertEquals(0, db.parse("{\"enabled\":false,\"spawns\":[]}", "off"));
    }
}

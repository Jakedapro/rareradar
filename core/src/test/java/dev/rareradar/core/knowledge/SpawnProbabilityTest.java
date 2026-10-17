package dev.rareradar.core.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpawnProbabilityTest {
    static KnowledgeBase kb;

    @BeforeAll static void load() {
        kb = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(KnowledgeBaseTest.datasetDir()));
    }

    static SpawnEntry e(String species, String bucket, double weight, String cond) {
        return new SpawnEntry("x", species == null ? "empty" : "pokemon", species, species == null ? null : "cobblemon:" + species,
                null, List.of(), null, bucket, "5-10", null, weight, null, null, null, "grounded", cond, null,
                List.of(), List.of(), List.of(), null, true, List.of(), List.of(), "test", "verified");
    }

    @Test void bucketThenWeightMatchesHandCalculation() {
        // common: a 30, b 10 → a = 0.94·0.75, b = 0.94·0.25; rare: c → 0.005; uncommon/ultra/boss empty → misses.
        var shares = SpawnProbability.compute(List.of(e("a", "common", 30, null), e("b", "common", 10, null), e("c", "rare", 1, null)), SpawnProbability.Context.any("grounded"));
        assertEquals("cobblemon:a", shares.get(0).speciesId());
        assertEquals(0.94 * 0.75, shares.get(0).perAttempt(), 1e-12);
        assertEquals(0.005, shares.get(2).perAttempt(), 1e-12);
        assertEquals(1.0, shares.stream().mapToDouble(SpawnProbability.Share::share).sum(), 1e-12);
        assertEquals(0.005 / 0.945, shares.get(2).share(), 1e-12);
    }

    @Test void emptyEntriesAndTimeConditionsAreRespected() {
        var list = List.of(e("a", "common", 10, null), e(null, "common", 10, null), e("owl", "common", 10, "{\"timeRange\":\"night\"}"));
        var day = SpawnProbability.compute(list, SpawnProbability.Context.openSky("grounded", SpawnProbability.NOON));
        assertEquals(1, day.size());
        assertEquals(0.94 * 0.5, day.get(0).perAttempt(), 1e-12, "empty entry takes half the weight");
        var night = SpawnProbability.compute(list, SpawnProbability.Context.openSky("grounded", SpawnProbability.MIDNIGHT));
        assertEquals(2, night.size());
        assertEquals(0.5, night.get(0).share(), 1e-12);
        assertTrue(SpawnProbability.compute(list, SpawnProbability.Context.any("submerged")).isEmpty());
    }

    @Test void skyLightAndRainConditions() {
        var list = List.of(e("a", "common", 10, null), e("cave", "common", 10, "{\"canSeeSky\":false,\"maxSkyLight\":7}"),
                e("rain", "common", 10, "{\"isRaining\":true}"), e("ruin", "common", 10, "{\"structures\":[\"minecraft:ocean_ruin\"]}"));
        assertEquals(1, SpawnProbability.compute(list, SpawnProbability.Context.openSky("grounded", null)).size());
        var under = new SpawnProbability.Context("grounded", null, false, 0, true, null, false);
        assertEquals(3, SpawnProbability.compute(list, under).size());
        assertEquals(4, SpawnProbability.compute(list, new SpawnProbability.Context("grounded", null, false, 0, true, null, true)).size());
    }

    @Test void realDataSharesSumToOneAndRankBiomes() {
        var plains = SpawnProbability.inBiome(kb, "minecraft:plains", List.of(), SpawnProbability.Context.openSky("grounded", SpawnProbability.NOON));
        assertFalse(plains.isEmpty());
        assertEquals(1.0, plains.stream().mapToDouble(SpawnProbability.Share::share).sum(), 1e-9);
        var best = SpawnProbability.bestBiomes(kb, "pikachu", SpawnProbability.Context.openSky(null, SpawnProbability.NOON), 5);
        assertFalse(best.isEmpty(), "pikachu has forest spawns in 1.8.2");
        for (int i = 1; i < best.size(); i++) assertTrue(best.get(i - 1).share().share() >= best.get(i).share().share());
        // Structure-locked entries (Dratini's ocean-ruins spawns, weight 4500) are excluded by default.
        var dratini = SpawnProbability.bestBiomes(kb, "dratini", SpawnProbability.Context.openSky("submerged", SpawnProbability.NOON), 3);
        assertTrue(dratini.isEmpty() || dratini.get(0).share().share() < 0.2, () -> "dratini " + dratini);
        var special = SpawnProbability.bestBiomes(kb, "dratini", new SpawnProbability.Context("submerged", SpawnProbability.NOON, true, 15, false, null, true), 3);
        assertTrue(special.get(0).share().share() > 0.2);
        assertTrue(SpawnProbability.bestBiomes(kb, "not-a-pokemon", SpawnProbability.Context.any(null), 5).isEmpty());
    }
}

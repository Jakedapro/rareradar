package dev.rareradar.core.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads the real generated datasets (src/main/resources/assets/rareradar/knowledge) and checks facts that were
 * read by hand from the Cobblemon 1.8.2 data files (commit 643bf9068b51c829316429916373e5aa9864b8b9).
 */
class KnowledgeBaseTest {
    static final String REL = "src/main/resources/assets/rareradar/knowledge";
    static Path dir;
    static KnowledgeBase kb;

    /** Finds the dataset folder from core/ (gradle -p core) or the repo root; override with -Drareradar.knowledgeDir. */
    static Path datasetDir() {
        String prop = System.getProperty("rareradar.knowledgeDir");
        if (prop != null) return Path.of(prop);
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path c = p.resolve(REL);
            if (Files.isRegularFile(c.resolve(KnowledgeBase.META_FILE))) return c;
        }
        throw new IllegalStateException("dataset folder " + REL + " not found above " + Path.of("").toAbsolutePath());
    }

    @BeforeAll static void load() {
        dir = datasetDir();
        kb = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(dir));
    }

    @Test void loadsWithProvenance() {
        assertTrue(kb.isLoaded(), () -> "errors: " + kb.errors());
        assertEquals(1025, kb.allSpecies().size());
        assertEquals(5044, kb.allSpawns().size());
        assertEquals(192, kb.biomeTags().size());
        assertEquals(28, kb.presetNames().size());
        Provenance p = kb.provenance();
        assertEquals("1.8.2", p.version());
        assertEquals("643bf9068b51c829316429916373e5aa9864b8b9", p.commit());
        assertEquals("https://gitlab.com/cable-mc/cobblemon.git", p.repo());
        assertEquals("MPL-2.0", p.license());
        assertEquals("verified", p.confidence());
        assertTrue(p.commitVerified());
        assertNotNull(kb.generatedAt());
        Species b = kb.species("bulbasaur");
        assertEquals("common/src/main/resources/data/cobblemon/species/generation1/bulbasaur.json", b.sourceFile());
        assertEquals("verified", b.confidence());
        assertEquals("common/src/main/resources/data/cobblemon/spawn_pool_world/0147_dratini.json",
                kb.spawnsFor("dratini").get(0).sourceFile());
    }

    @Test void bulbasaurFacts() {
        Species b = kb.byDex(1);
        assertSame(b, kb.species("cobblemon:bulbasaur"));
        assertSame(b, kb.species("Bulbasaur"));
        assertEquals("Bulbasaur", b.name());
        assertEquals("grass", b.primaryType());
        assertEquals("poison", b.secondaryType());
        assertEquals(new Stats(45, 49, 49, 65, 65, 45, 318), b.baseStats());
        assertEquals(45, b.catchRate());
        assertEquals(64, b.baseExperienceYield());
        assertEquals("medium_slow", b.experienceGroup());
        assertEquals(0.875, b.maleRatio());
        assertEquals(List.of("monster", "grass"), b.eggGroups());
        assertEquals(List.of("overgrow", "h:chlorophyll"), b.abilities());
        assertEquals(List.of("chlorophyll"), b.hiddenAbilities());
        Stats ev = kb.evYield("bulbasaur");
        assertEquals(1, ev.specialAttack());
        assertEquals(1, ev.total());
        assertEquals(Set.of("gen1", "starter"), b.labels());
        assertEquals(Boolean.TRUE, b.implemented());
        assertTrue(b.moves().contains("1:tackle"));
        assertTrue(b.moves().contains("egg:curse"));
        assertTrue(b.moves().contains("tm:bodyslam"));
    }

    @Test void evolutionsBothDirections() {
        List<Evolution> from = kb.evolutionsFrom("bulbasaur");
        assertEquals(1, from.size());
        Evolution e = from.get(0);
        assertEquals("ivysaur", e.result());
        assertEquals("cobblemon:ivysaur", e.resultSpeciesId());
        assertEquals("level_up", e.variant());
        assertEquals(List.of("level"), e.requirementVariants());
        assertEquals(16, e.requirements().getAsJsonArray().get(0).getAsJsonObject().get("minLevel").getAsInt());
        assertTrue(kb.evolutionsTo("ivysaur").stream().anyMatch(x -> x.fromSpeciesId().equals("cobblemon:bulbasaur")));

        // Dratini -> Dragonair at level 30
        Evolution d = kb.evolutionsFrom("dratini").get(0);
        assertEquals("cobblemon:dragonair", d.resultSpeciesId());
        assertTrue(d.requirementsJson().contains("\"minLevel\":30"));

        // Pikachu's alolan Raichu evolution carries its raw biome requirement; its plain Raichu one has none
        List<Evolution> pika = kb.evolutionsFrom("pikachu");
        Evolution alolan = pika.stream().filter(x -> "raichu alolan".equals(x.result())).findFirst().orElseThrow();
        assertEquals("cobblemon:raichu", alolan.resultSpeciesId());
        assertEquals("alolan", alolan.resultProperties());
        assertEquals(List.of("biome"), alolan.requirementVariants());
        assertTrue(alolan.requirementsJson().contains("#cobblemon:evolution/regional/pikachu_alolabiome"));
        Evolution plain = pika.stream().filter(x -> "raichu".equals(x.result())).findFirst().orElseThrow();
        assertNull(plain.requirementsJson(), "file has no requirements key -> null, not invented");

        // Eevee evolves into several species
        assertTrue(kb.evolutionsFrom("eevee").size() >= 3);
        assertTrue(kb.evolutionsTo("vaporeon").stream().anyMatch(x -> x.fromSpeciesId().equals("cobblemon:eevee")));
    }

    @Test void formsAndOverrides() {
        Species v = kb.species("vulpix");
        assertEquals(37, v.dex());
        Form alola = v.form("Alola");
        assertNotNull(alola);
        assertSame(alola, v.form("alolan"), "lookup by aspect");
        assertEquals(List.of("alolan"), alola.aspects());
        assertEquals("ice", alola.primaryType());
        assertArrayEquals(new String[]{"ice", null}, v.typesFor("alola"));
        assertArrayEquals(new String[]{"fire", null}, v.typesFor(null));
        assertEquals(List.of("snowcloak", "h:snowwarning"), alola.abilities());
        assertNull(alola.baseStats(), "not overridden in the file");
        assertEquals(v.baseStats(), v.baseStatsFor("alola"), "falls back to species stats");
        assertEquals(190, v.catchRateFor("alola"));
        Evolution fe = alola.evolutions().get(0);
        assertEquals("ninetales alolan", fe.result());
        assertEquals("Alola", fe.fromForm());
        assertEquals("\"cobblemon:ice_stone\"", fe.requiredContextJson());

        Species mewtwo = kb.species("mewtwo");
        assertEquals(List.of("Mega-X", "Mega-Y"), mewtwo.forms().stream().map(Form::name).toList());
    }

    @Test void legendaryMythicalAndOtherLabels() {
        Species mewtwo = kb.byDex(150);
        assertEquals("Mewtwo", mewtwo.name());
        assertTrue(mewtwo.isLegendary());
        assertEquals(3, mewtwo.catchRate());
        assertEquals(new Stats(106, 110, 90, 154, 90, 130, 680), mewtwo.baseStats());
        assertEquals(3, mewtwo.evYield().specialAttack());
        assertEquals(-1.0, mewtwo.maleRatio(), "genderless is -1 in the data");

        Species mew = kb.byDex(151);
        assertTrue(mew.isMythical());
        assertFalse(mew.isLegendary());
        assertEquals(3, mew.evYield().hp());

        Species nihilego = kb.species("nihilego");
        assertEquals(793, nihilego.dex());
        assertTrue(nihilego.isUltraBeast());
        assertNull(nihilego.implemented(), "no implemented key in the 1.8.2 file -> null");

        Species tusk = kb.species("Great Tusk");
        assertEquals(984, tusk.dex());
        assertTrue(tusk.isParadox());
        assertEquals(30, tusk.catchRate());

        assertEquals(122, kb.species("Mr. Mime").dex());
        assertSame(kb.species("mrmime"), kb.species("cobblemon:mrmime"));

        // Label counts as they appear in the 1.8.2 data (species level)
        assertEquals(71, kb.legendaries().size());
        assertEquals(23, kb.mythicals().size());
        assertEquals(11, kb.withLabel("ultra_beast").size());
        assertEquals(20, kb.withLabel("paradox").size());
        assertTrue(kb.legendaries().contains(mewtwo));
        assertTrue(kb.mythicals().contains(mew));
        assertTrue(kb.legendaries().stream().allMatch(s -> s.labels().contains("legendary")));
    }

    @Test void spawnEntriesAndBiomeMatching() {
        SpawnEntry p1 = kb.spawnsFor("pikachu").stream().filter(s -> "pikachu-1".equals(s.id())).findFirst().orElseThrow();
        assertEquals("cobblemon:pikachu", p1.speciesId());
        assertEquals("uncommon", p1.bucket());
        assertEquals("16-32", p1.level());
        assertEquals(21.82, p1.weight());
        assertEquals("grounded", p1.positionType());
        assertEquals(List.of("#cobblemon:is_forest"), p1.biomes());
        assertEquals(List.of("#cobblemon:is_beach", "#cobblemon:is_spooky", "#cobblemon:is_tropical_island"), p1.excludedBiomes());
        assertEquals(List.of("natural"), p1.presets());
        assertTrue(p1.weightMultipliersJson().contains("isThundering"));
        assertEquals(8, p1.condition().getAsJsonObject().get("minSkyLight").getAsInt());
        assertNotNull(kb.preset("natural"));
        assertTrue(kb.preset("natural").contains("#cobblemon:natural"));

        SpawnEntry alola = kb.spawnsFor("pikachu").stream().filter(s -> "pikachu-alola-2".equals(s.id())).findFirst().orElseThrow();
        assertEquals("region_bias=alola", alola.properties());

        // minecraft:cherry_grove is listed directly in #cobblemon:is_forest
        assertTrue(kb.biomeRefs("minecraft:cherry_grove", List.of()).contains("#cobblemon:is_forest"));
        assertTrue(kb.spawnsInBiome("minecraft:cherry_grove", List.of()).contains(p1));
        assertTrue(kb.speciesSpawnableInBiome("cherry_grove", List.of()).contains("cobblemon:pikachu"));
        // a vanilla forest only matches through its runtime tag #minecraft:is_forest (listed in is_forest)
        assertFalse(kb.spawnsInBiome("minecraft:forest", List.of()).contains(p1));
        assertTrue(kb.spawnsInBiome("minecraft:forest", List.of("minecraft:is_forest")).contains(p1));

        // Transitive tags: frozen_ocean -> #cobblemon:is_frozen_ocean -> #cobblemon:is_ocean
        Set<String> refs = kb.biomeRefs("minecraft:frozen_ocean", List.of());
        assertTrue(refs.containsAll(Set.of("minecraft:frozen_ocean", "#cobblemon:is_frozen_ocean", "#cobblemon:is_ocean")));
        List<SpawnEntry> frozen = kb.spawnsInBiome("minecraft:frozen_ocean", List.of());
        SpawnEntry d7 = byId("dratini", "dratini-7"), d8 = byId("dratini", "dratini-8");
        assertEquals(4500.0, d7.weight());
        assertEquals(List.of("water", "ocean_ruins"), d7.presets());
        assertEquals(List.of("#cobblemon:is_frozen_ocean"), d8.excludedBiomes());
        assertTrue(frozen.contains(d7), "is_ocean condition, no anticondition");
        assertFalse(frozen.contains(d8), "anticondition #cobblemon:is_frozen_ocean");

        // dark_forest is in both is_magical (condition) and is_spooky (anticondition) of dratini-9
        assertFalse(kb.spawnsInBiome("minecraft:dark_forest", List.of()).contains(byId("dratini", "dratini-9")));
    }

    @Test void herdsAreIndexedForEachMember() {
        SpawnEntry herd = kb.spawnsFor("ivysaur").stream().filter(s -> "bulbasaur-herd-1".equals(s.id())).findFirst().orElseThrow();
        assertEquals("pokemon-herd", herd.type());
        assertNull(herd.speciesId());
        assertEquals(List.of("cobblemon:bulbasaur", "cobblemon:ivysaur", "cobblemon:venusaur"), herd.herdSpeciesIds());
        assertEquals("ultra-rare", herd.bucket());
        assertEquals(30.0, herd.weight());
        assertEquals("5-53", herd.levelRange());
        assertEquals(4, herd.maxHerdSize());
        assertEquals(List.of("#cobblemon:is_jungle"), herd.biomes());
        assertTrue(herd.sourceFile().endsWith("spawn_pool_world/herds/0001_bulbasaur_herd.json"));
        assertTrue(kb.spawnsFor("bulbasaur").contains(herd));
    }

    /** Known issues allowed in the 1.8.2 data. Keep this list explicit; currently none. */
    static final List<String> ALLOWED_PROBLEMS = List.of();

    @Test void validationIsClean() {
        List<String> problems = kb.validate();
        problems.removeAll(ALLOWED_PROBLEMS);
        assertEquals(List.of(), problems);
        for (Species s : kb.allSpecies()) {
            assertTrue(s.dex() > 0, s.id());
            assertTrue(s.baseStats().complete(), s.id());
        }
        assertEquals(1025, kb.allSpecies().stream().mapToInt(Species::dex).distinct().count());
    }

    @Test void validationReportsUnknownSpeciesAndDuplicates(@TempDir Path tmp) throws Exception {
        String species = "{\"schemaVersion\":1,\"species\":["
                + "{\"id\":\"cobblemon:a\",\"nationalPokedexNumber\":1,\"name\":\"A\",\"primaryType\":\"normal\",\"catchRate\":45,"
                + "\"baseStats\":{\"hp\":1,\"attack\":1,\"defence\":1,\"special_attack\":1,\"special_defence\":1,\"speed\":1},"
                + "\"evYield\":{\"hp\":1,\"attack\":0,\"defence\":0,\"special_attack\":0,\"special_defence\":0,\"speed\":0}},"
                + "{\"id\":\"cobblemon:b\",\"nationalPokedexNumber\":1,\"name\":\"B\",\"primaryType\":\"normal\"}]}";
        String spawns = "{\"schemaVersion\":1,\"presets\":{},\"unresolvedPresets\":[],\"spawns\":["
                + "{\"id\":\"x-1\",\"type\":\"pokemon\",\"pokemon\":\"zzz\",\"species\":\"zzz\",\"bucket\":\"common\",\"weight\":1.0,"
                + "\"biomes\":[\"#cobblemon:nope\"],\"sourceFile\":\"f.json\"}]}";
        String tags = "{\"schemaVersion\":1,\"tags\":[]}";
        writeSet(tmp, species, spawns, tags);
        KnowledgeBase small = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(tmp));
        assertTrue(small.isLoaded(), () -> small.errors().toString());
        List<String> problems = small.validate();
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("duplicate dex number 1")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown species zzz")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("cobblemon:b: missing/incomplete baseStats")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("cobblemon:b: missing catchRate")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown Cobblemon biome tag #cobblemon:nope")), problems.toString());
    }

    @Test void corruptOrMissingInputFailsSoft(@TempDir Path tmp) throws Exception {
        // 1. Missing everything
        KnowledgeBase none = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(tmp.resolve("absent")));
        assertTrue(none.isEmpty());
        assertFalse(none.errors().isEmpty());
        assertNull(none.species("bulbasaur"));
        assertEquals(List.of(), none.spawnsFor("bulbasaur"));
        assertEquals(List.of(), none.speciesSpawnableInBiome("minecraft:forest", List.of()));
        assertFalse(none.validate().isEmpty());

        // 2. Real meta + real species/tags, but spawns replaced by garbage (not gzip)
        Path bad = tmp.resolve("bad");
        Files.createDirectories(bad);
        for (String f : List.of(KnowledgeBase.META_FILE, KnowledgeBase.SPECIES_FILE, KnowledgeBase.BIOME_TAGS_FILE))
            Files.copy(dir.resolve(f), bad.resolve(f));
        Files.writeString(bad.resolve(KnowledgeBase.SPAWNS_FILE), "this is not gzip");
        KnowledgeBase k1 = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(bad));
        assertTrue(k1.isEmpty());
        assertTrue(k1.errors().get(0).contains(KnowledgeBase.SPAWNS_FILE), k1.errors().toString());

        // 3. Valid gzip with truncated JSON
        Files.write(bad.resolve(KnowledgeBase.SPAWNS_FILE), gzip("{\"schemaVersion\":1,\"spawns\":[{\"id\":"));
        KnowledgeBase k2 = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(bad));
        assertTrue(k2.isEmpty());
        assertFalse(k2.errors().isEmpty());

        // 4. Byte-flipped real file: sha256 mismatch against meta.json (or gzip CRC error)
        byte[] real = Files.readAllBytes(dir.resolve(KnowledgeBase.SPAWNS_FILE));
        real[real.length / 2] ^= 0x5A;
        Files.write(bad.resolve(KnowledgeBase.SPAWNS_FILE), real);
        KnowledgeBase k3 = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(bad));
        assertTrue(k3.isEmpty());
        assertFalse(k3.errors().isEmpty());

        // 5. Source that throws
        KnowledgeBase k4 = KnowledgeBase.load(name -> { throw new IOException("boom"); });
        assertTrue(k4.isEmpty());
        assertTrue(k4.errors().get(0).contains("boom"));

        // 6. Unsupported schema version
        Path v2 = tmp.resolve("v2");
        Files.createDirectories(v2);
        Files.writeString(v2.resolve(KnowledgeBase.META_FILE), "{\"schemaVersion\":2}");
        KnowledgeBase k5 = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(v2));
        assertTrue(k5.errors().get(0).contains("schemaVersion 2"), k5.errors().toString());
    }

    @Test void loadsFromClasspathStyleSource() {
        // Same loader path as the mod: a name -> InputStream function (here backed by the files).
        KnowledgeBase viaFn = KnowledgeBase.load(name -> Files.newInputStream(dir.resolve(name)));
        assertTrue(viaFn.isLoaded());
        KnowledgeBase missing = KnowledgeBase.loadFromClasspath(new ClassLoader(null) {});
        assertTrue(missing.isEmpty());
        assertTrue(missing.errors().get(0).contains("not found"));
    }

    // ---------------------------------------------------------------- helpers

    static SpawnEntry byId(String species, String id) {
        return kb.spawnsFor(species).stream().filter(s -> id.equals(s.id())).findFirst().orElseThrow();
    }

    static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return bo.toByteArray();
    }

    static void writeSet(Path d, String species, String spawns, String tags) throws Exception {
        byte[] a = gzip(species), b = gzip(spawns), c = gzip(tags);
        Files.write(d.resolve(KnowledgeBase.SPECIES_FILE), a);
        Files.write(d.resolve(KnowledgeBase.SPAWNS_FILE), b);
        Files.write(d.resolve(KnowledgeBase.BIOME_TAGS_FILE), c);
        Files.writeString(d.resolve(KnowledgeBase.META_FILE), "{\"schemaVersion\":1,\"datasets\":{"
                + "\"" + KnowledgeBase.SPECIES_FILE + "\":{\"sha256\":\"" + sha(a) + "\"},"
                + "\"" + KnowledgeBase.SPAWNS_FILE + "\":{\"sha256\":\"" + sha(b) + "\"},"
                + "\"" + KnowledgeBase.BIOME_TAGS_FILE + "\":{\"sha256\":\"" + sha(c) + "\"}}}");
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}

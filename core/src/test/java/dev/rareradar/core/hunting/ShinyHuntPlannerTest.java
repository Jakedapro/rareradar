package dev.rareradar.core.hunting;

import dev.rareradar.core.spawn.EncounterRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShinyHuntPlannerTest {
    static EncounterRecord enc(String species, String biome, boolean shiny) {
        EncounterRecord r = new EncounterRecord();
        r.species = species; r.biome = biome; r.shiny = shiny;
        return r;
    }

    @Test void poissonHelpers() {
        assertEquals(Math.log(2), ShinyHuntPlanner.hoursForProbability(1, 0.5), 1e-12);
        assertEquals(1 - Math.exp(-2), ShinyHuntPlanner.probabilityWithin(1, 2), 1e-12);
        assertTrue(Double.isNaN(ShinyHuntPlanner.hoursForProbability(0, 0.5)));
    }

    @Test void observedShareBeatsModelAndRatesRank() {
        List<EncounterRecord> log = new ArrayList<>();
        for (int i = 0; i < 100; i++) log.add(enc(i < 20 ? "gible" : "rattata", "minecraft:desert", false));
        for (int i = 0; i < 100; i++) log.add(enc(i < 2 ? "gible" : "rattata", "minecraft:badlands", false));
        var opts = ShinyHuntPlanner.rank("gible", log, Map.of("minecraft:desert", 1.0, "minecraft:badlands", 1.0),
                Map.of("minecraft:badlands", 0.5, "minecraft:mesa", 0.3), 8192);
        assertEquals("minecraft:desert", opts.get(0).where());
        assertEquals(ShinyHuntPlanner.ShareSource.OBSERVED, opts.get(0).shareSource());
        assertEquals(100 * 0.2 / 8192, opts.get(0).targetShinyPerHour(), 1e-12);
        // The model said 50% in badlands, but only 2 of 100 encounters were gible: the blend follows the evidence.
        var bad = opts.stream().filter(o -> o.where().equals("minecraft:badlands")).findFirst().orElseThrow();
        assertEquals(ShinyHuntPlanner.ShareSource.BLENDED, bad.shareSource());
        assertEquals((20 * 0.5 + 2) / 120.0, bad.share(), 1e-12);
        // A biome never visited has a share but no measured rate and is listed after the measured ones.
        var mesa = opts.get(opts.size() - 1);
        assertEquals("minecraft:mesa", mesa.where());
        assertFalse(mesa.rateKnown());
    }

    @Test void dryStreakIsMemoryless() {
        List<EncounterRecord> log = new ArrayList<>();
        log.add(enc("a", "x", true));
        for (int i = 0; i < 8192; i++) log.add(enc("a", "x", false));
        var s = ShinyHuntPlanner.streak(log, null, 8192);
        assertEquals(8192, s.sinceLastShiny());
        assertEquals(1, s.shiniesSeen());
        assertEquals(Math.exp(-1), s.pDrySpell(), 1e-4);
        assertTrue(s.note().contains("still 8192"));
    }
}

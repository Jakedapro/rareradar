package dev.rareradar.core.training;

import dev.rareradar.core.knowledge.KnowledgeBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real Cobblemon 1.8.2 dataset. */
class EvTrainingPlannerTest {
    static KnowledgeBase kb;

    @BeforeAll static void load() {
        Path d = Path.of("").toAbsolutePath();
        while (d != null && !Files.isDirectory(d.resolve("src/main/resources/assets/rareradar/knowledge"))) d = d.getParent();
        Path dir = d.resolve("src/main/resources/assets/rareradar/knowledge");
        kb = KnowledgeBase.load(name -> new FileInputStream(dir.resolve(name).toFile()));
        assertTrue(kb.isLoaded(), kb.errors().toString());
    }

    @Test void ranksByEvsPerHour() {
        // Bulbasaur gives 1 Sp. Atk EV (verified in the knowledge-base tests); Mew gives 3 HP EVs.
        var plan = EvTrainingPlanner.plan(kb, EvTrainingPlanner.Stat.SPECIAL_ATTACK, 96, 252, 0, Map.of("bulbasaur", 30.0, "mew", 100.0));
        assertEquals(156, plan.remaining());
        assertEquals(1, plan.candidates().size(), "Mew yields no Sp. Atk EVs");
        assertEquals(30.0, plan.candidates().get(0).evsPerHour(), 1e-9);
        assertEquals(156 / 30.0, plan.hoursForBest(), 1e-9);
        var hp = EvTrainingPlanner.plan(kb, EvTrainingPlanner.Stat.HP, 0, 252, 0, Map.of("mew", 10.0));
        assertEquals(30.0, hp.candidates().get(0).evsPerHour(), 1e-9);
    }

    @Test void respectsCaps() {
        var p = EvTrainingPlanner.plan(kb, EvTrainingPlanner.Stat.SPEED, 0, 300, 500, Map.of());
        assertEquals(252, p.target());
        assertEquals(10, p.remaining(), "510 total cap leaves only 10");
        assertTrue(Double.isNaN(p.hoursForBest()), "no candidates → unknown time");
        assertEquals(0, EvTrainingPlanner.plan(kb, EvTrainingPlanner.Stat.SPEED, 252, 252, 0, Map.of()).hoursForBest());
    }
}

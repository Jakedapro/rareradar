package dev.rareradar.core.misc;

import dev.rareradar.core.daily.DailyChecklist;
import dev.rareradar.core.daily.VoteParty;
import dev.rareradar.core.dex.DexPlanner;
import dev.rareradar.core.dex.DexTier;
import dev.rareradar.core.inventory.BallPolicy;
import dev.rareradar.core.inventory.ResourceBook;
import dev.rareradar.core.mega.BossEncounter;
import dev.rareradar.core.mega.MegaPlanner;
import dev.rareradar.core.spawn.SpawnRule;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    static BossEncounter boss(boolean won, boolean drop) {
        BossEncounter b = new BossEncounter();
        b.outcome = won ? BossEncounter.Outcome.WON : BossEncounter.Outcome.LOST;
        b.megaStoneDropped = drop;
        return b;
    }

    @Test void bossDropRateFromOwnLogOnly() {
        assertFalse(MegaPlanner.dropRate(List.of()).known());
        assertFalse(MegaPlanner.dropRate(List.of(boss(false, false))).known());
        var r = MegaPlanner.dropRate(List.of(boss(true, true), boss(true, false), boss(true, true), boss(true, true)));
        assertEquals(3.5 / 5, r.value(), 1e-9);
        assertTrue(r.low() < r.value() && r.high() > r.value());
        var w = MegaPlanner.winsForSpecificStone(List.of(boss(true, true), boss(true, true)), 10);
        assertTrue(w.known() && w.value() > 10);
    }

    @Test void megaRankingPrefersOwnedStones() {
        MegaPlanner m = new MegaPlanner();
        m.ownedStones.add("garchompite");
        var r = m.rank(Map.of("garchomp", "garchompite", "lucario", "lucarionite"), Map.of("lucario", 6));
        assertEquals("garchomp", r.get(0).species());
        assertTrue(r.get(0).why().contains("Mega Bracelet still needed"));
    }

    @Test void dexTiersAndSuggestions() {
        DexPlanner d = new DexPlanner();
        for (int i = 0; i < 100; i++) d.universe.add("s" + i);
        for (int i = 0; i < 24; i++) d.markCaught("s" + i, false);
        d.tiers.add(new DexTier("all", "t1", "10%", 10, true, false));
        d.tiers.add(new DexTier("all", "t2", "25%", 25, false, false));
        d.tiers.add(new DexTier("all", "t3", "50%", 50, false, false));
        assertEquals(24.0, d.percent(), 1e-9);
        var next = d.nextTier().orElseThrow();
        assertEquals("t2", next.getKey().id());
        assertEquals(1, next.getValue());
        SpawnRule common = new SpawnRule(); common.bucket = "common";
        var s = d.suggest(sp -> sp.equals("s50") ? 3.0 : null, sp -> sp.equals("s60") ? List.of(common) : List.of(), 3);
        assertEquals("s50", s.get(0).species());
        assertEquals("s60", s.get(1).species());
        assertTrue(new DexPlanner().nextTier().isEmpty());
    }

    @Test void dailyChecklistResetsByDate() {
        DailyChecklist c = new DailyChecklist();
        String d1 = DailyChecklist.today(ZoneOffset.UTC, 0), d2 = DailyChecklist.today(ZoneOffset.UTC, 86_400_000L);
        assertEquals(6, c.remaining(d1));
        c.toggle(DailyChecklist.DEFAULT_ITEMS[0], d1);
        assertEquals(5, c.remaining(d1));
        assertEquals(6, c.remaining(d2));
        c.lastDone.put("x", "garbage");
        c.sanitize();
        assertEquals("", c.lastDone.get("x"));
    }

    @Test void votePartyWindow() {
        VoteParty v = new VoteParty();
        v.start = 1_000_000;
        assertTrue(v.active(1_000_000 + 60_000));
        assertFalse(v.active(1_000_000 + 31 * 60_000));
        v.shinies = 3;
        assertEquals(6.0, v.shinyRate(30).value(), 1e-9);
        assertEquals(3.0, v.observedMultiplier(30, 2.0), 1e-9);
        assertTrue(Double.isNaN(v.observedMultiplier(30, 0)));
    }

    @Test void resourceValuationAndWarnings() {
        ResourceBook b = ResourceBook.defaults();
        b.setCount("cobblemon:master_ball", 2);
        b.setCount("cobblemon:ultra_ball", 10);
        b.resources.get("cobblemon:ultra_ball").unitValue = 800;
        b.resources.get("cobblemon:ultra_ball").reserve = 16;
        double[] v = b.totalValue();
        assertEquals(8_000, v[0], 1e-9);
        assertEquals(1, v[1], 1e-9); // master ball has no price → unknown, not zero
        assertEquals(1, b.warnings().size());
        assertNotNull(ResourceBook.activityWarning("Breeding", 5_000, 1_000));
        assertNull(ResourceBook.activityWarning("Breeding", Double.NaN, 1_000));
    }

    @Test void ballPolicyProtectsMasterBalls() {
        BallPolicy p = new BallPolicy();
        String[] hotbar = {"master_ball", "ultra_ball", "poke_ball", null, null, null, null, null, null};
        Map<String, Integer> counts = Map.of("master_ball", 5, "ultra_ball", 20, "poke_ball", 30);
        assertEquals("poke_ball", p.choose(hotbar, counts, 1, false).ball());
        assertEquals("ultra_ball", p.choose(hotbar, counts, 10, false).ball());
        assertEquals("master_ball", p.choose(hotbar, counts, 10, true).ball());
        p.reserves.put("poke_ball", 30);
        p.reserves.put("ultra_ball", 20);
        assertNull(p.choose(hotbar, counts, 1, false).ball());
    }
}

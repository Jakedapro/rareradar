package dev.rareradar.core.rarity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RarityEngineTest {
    private static RarityEngine engine(RarityConfig c, Watchlist w) { return new RarityEngine(c, w, null, null); }

    @Test void precedenceMatchesOriginalRadar() {
        RarityEngine e = engine(new RarityConfig(), new Watchlist());
        assertEquals(Category.MYTHICAL, e.classify(PokemonObservation.of("mew").labels("mythical", "legendary")).primary());
        assertEquals(Category.LEGENDARY, e.classify(PokemonObservation.of("mewtwo").labels("legendary").shiny(true)).primary());
        assertEquals(Category.ULTRA_BEAST, e.classify(PokemonObservation.of("nihilego").labels("ultra_beast")).primary());
        assertEquals(Category.PARADOX, e.classify(PokemonObservation.of("great_tusk").labels("paradox")).primary());
        assertEquals(Category.SHINY, e.classify(PokemonObservation.of("pidgey").shiny(true)).primary());
        assertFalse(e.classify(PokemonObservation.of("pidgey")).qualifies());
    }

    @Test void ownedPokemonNeverQualify() {
        PokemonObservation o = PokemonObservation.of("mew").labels("mythical");
        o.owned = true;
        assertFalse(engine(new RarityConfig(), new Watchlist()).classify(o).qualifies());
    }

    @Test void scoreFollowsConfiguredWeights() {
        RarityConfig c = new RarityConfig();
        c.wShiny = 1; c.wLegendary = 10; c.wMythical = 0; c.wSpecial = 0; c.wDemand = 0;
        RarityEngine e = engine(c, new Watchlist());
        assertEquals(11, e.classify(PokemonObservation.of("mewtwo").labels("legendary").shiny(true)).score(), 1e-9);
    }

    @Test void unknownDemandContributesZeroAndIsFlagged() {
        RarityConfig c = new RarityConfig();
        RarityResult r = new RarityEngine(c, new Watchlist(), o -> Double.NaN, null)
                .classify(PokemonObservation.of("pidgey").shiny(true));
        assertFalse(r.demandKnown());
        assertEquals(c.wShiny, r.score(), 1e-9);
        RarityResult r2 = new RarityEngine(c, new Watchlist(), o -> 0.5, null)
                .classify(PokemonObservation.of("pidgey").shiny(true));
        assertTrue(r2.demandKnown());
        assertEquals(c.wShiny + 0.5 * c.wDemand, r2.score(), 1e-9);
    }

    @Test void disabledTiersAreIgnored() {
        RarityConfig c = new RarityConfig();
        c.shiny = false;
        assertFalse(engine(c, new Watchlist()).classify(PokemonObservation.of("pidgey").shiny(true)).qualifies());
    }

    @Test void watchlistMatchesSpeciesFormAndShiny() {
        Watchlist w = new Watchlist();
        w.add(new Watchlist.Entry("Dratini", null, false, 7, "dex"));
        w.add(new Watchlist.Entry("vulpix", "alolan", true, 9, ""));
        RarityEngine e = engine(new RarityConfig(), w);
        RarityResult r = e.classify(PokemonObservation.of("dratini"));
        assertEquals(Category.WATCHLIST, r.primary());
        assertEquals(7, r.watchPriority());
        assertFalse(e.classify(PokemonObservation.of("vulpix").form("alolan")).qualifies());
        assertTrue(e.classify(PokemonObservation.of("vulpix").form("alolan").shiny(true)).qualifies());
        assertFalse(e.classify(PokemonObservation.of("vulpix").shiny(true).labels()).categories().contains(Category.WATCHLIST));
    }

    @Test void watchlistImportExportRoundTrip() {
        Watchlist w = new Watchlist();
        w.add(new Watchlist.Entry("Gible", null, true, 4, "shiny"));
        String json = w.exportJson();
        Watchlist w2 = new Watchlist();
        assertEquals(1, w2.importJson(json, true));
        assertEquals("gible", w2.entries().get(0).species);
        assertEquals(1, w2.importJson("[{\"species\":\"\"},{\"species\":\"Eevee\",\"priority\":99}]", false));
        assertEquals(10, w2.entries().get(1).priority);
        assertThrows(IllegalArgumentException.class, () -> w2.importJson("{nope", false));
    }

    @Test void rankerPrefersWatchlistThenUtility() {
        RarityEngine e = engine(new RarityConfig(), new Watchlist());
        RarityResult legend = e.classify(PokemonObservation.of("mewtwo").labels("legendary"));
        RarityResult shiny = e.classify(PokemonObservation.of("pidgey").shiny(true));
        List<TargetRanker.Candidate<String>> list = new ArrayList<>(List.of(
                new TargetRanker.Candidate<>("far legend", legend, 1000),
                new TargetRanker.Candidate<>("near shiny", shiny, 5)));
        list.sort(TargetRanker.comparator(64));
        assertEquals("near shiny", list.get(0).ref());
        Watchlist w = new Watchlist();
        w.add(new Watchlist.Entry("pidgey", null, false, 3, ""));
        RarityResult watched = engine(new RarityConfig(), w).classify(PokemonObservation.of("pidgey"));
        list.add(new TargetRanker.Candidate<>("watched", watched, 500));
        list.sort(TargetRanker.comparator(64));
        assertEquals("watched", list.get(0).ref());
    }
}

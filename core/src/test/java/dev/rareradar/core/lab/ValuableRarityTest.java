package dev.rareradar.core.lab;

import dev.rareradar.core.rarity.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValuableRarityTest {
    private static RarityEngine engine(RarityConfig cfg, double demand) {
        return new RarityEngine(cfg, new Watchlist(), o -> demand, null);
    }

    @Test
    void highDemandSpeciesQualifiesWithoutBeingShinyOrLegendary() {
        RarityResult r = engine(new RarityConfig(), 0.8).classify(PokemonObservation.of("dratini"));
        assertTrue(r.qualifies());
        assertEquals(Category.VALUABLE, r.primary());
    }

    @Test
    void lowOrUnknownDemandDoesNotQualify() {
        assertFalse(engine(new RarityConfig(), 0.3).classify(PokemonObservation.of("rattata")).qualifies());
        assertFalse(engine(new RarityConfig(), Double.NaN).classify(PokemonObservation.of("rattata")).qualifies(), "no market data must never make a Pokémon 'valuable'");
    }

    @Test
    void canBeSwitchedOffAndShinyStillWins() {
        RarityConfig off = new RarityConfig();
        off.valuable = false;
        assertFalse(engine(off, 0.9).classify(PokemonObservation.of("dratini")).qualifies());
        RarityResult shiny = engine(new RarityConfig(), 0.9).classify(PokemonObservation.of("dratini").shiny(true));
        assertEquals(Category.SHINY, shiny.primary());
    }
}

package dev.rareradar.core.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static dev.rareradar.core.market.GtsTextParser.Status.*;
import static dev.rareradar.core.market.PriceObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Formats below are representative of common GTS plugin broadcasts and chest menus; they are NOT verified
 * CobbleGalaxy samples (its wording is undocumented). Real samples logged by the mod should be added here.
 */
class GtsTextParserTest {
    static final Predicate<String> SPECIES = Set.of("pikachu", "gible", "garchomp", "mrmime", "dratini", "eevee")::contains;

    static GtsTextParser.Result a(String s) { return GtsTextParser.parseAnnouncement(s, SPECIES); }

    @Test void typicalListingAnnouncements() {
        var r = a("[GTS] Steve has listed a Shiny Gible for $12,500!");
        assertEquals(PARSED, r.status());
        assertEquals("gible", r.subject());
        assertTrue(r.shiny());
        assertEquals(12_500, r.price(), 1e-9);
        assertEquals(LISTING, r.kind());

        r = a("§6GTS §8» §fAlex listed §ePikachu §7(Lv. 25) §ffor §a$5k");
        assertEquals("pikachu", r.subject());
        assertEquals(5_000, r.price(), 1e-9);
        assertFalse(r.shiny());

        r = a("GTS: Jordan is selling Dratini 31/31/31/0/31/31 for 1,250,000 PokéDollars");
        assertEquals("dratini", r.subject());
        assertArrayEquals(new int[]{31, 31, 31, 0, 31, 31}, r.ivs());
        assertEquals(1_250_000, r.price(), 1e-9);

        r = a("[GTS] Sam listed Mr. Mime for $900");
        assertEquals("mrmime", r.subject());
    }

    @Test void itemAnnouncements() {
        var r = a("[GTS] Steve has listed 16x Rare Candy for $8,000");
        assertEquals(PARSED, r.status());
        assertFalse(r.pokemon());
        assertEquals("item:rare_candy", r.subject());
        assertEquals(LISTING, r.kind());
        r = a("[GTS] Kim listed a Master Ball for $1.5m");
        assertEquals("item:master_ball", r.subject());
        assertEquals(1_500_000, r.price(), 1e-9);
    }

    @Test void listingIsNeverClassifiedAsSale() {
        assertEquals(LISTING, a("[GTS] Steve listed Gible for $500").kind());
        assertEquals(LISTING, a("[GTS] Steve listed Gible for $500 — buy it before it's sold!").kind(), "listing words win");
        assertEquals(LISTING, a("GTS » Gible $500").kind(), "no verb → asking price");
        assertEquals(SALE, a("[GTS] Alex bought Steve's Gible for $500").kind());
        assertEquals(SALE, a("[GTS] Your Gible sold for $500").kind());
    }

    @Test void rejectsAndIgnoresInsteadOfGuessing() {
        assertEquals(IGNORED, a("Steve: anyone selling gible for $500?").status(), "player chat without GTS marker");
        assertEquals(REJECTED, a("[GTS] Steve listed Gible").status());
        assertEquals("no price found", a("[GTS] Steve listed Gible").reason());
        assertEquals("more than one price in message", a("[GTS] Gible was $500, now $400").reason());
        assertEquals(REJECTED, a("[GTS] Welcome to the GTS! Prices from $1").status());
        assertEquals(PARSED, a("[GTS] Gible $500 and again $500").status(), "repeated identical amount is fine");
    }

    @Test void numberFormats() {
        assertEquals(12_500, GtsTextParser.singlePrice("$12.500"), 1e-9);
        assertEquals(12.5, GtsTextParser.singlePrice("$12.5"), 1e-9);
        assertEquals(2_500, GtsTextParser.singlePrice("2.5k coins"), 1e-9);
        assertEquals(700, GtsTextParser.singlePrice("₽700"), 1e-9);
        assertTrue(Double.isNaN(GtsTextParser.singlePrice("Lv. 25 Gible")));
    }

    @Test void chestMenuEntries() {
        var r = GtsTextParser.parseMenuEntry("§bShiny Gible", List.of("§7Seller: Steve", "§7Level: 30", "§aPrice: $12,500", "§7Click to buy"), "gible", SPECIES);
        assertEquals(PARSED, r.status());
        assertEquals("gible", r.subject());
        assertTrue(r.shiny());
        assertEquals(12_500, r.price(), 1e-9);
        assertEquals(LISTING, r.kind());

        r = GtsTextParser.parseMenuEntry("Garchomp", List.of("IVs: 31/31/31/31/31/0", "Price: 250,000"), null, SPECIES);
        assertEquals("garchomp", r.subject());
        assertEquals(250_000, r.price(), 1e-9);
        assertArrayEquals(new int[]{31, 31, 31, 31, 31, 0}, r.ivs());

        r = GtsTextParser.parseMenuEntry("Rare Candy x4", List.of("Cost: 3k"), null, SPECIES);
        assertEquals("item:rare_candy_x4", r.subject());
        assertEquals(3_000, r.price(), 1e-9);

        assertEquals("no labelled price line in lore", GtsTextParser.parseMenuEntry("Next Page", List.of("Click to continue"), null, SPECIES).reason());
        assertEquals("no labelled price line in lore", GtsTextParser.parseMenuEntry("Gible", List.of("Level 25", "$500 earned"), null, SPECIES).reason(),
                "an unlabelled amount (could be anything) is not treated as the price");
    }

    @Test void captureStats() {
        CaptureStats s = new CaptureStats("chat");
        s.detected = 3; s.parsed = 2; s.saved = 1; s.duplicates = 1;
        s.reject("no price found", "[GTS] x");
        assertTrue(s.summary().contains("detected 3, parsed 2, rejected 1"));
        assertTrue(s.changedSinceLastLog());
        assertFalse(s.changedSinceLastLog());
        assertEquals(1, s.recentRejectedSamples().size());
    }
}

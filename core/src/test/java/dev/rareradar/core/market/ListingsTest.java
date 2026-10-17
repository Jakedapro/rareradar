package dev.rareradar.core.market;

import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.rareradar.core.market.PriceObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Based on rows captured on the live CobbleGalaxy server (exported market.csv, alpha.2). */
class ListingsTest {
    static final long T = 1792242560338L;
    @TempDir Path dir;

    static PriceObservation obs(String sp, boolean shiny, double price, long ts, String note) {
        PriceObservation p = PriceObservation.of(sp, shiny, LISTING, price, ts);
        p.note = note;
        return p;
    }

    @Test void chatAnnouncementOfAScreenCardCountsOnce() {
        var chat = obs("irontreads", true, 300_000, T - 2_700, "chat:LISTING:irontreads:300000:2987070 (GTS chat)");
        var card = obs("irontreads", true, 300_000, T, "gts:LISTING:aefdd7c7-f64d-4144-8691-d822fe66bff1:300000").ivs(7, 31, 25, 10, 6, 30);
        var out = Listings.distinct(List.of(chat, card));
        assertEquals(1, out.size());
        assertSame(card, out.get(0), "the screen card (with IVs) is kept");
    }

    @Test void reannouncementCountsOnceButDistinctCardsStay() {
        var a = obs("item:shadow_crate_key", false, 5_000_000, T, "chat:LISTING:item:shadow_crate_key:5000000:2987070 (GTS chat)");
        var b = obs("item:shadow_crate_key", false, 5_000_000, T + 200_000, "chat:LISTING:item:shadow_crate_key:5000000:2987071 (GTS chat)");
        assertEquals(1, Listings.distinct(List.of(a, b)).size());
        var d1 = obs("deino", false, 5_000, T, "gts:LISTING:d4ce853f:5000");
        var d2 = obs("deino", false, 5_000, T, "gts:LISTING:e1203105:5000");
        assertEquals(2, Listings.distinct(List.of(d1, d2)).size(), "two real listings at the same price");
        var later = obs("item:shadow_crate_key", false, 5_000_000, T + 2 * Listings.WINDOW_MS, "chat:LISTING:x (GTS chat)");
        assertEquals(2, Listings.distinct(List.of(a, later)).size(), "a re-listing days later counts again");
    }

    @Test void statsUseDistinctListingsAndSalesAreUntouched() {
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        db.add(obs("swellow", true, 50_000, T, "chat:LISTING:swellow:50000:2987070 (GTS chat)"));
        db.add(obs("swellow", true, 50_000, T + 1, "gts:LISTING:23bc123a:50000"));
        PriceObservation sale = PriceObservation.of("swellow", true, SALE, 50_000, T + 2);
        sale.note = "chat:SALE:swellow:50000:1 (GTS chat)";
        db.add(sale);
        MarketStats s = db.stats(new MarketKey("swellow", null, true), T + 10);
        assertEquals(1, s.listings());
        assertEquals(1, s.sales());
    }

    @Test void cobblemonIdsAreNormalised() {
        PriceObservation p = PriceObservation.of("azumarill", false, LISTING, 5_000, T);
        p.ability = "cobblemon.ability.hugepower";
        p.nature = "cobblemon:impish";
        p.normalize();
        assertEquals("hugepower", p.ability);
        assertEquals("impish", p.nature);
    }
}

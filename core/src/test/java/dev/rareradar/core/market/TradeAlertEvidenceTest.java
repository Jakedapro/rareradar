package dev.rareradar.core.market;

import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.rareradar.core.market.PriceObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Assisted-trading alerts must rest on confirmed sales, never on asking prices, and deduct the fee. */
class TradeAlertEvidenceTest {
    static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    OpportunityFinder finder(MarketDatabase db) {
        return new OpportunityFinder(db, new Valuator(db, new ValuationConfig()), new ValuationConfig());
    }

    @Test void askingPricesAloneNeverTriggerAnAlert() {
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        for (int i = 0; i < 40; i++) db.add(PriceObservation.of("gible", false, LISTING, 100_000 + i * 1_000, NOW - i * 3_600_000L));
        PriceObservation cheap = PriceObservation.of("gible", false, LISTING, 1_000, NOW);
        db.add(cheap);
        assertNull(finder(db).checkListing(cheap, NOW));
        assertTrue(Double.isNaN(finder(db).netAfterFee(cheap, 5, NOW)));
    }

    @Test void oneOrTwoSalesAreNotEnough() {
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        db.add(PriceObservation.of("gible", false, SALE, 50_000, NOW - DAY));
        db.add(PriceObservation.of("gible", false, SALE, 52_000, NOW - 2 * DAY));
        PriceObservation cheap = PriceObservation.of("gible", false, LISTING, 1_000, NOW);
        db.add(cheap);
        assertNull(finder(db).checkListing(cheap, NOW));
    }

    @Test void confirmedSalesTriggerAlertAndFeeIsDeducted() {
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        for (int i = 0; i < 10; i++) db.add(PriceObservation.of("gible", false, SALE, 10_000, NOW - i * DAY));
        PriceObservation cheap = PriceObservation.of("gible", false, LISTING, 6_000, NOW);
        db.add(cheap);
        OpportunityFinder f = finder(db);
        assertNotNull(f.checkListing(cheap, NOW));
        // 10 000 × (1 − 0.10) − 6 000 = 3 000
        assertEquals(3_000, f.netAfterFee(cheap, 10, NOW), 1e-6);
        // A 45% fee wipes the margin: 10 000 × 0.55 − 6 000 < 0 → the mod suppresses the alert.
        assertTrue(f.netAfterFee(cheap, 45, NOW) < 0);
        assertTrue(Double.isNaN(f.netAfterFee(cheap, Double.NaN, NOW)), "unknown fee is not assumed to be zero");
    }

    @Test void saleRecordsAreNotSalesWhenTheyAreAsks() {
        // A sale must be recorded as SALE; many listings plus a cheap listing never become sale evidence.
        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        for (int i = 0; i < 10; i++) db.add(PriceObservation.of("axew", false, LISTING, 10_000, NOW - i * DAY));
        assertEquals(0, db.stats(new MarketKey("axew", null, false), NOW).sales());
    }
}

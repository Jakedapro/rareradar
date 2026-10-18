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

    MarketDatabase salesDb(String sp, int n, double price, long spacing) {
        MarketDatabase db = new MarketDatabase(dir.resolve(sp + ".jsonl"), AsyncWriter.inline());
        for (int i = 0; i < n; i++) db.add(PriceObservation.of(sp, false, SALE, price + (i % 3 - 1) * price * 0.05, NOW - i * spacing));
        return db;
    }

    @Test void assessmentUsesLowerQuartileAfterFeeAndLiquidity() {
        MarketDatabase db = salesDb("gible", 20, 10_000, DAY / 2); // 20 sales in 10 days: liquid
        OpportunityFinder f = finder(db);
        PriceObservation cheap = PriceObservation.of("gible", false, LISTING, 5_000, NOW);
        var a = f.assess(cheap, 10, NOW);
        assertEquals(OpportunityFinder.Verdict.BUY_CANDIDATE, a.verdict());
        assertTrue(a.conservativeNet() > 0 && a.conservativeNet() < a.expectedNet());
        // Close to typical price: profits only at the median, not at the lower quartile.
        PriceObservation near = PriceObservation.of("gible", false, LISTING, 8_800, NOW);
        assertEquals(OpportunityFinder.Verdict.MARGINAL, f.assess(near, 10, NOW).verdict());
        assertEquals(OpportunityFinder.Verdict.NO_MARGIN, f.assess(PriceObservation.of("gible", false, LISTING, 9_900, NOW), 10, NOW).verdict());
        assertEquals(OpportunityFinder.Verdict.MARGINAL, f.assess(cheap, Double.NaN, NOW).verdict(), "unknown fee caps the verdict");
    }

    @Test void rareSalesAreIlliquid() {
        MarketDatabase db = salesDb("axew", 5, 10_000, 20 * DAY); // 5 sales over ~80 days
        var a = finder(db).assess(PriceObservation.of("axew", false, LISTING, 3_000, NOW), 10, NOW);
        assertEquals(OpportunityFinder.Verdict.ILLIQUID, a.verdict());
    }
}

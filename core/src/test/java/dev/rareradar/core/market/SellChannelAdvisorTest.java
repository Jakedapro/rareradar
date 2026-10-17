package dev.rareradar.core.market;

import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.rareradar.core.market.PriceObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class SellChannelAdvisorTest {
    static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    MarketStats stats(String sp, double sts, double sale, int sales, int listings, double listPrice) {
        MarketDatabase db = new MarketDatabase(dir.resolve(sp + ".jsonl"), AsyncWriter.inline());
        if (Double.isFinite(sts)) db.add(PriceObservation.of(sp, false, STS, sts, NOW));
        for (int i = 0; i < sales; i++) db.add(PriceObservation.of(sp, false, SALE, sale, NOW - i * DAY));
        for (int i = 0; i < listings; i++) db.add(PriceObservation.of(sp, false, LISTING, listPrice, NOW - i * DAY));
        return db.stats(new MarketKey(sp, null, false), NOW);
    }

    @Test void gtsWinsOnlyWithConfirmedSalesAfterFee() {
        var c = SellChannelAdvisor.compare(stats("gible", 1_000, 5_000, 5, 0, 0), 10);
        assertEquals(SellChannelAdvisor.Verdict.GTS, c.verdict());
        assertEquals(4_500, c.gtsNet(), 1e-6);
    }

    @Test void askingPricesNeverCount() {
        var c = SellChannelAdvisor.compare(stats("axew", 1_000, 0, 0, 50, 99_999), 10);
        assertEquals(SellChannelAdvisor.Verdict.UNKNOWN, c.verdict());
        assertTrue(c.reason().contains("asking prices don't count"));
    }

    @Test void feeCanFlipTheVerdictAndUnknownFeeIsNotZero() {
        assertEquals(SellChannelAdvisor.Verdict.STS, SellChannelAdvisor.compare(stats("a", 1_000, 1_100, 5, 0, 0), 20).verdict());
        assertEquals(SellChannelAdvisor.Verdict.EITHER, SellChannelAdvisor.compare(stats("b", 1_000, 1_100, 5, 0, 0), 5).verdict());
        assertEquals(SellChannelAdvisor.Verdict.UNKNOWN, SellChannelAdvisor.compare(stats("c", 1_000, 5_000, 5, 0, 0), Double.NaN).verdict());
    }
}

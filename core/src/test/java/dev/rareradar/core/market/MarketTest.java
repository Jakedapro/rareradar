package dev.rareradar.core.market;

import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.util.Confidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.rareradar.core.market.PriceObservation.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketTest {
    static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    MarketDatabase db() { return new MarketDatabase(dir.resolve("market.jsonl"), AsyncWriter.inline()); }

    @Test void listingsAndSalesAreSeparated() {
        MarketDatabase db = db();
        db.add(PriceObservation.of("gible", false, LISTING, 1_000_000, NOW - DAY));
        db.add(PriceObservation.of("gible", false, LISTING, 20_000, NOW - DAY));
        db.add(PriceObservation.of("gible", false, SALE, 15_000, NOW - 2 * DAY));
        MarketStats s = db.stats(new MarketKey("gible", null, false), NOW);
        assertEquals(2, s.listings());
        assertEquals(1, s.sales());
        assertEquals(15_000, s.saleMedian());
        assertEquals(1_000_000, s.listMax());
        assertEquals(20_000, s.listMin());
    }

    @Test void absurdListingDoesNotDefineValue() {
        MarketDatabase db = db();
        db.add(PriceObservation.of("gible", false, LISTING, 1_000_000, NOW));
        for (int i = 0; i < 5; i++) db.add(PriceObservation.of("gible", false, SALE, 10_000 + i * 100, NOW - i * DAY));
        Valuator v = new Valuator(db, new ValuationConfig());
        var val = v.value(PokemonObservation.of("gible"), NOW);
        assertEquals(10_200, val.total().value(), 1e-6);
        assertTrue(val.total().high() < 20_000);
    }

    @Test void listingsOnlyGivesLowConfidenceLowerQuartile() {
        MarketDatabase db = db();
        for (double p : new double[]{10_000, 20_000, 30_000, 40_000, 50_000}) db.add(PriceObservation.of("axew", false, LISTING, p, NOW));
        var e = new Valuator(db, new ValuationConfig()).bucketValue(new MarketKey("axew", null, false), NOW);
        assertEquals(Confidence.LOW, e.confidence());
        assertEquals(20_000, e.value(), 1e-6);
        assertTrue(e.basis().contains("asking"));
    }

    @Test void stsPayoutIsAFloor() {
        MarketDatabase db = db();
        db.add(PriceObservation.of("axew", false, LISTING, 500, NOW));
        db.add(PriceObservation.of("axew", false, STS, 2_000, NOW));
        var e = new Valuator(db, new ValuationConfig()).bucketValue(new MarketKey("axew", null, false), NOW);
        assertEquals(2_000, e.low(), 1e-6);
        assertTrue(e.value() >= 2_000);
    }

    @Test void unknownWhenNoData() {
        var val = new Valuator(db(), new ValuationConfig()).value(PokemonObservation.of("mew"), NOW);
        assertFalse(val.total().known());
        assertEquals("insufficient data", val.method());
    }

    @Test void shinyComponentLearnedFromOtherSpecies() {
        MarketDatabase db = db();
        // dratini: shiny 3x base; we ask about shiny gible which has only non-shiny data.
        db.add(PriceObservation.of("dratini", false, SALE, 10_000, NOW));
        db.add(PriceObservation.of("dratini", true, SALE, 30_000, NOW));
        for (int i = 0; i < 3; i++) db.add(PriceObservation.of("gible", false, SALE, 5_000, NOW - i * DAY));
        Valuator v = new Valuator(db, new ValuationConfig());
        var val = v.value(PokemonObservation.of("gible").shiny(true), NOW);
        assertTrue(val.total().known());
        assertEquals(15_000, val.total().value(), 1e-6);
        assertTrue(val.method().startsWith("component"));
        assertTrue(val.total().confidence().ordinal() <= Confidence.MEDIUM.ordinal());
    }

    @Test void ivPremiumRegression() {
        MarketDatabase db = db();
        int[][] ivSets = {{31,31,31,31,31,31},{31,31,31,31,31,0},{31,31,31,0,0,0},{0,0,0,0,0,0}};
        double[] prices = {60_000, 50_000, 30_000, 0 + 1};
        for (int i = 0; i < 4; i++) db.add(PriceObservation.of("dratini", false, SALE, prices[i], NOW).ivs(ivSets[i]));
        double slope = new Valuator(db, new ValuationConfig()).ivPremiumPerPerfect("dratini", null, false);
        assertTrue(slope > 9_000 && slope < 11_000, "slope " + slope);
    }

    @Test void trendAndLiquidity() {
        MarketDatabase db = db();
        for (int w = 0; w < 6; w++) db.add(PriceObservation.of("gible", false, SALE, 10_000 * Math.pow(1.1, w), NOW - (5 - w) * 7 * DAY));
        MarketStats s = db.stats(new MarketKey("gible", null, false), NOW);
        assertEquals(0.10, s.trendPerWeek(), 1e-6);
        assertTrue(s.trendFromSales());
        assertEquals(6 / 5.0, s.salesPerWeek(), 1e-6);
    }

    @Test void persistenceRoundTripAndValidation() {
        MarketDatabase db = db();
        db.add(PriceObservation.of("Gible", true, SALE, 123, NOW).form(" "));
        assertThrows(IllegalArgumentException.class, () -> db.add(PriceObservation.of("gible", false, SALE, -5, NOW)));
        assertThrows(IllegalArgumentException.class, () -> db.add(PriceObservation.of("gible", false, SALE, 5, NOW).ivs(1, 2, 3, 4, 5, 99)));
        MarketDatabase again = db();
        assertEquals(1, again.size());
        assertEquals("gible", again.all().get(0).species);
        assertNull(again.all().get(0).form);
        assertTrue(again.remove(again.all().get(0).id));
        assertEquals(0, db().size());
    }

    @Test void csvRoundTripWithErrors() {
        PriceObservation p = PriceObservation.of("vulpix", true, SALE, 42_000, NOW).form("alolan").ivs(31, 31, 31, 31, 31, 0);
        p.note = "bought, then \"flipped\"";
        String csv = MarketCsv.export(List.of(p)) + "bad,row\n" + NOW + ",gible,,false,LISTING,-1,,,,\n";
        MarketCsv.Parsed parsed = MarketCsv.parse(csv);
        assertEquals(1, parsed.rows().size());
        assertEquals(2, parsed.errors().size());
        PriceObservation q = parsed.rows().get(0);
        assertEquals("alolan", q.form);
        assertArrayEquals(new int[]{31, 31, 31, 31, 31, 0}, q.ivs);
        assertEquals(p.note, q.note);
    }

    @Test void opportunitiesAreFlaggedNotPromised() {
        MarketDatabase db = db();
        for (int i = 0; i < 10; i++) db.add(PriceObservation.of("gible", false, SALE, 10_000 + 100 * i, NOW - i * DAY));
        db.add(PriceObservation.of("gible", false, LISTING, 3_000, NOW));
        OpportunityFinder f = new OpportunityFinder(db, new Valuator(db, new ValuationConfig()), new ValuationConfig());
        var ops = f.marketWide(NOW);
        var under = ops.stream().filter(o -> o.type() == Opportunity.Type.POTENTIALLY_UNDERVALUED).findFirst().orElseThrow();
        assertTrue(under.explanation().contains("not guaranteed"));
        assertTrue(ops.stream().anyMatch(o -> o.type() == Opportunity.Type.STRONG_DEMAND));

        PokemonObservation mine = PokemonObservation.of("gible").ivs(31, 31, 31, 31, 0, 0);
        mine.owned = true;
        assertEquals(Opportunity.Type.BREEDING_CANDIDATE, f.advise(mine, NOW).type());
        assertEquals(Opportunity.Type.CONSIDER_KEEPING, f.advise(PokemonObservation.of("mew"), NOW).type());
    }

    @Test void demandIsNaNWithoutDataAndBounded() {
        MarketDatabase db = db();
        Valuator v = new Valuator(db, new ValuationConfig());
        assertTrue(Double.isNaN(v.demand("gible", null, NOW)));
        for (int i = 0; i < 7; i++) db.add(PriceObservation.of("gible", false, SALE, 1, NOW - i * DAY));
        double d = v.demand("gible", null, NOW);
        assertTrue(d > 0.4 && d < 1, "d=" + d);
    }
}

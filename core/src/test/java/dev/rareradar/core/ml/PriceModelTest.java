package dev.rareradar.core.ml;

import dev.rareradar.core.market.PriceObservation;
import dev.rareradar.core.rarity.PokemonObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PriceModelTest {
    @Test void refusesSmallData() {
        PriceModel m = new PriceModel();
        m.train(List.of(PriceObservation.of("a", false, PriceObservation.Kind.SALE, 10, 1)), 1);
        assertFalse(m.usable());
        assertFalse(m.predict(PokemonObservation.of("a")).known());
    }

    @Test void learnsIvEffectThatBucketMedianMisses() {
        Random r = new Random(3);
        List<PriceObservation> data = new ArrayList<>();
        String[] sp = {"gible", "dratini", "axew", "larvitar"};
        double[] base = {10_000, 20_000, 5_000, 15_000};
        for (int i = 0; i < 300; i++) {
            int s = r.nextInt(4);
            int perfect = r.nextInt(7);
            int[] ivs = new int[6];
            for (int k = 0; k < 6; k++) ivs[k] = k < perfect ? 31 : r.nextInt(20);
            boolean shiny = r.nextInt(5) == 0;
            double price = base[s] * Math.exp(0.35 * perfect) * (shiny ? 3 : 1) * Math.exp(r.nextGaussian() * 0.1);
            data.add(PriceObservation.of(sp[s], shiny, PriceObservation.Kind.SALE, price, 1).ivs(ivs));
        }
        PriceModel m = new PriceModel();
        var metrics = m.train(data, 42);
        assertTrue(metrics.usable(), metrics.summary());
        assertTrue(metrics.modelMae() < metrics.baselineMae());
        var hi = m.predict(PokemonObservation.of("gible").ivs(31, 31, 31, 31, 31, 31));
        var lo = m.predict(PokemonObservation.of("gible").ivs(0, 0, 0, 0, 0, 0));
        assertTrue(hi.value() > 3 * lo.value(), hi.value() + " vs " + lo.value());
        assertTrue(hi.low() < hi.value() && hi.value() < hi.high());
    }

    @Test void keepsHeuristicWhenModelDoesNotHelp() {
        Random r = new Random(5);
        List<PriceObservation> data = new ArrayList<>();
        for (int i = 0; i < 60; i++) data.add(PriceObservation.of("s" + (i % 3), false, PriceObservation.Kind.SALE, 1000 * Math.exp(r.nextGaussian()), 1));
        PriceModel m = new PriceModel();
        assertFalse(m.train(data, 1).usable());
    }

    @Test void valuatorFallsBackToUsableModel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var db = new dev.rareradar.core.market.MarketDatabase(dir.resolve("m.jsonl"), dev.rareradar.core.store.AsyncWriter.inline());
        Random r = new Random(9);
        for (int i = 0; i < 200; i++) {
            int perfect = r.nextInt(7);
            int[] ivs = new int[6];
            for (int k = 0; k < 6; k++) ivs[k] = k < perfect ? 31 : 5;
            String s = i % 2 == 0 ? "gible" : "axew";
            db.add(PriceObservation.of(s, false, PriceObservation.Kind.SALE, 1000 * Math.exp(0.4 * perfect + 0.05 * r.nextGaussian()), 1).ivs(ivs));
        }
        PriceModel m = new PriceModel();
        assertTrue(m.train(db.all(), 1).usable());
        // A separate, sparse database: one listing only → heuristic is LOW confidence → model is consulted.
        var sparse = new dev.rareradar.core.market.MarketDatabase(dir.resolve("sparse.jsonl"), dev.rareradar.core.store.AsyncWriter.inline());
        sparse.add(PriceObservation.of("gible", false, PriceObservation.Kind.LISTING, 99_999, 1));
        var v = new dev.rareradar.core.market.Valuator(sparse, new dev.rareradar.core.market.ValuationConfig());
        v.setModel(m);
        var val = v.value(PokemonObservation.of("gible").ivs(31, 31, 31, 31, 31, 31), 1);
        assertEquals("learned model", val.method());
        assertEquals(1000 * Math.exp(0.4 * 6), val.total().value(), 1000 * Math.exp(0.4 * 6) * 0.15);
        // Never extrapolates: shiny (no shiny training data), unseen species and forms stay with the heuristic.
        assertNotEquals("learned model", v.value(PokemonObservation.of("gible").shiny(true), 1).method());
        assertFalse(m.predict(PokemonObservation.of("mew")).known());
        assertFalse(m.predict(PokemonObservation.of("gible").form("galar")).known());
    }

    @Test void solver() {
        double[] x = PriceModel.solve(new double[][]{{2, 1}, {1, 3}}, new double[]{3, 5});
        assertEquals(0.8, x[0], 1e-9);
        assertEquals(1.4, x[1], 1e-9);
    }
}

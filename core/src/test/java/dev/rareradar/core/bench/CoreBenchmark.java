package dev.rareradar.core.bench;

import dev.rareradar.core.breeding.*;
import dev.rareradar.core.market.*;
import dev.rareradar.core.ml.PriceModel;
import dev.rareradar.core.rarity.*;
import dev.rareradar.core.store.AsyncWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

/**
 * Micro-benchmarks for the hot paths (run with: gradle -p core test -Pbench). Numbers are host-dependent and are
 * reported in docs/PERFORMANCE.md together with the machine they were measured on. Not a substitute for in-game
 * profiling.
 */
@Tag("benchmark")
class CoreBenchmark {
    @TempDir Path dir;

    static double time(String label, int iters, Runnable r) {
        for (int i = 0; i < Math.max(1, iters / 10); i++) r.run(); // warm-up
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) r.run();
        double us = (System.nanoTime() - t0) / 1e3 / iters;
        System.out.printf(Locale.ROOT, "BENCH %-48s %10.3f µs/op%n", label, us);
        return us;
    }

    @Test void hotPaths() {
        Random rnd = new Random(1);
        // Classification: what the V1 mixins did per entity per frame (HashSet of labels) vs a V3 cache lookup.
        List<PokemonObservation> mons = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            PokemonObservation o = PokemonObservation.of("s" + rnd.nextInt(50)).shiny(rnd.nextInt(50) == 0);
            o.labels = rnd.nextInt(30) == 0 ? Set.of("legendary", "gen1") : Set.of("gen1");
            mons.add(o);
        }
        Watchlist w = new Watchlist();
        w.add(new Watchlist.Entry("s7", null, false, 5, ""));
        RarityEngine eng = new RarityEngine(new RarityConfig(), w, o -> Double.NaN, s -> false);
        int[] sink = {0};
        time("V1-style label set copy + checks (200 entities)", 20_000, () -> {
            for (PokemonObservation o : mons) {
                Set<String> labels = new HashSet<>(o.labels); labels.addAll(Set.of());
                if (labels.contains("legendary") || Boolean.TRUE.equals(o.shiny)) sink[0]++;
            }
        });
        time("V3 RarityEngine.classify (200 entities)", 20_000, () -> { for (PokemonObservation o : mons) if (eng.classify(o).qualifies()) sink[0]++; });
        {
            Map<Integer, Integer> colors = new HashMap<>();
            for (int i = 0; i < 200; i++) if (i % 20 == 0) colors.put(i, 0xFF00FF);
            time("V3 per-frame colour lookup (200 entities, boxed map)", 200_000, () -> { for (int i = 0; i < 200; i++) if (colors.get(i) != null) sink[0]++; });
        }

        MarketDatabase db = new MarketDatabase(dir.resolve("m.jsonl"), AsyncWriter.inline());
        List<PriceObservation> rows = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int[] ivs = new int[6];
            for (int k = 0; k < 6; k++) ivs[k] = rnd.nextInt(32);
            rows.add(PriceObservation.of("s" + rnd.nextInt(100), rnd.nextInt(10) == 0, rnd.nextBoolean() ? PriceObservation.Kind.SALE : PriceObservation.Kind.LISTING,
                    1000 + rnd.nextInt(100_000), 1_700_000_000_000L + rnd.nextInt(1_000_000_000)).ivs(ivs));
        }
        long t0 = System.nanoTime();
        db.importAll(rows);
        System.out.printf(Locale.ROOT, "BENCH %-48s %10.3f ms%n", "import 5,000 observations (inline IO)", (System.nanoTime() - t0) / 1e6);
        Valuator v = new Valuator(db, new ValuationConfig());
        long now = 1_701_000_000_000L;
        time("Valuator.value (5k-row DB, cold bucket stats)", 2_000, () -> sink[0] += (int) v.value(PokemonObservation.of("s" + rnd.nextInt(100)), now + rnd.nextInt(10_000_000)).total().samples());
        time("Valuator.value (cached bucket stats)", 20_000, () -> sink[0] += (int) v.value(PokemonObservation.of("s3"), now).total().samples());

        Parent a = new Parent("a", new int[]{31, 31, 31, 0, 0, 0}, "adamant", "male", List.of("monster"), HeldItem.DESTINY_KNOT);
        Parent b = new Parent("b", new int[]{0, 0, 0, 31, 31, 31}, "jolly", "female", List.of("monster"), HeldItem.POWER_ANKLET);
        BreedingAnalyzer an = new BreedingAnalyzer(new BreedingRules());
        time("BreedingAnalyzer.analyze (exact, knot + power item)", 2_000,
                () -> sink[0] += (int) (an.analyze(a, b, new BreedingAnalyzer.Target(new int[6], 5, "adamant"), null).pHit() * 10));

        t0 = System.nanoTime();
        PriceModel m = new PriceModel();
        var metrics = m.train(db.all(), 1);
        System.out.printf(Locale.ROOT, "BENCH %-48s %10.3f ms  (%s)%n", "PriceModel.train 5k rows, 5-fold CV × 3 λ", (System.nanoTime() - t0) / 1e6, metrics.summary());
        System.out.println("sink " + sink[0]);
    }
}

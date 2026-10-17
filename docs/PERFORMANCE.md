# Performance notes

## What changed versus V1

| Path | V1 | V3 |
|---|---|---|
| Outline mixins (`hasOutline`, `getTeamColorValue`) | `Rarity.of()` for **every entity, every frame, twice**, each call allocating a `HashSet` of labels | O(1) lookup in a primitive `Int2IntOpenHashMap` filled by the scanner; no allocation |
| Entity scan | every client tick, re-sorting and re-allocating the hit list | every `scanIntervalTicks` ticks (default 5, configurable 1–40) |
| Classification | recomputed on every scan and every frame | cached per entity id; recomputed only when ownership changes or after 30 s (so the demand term can update) |
| HUD | copied the hit list every frame | reads the scanner's immutable list; value estimates are computed once per entity and cached |
| A* pathfinding | node cap only | node cap **plus a 4 ms wall-clock budget**, returning the best partial path |
| Disk writes | settings saved synchronously on the client thread | all data writes go to one low-priority background thread with a bounded queue. Config writes are atomic (temp file + move). |
| Price model training | n/a | background daemon thread on a snapshot; the client thread only swaps in the finished model |

## Micro-benchmarks (core, measured)

Command: `gradle -p core test -Pbench --tests '*CoreBenchmark*'`.
Host: cloud container, 4 vCPU Intel Xeon @ 2.10 GHz, OpenJDK 21.0.12. Measured 2026-10-17.

| Benchmark | Result |
|---|---|
| V1-style label-set copy + checks, 200 entities | 8.6 µs per pass |
| V3 `RarityEngine.classify`, 200 entities (cache miss path) | 6.8 µs per pass |
| V3 per-frame colour lookup, 200 entities (boxed `HashMap` in the benchmark; the mod uses a primitive map) | 0.55 µs per pass |
| Import 5,000 price observations (inline I/O) | 110 ms |
| `Valuator.value` on a 5k-row DB, cold bucket statistics | 37 µs |
| `Valuator.value`, cached bucket statistics | 10.6 µs |
| `BreedingAnalyzer.analyze` (exact enumeration, Destiny Knot + Power item) | 57 µs |
| `PriceModel.train`, 5k rows, 5-fold CV × 3 λ | 125 ms (background thread) |

**Interpretation:** for 200 loaded Pokémon, V1 did about 2 × 8.6 µs of allocating work **per rendered frame** in the outline mixins. V3 does a ~0.5 µs lookup per frame plus ~7 µs of classification on cache misses only, at a fifth of the scan frequency.

## What has NOT been measured

These were **not** measured. The mod could not be run in this environment (no Minecraft runtime), so none of the following exist yet:

* in-game FPS impact;
* total memory consumption;
* rendering overhead;
* end-to-end scan duration on a real client.

The dashboard's *Radar* page shows live scan timings (average, last, max) and heap use, so they can be measured in-game. No claim of "zero FPS impact" is made.

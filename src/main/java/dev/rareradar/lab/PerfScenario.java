package dev.rareradar.lab;

import dev.rareradar.RadarScanner;
import dev.rareradar.core.lab.*;

import java.util.ArrayList;
import java.util.List;

/** Samples frame rate, scanner time and heap while the player stands still. Thresholds are stated, not hidden. */
final class PerfScenario implements Scenario {
    static final double MIN_AVG_FPS = 20, MAX_SCANNER_MS = 25;
    private static final int TICKS = 20 * 15;
    private final List<Double> fps = new ArrayList<>();
    private double heapStart = -1, heapEnd, scannerMax, pokemonMax;

    public String id() { return "PERF_STATIONARY"; }
    public String idPrefix() { return "PERF"; }
    public String category() { return "performance"; }
    public String description() { return "15 s stationary sampling of FPS, scanner time and heap"; }
    public Layer layer() { return Layer.IN_GAME; }
    public String expected() { return "Average FPS >= " + MIN_AVG_FPS + " and scanner max <= " + MAX_SCANNER_MS + " ms (indicative thresholds)"; }

    public void begin(TestContext c) { c.test.timeoutTicks = TICKS + 200; }

    public Step tick(TestContext c) {
        // Per-tick max of the most recent scan time (the scanner's own lifetime max would include unrelated earlier spikes).
        scannerMax = Math.max(scannerMax, RadarScanner.lastScanNanos / 1e6);
        if (c.ticks % 20 == 0) {
            PerfSample s = c.host.samplePerf();
            if (s != null) {
                if (s.fps() >= 0) fps.add((double) s.fps());
                if (heapStart < 0) heapStart = s.heapUsedMb();
                heapEnd = s.heapUsedMb();
                pokemonMax = Math.max(pokemonMax, s.pokemonEntities());
            }
        }
        if (c.ticks < TICKS) return Step.RUNNING;
        double avg = fps.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double min = fps.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        c.metric("fps_avg", Math.round(avg * 10) / 10.0);
        c.metric("fps_min", min);
        c.metric("scanner_max_ms", Math.round(scannerMax * 100) / 100.0);
        c.metric("heap_delta_mb", Math.round((heapEnd - heapStart) * 10) / 10.0);
        c.metric("pokemon_entities_max", pokemonMax);
        c.domain("detection.jsonl", "perf_window", "fps_avg", avg, "scanner_max_ms", scannerMax, "pokemon_max", pokemonMax);
        if (fps.isEmpty()) return Step.skip("no FPS readings available");
        if (avg < MIN_AVG_FPS) return Step.fail(FailureClass.PERFORMANCE_DEGRADED, String.format("average %.1f FPS < %.0f", avg, MIN_AVG_FPS), "");
        if (scannerMax > MAX_SCANNER_MS) return Step.fail(FailureClass.PERFORMANCE_DEGRADED, String.format("scanner max %.1f ms > %.0f ms", scannerMax, MAX_SCANNER_MS), "");
        return Step.pass(String.format("%.0f FPS avg, scanner max %.2f ms", avg, scannerMax));
    }
}

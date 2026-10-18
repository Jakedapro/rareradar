package dev.rareradar.core.lab;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded performance history plus summary statistics and an (indicative) heap-growth check. */
public final class PerformanceMonitor {
    public static final int MAX_SAMPLES = 4000;
    public static final double STALL_MS = 100;
    private final Deque<PerfSample> samples = new ArrayDeque<>();
    private int stalls;
    private long total;
    private double labTickMax;

    public void record(PerfSample s) {
        if (s == null) return;
        total++;
        if (samples.size() >= MAX_SAMPLES) samples.removeFirst();
        samples.addLast(s);
        if (s.labTickMs() > STALL_MS) stalls++;
        labTickMax = Math.max(labTickMax, s.labTickMs());
    }

    public long totalSamples() { return total; }

    /** Least-squares slope of heap use in MB/minute over the retained samples; NaN if too few or too short. */
    public double heapSlopeMbPerMin() {
        List<PerfSample> l = new ArrayList<>(samples);
        if (l.size() < 20) return Double.NaN;
        double spanMin = (l.get(l.size() - 1).tMillis() - l.get(0).tMillis()) / 60_000.0;
        if (spanMin < 2) return Double.NaN;
        double n = l.size(), sx = 0, sy = 0, sxx = 0, sxy = 0;
        long t0 = l.get(0).tMillis();
        for (PerfSample s : l) {
            double x = (s.tMillis() - t0) / 60_000.0, y = s.heapUsedMb();
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double den = n * sxx - sx * sx;
        return den == 0 ? Double.NaN : (n * sxy - sx * sy) / den;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<PerfSample> l = new ArrayList<>(samples);
        m.put("samples", total);
        if (l.isEmpty()) { m.put("note", "no performance samples were taken"); return m; }
        double[] fps = l.stream().filter(s -> s.fps() >= 0).mapToDouble(PerfSample::fps).sorted().toArray();
        if (fps.length > 0) {
            m.put("fps_avg", round(java.util.Arrays.stream(fps).average().orElse(0)));
            m.put("fps_min", fps[0]);
            m.put("fps_p5", fps[(int) Math.floor((fps.length - 1) * 0.05)]);
        }
        double[] heap = l.stream().mapToDouble(PerfSample::heapUsedMb).toArray();
        m.put("heap_start_mb", round(heap[0]));
        m.put("heap_end_mb", round(heap[heap.length - 1]));
        m.put("heap_max_mb", round(java.util.Arrays.stream(heap).max().orElse(0)));
        double slope = heapSlopeMbPerMin();
        if (!Double.isNaN(slope)) {
            m.put("heap_slope_mb_per_min", round(slope));
            m.put("heap_growth_suspected", slope > 25);
            m.put("heap_note", "indicative only: JVM heap includes uncollected garbage; confirm with a long run");
        }
        m.put("scanner_max_ms", round(l.stream().mapToDouble(PerfSample::scannerMaxMs).max().orElse(-1)));
        m.put("lab_tick_max_ms", round(labTickMax));
        m.put("lab_tick_stalls_over_100ms", stalls);
        m.put("pokemon_entities_max", l.stream().mapToInt(PerfSample::pokemonEntities).max().orElse(-1));
        return m;
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
}

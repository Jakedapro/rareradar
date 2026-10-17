package dev.rareradar.core.market;

import java.util.*;

/** Counters for one price-capture path, so it is always visible how many listings were seen, parsed, rejected and saved. */
public final class CaptureStats {
    public final String name;
    public long detected, parsed, saved, duplicates, ignored;
    public final Map<String, Long> rejected = new TreeMap<>();
    private final Deque<String> samples = new ArrayDeque<>();
    private long lastLoggedTotal = -1;

    public CaptureStats(String name) { this.name = name; }

    public void reject(String reason, String sample) {
        rejected.merge(reason, 1L, Long::sum);
        if (sample != null) {
            if (samples.size() >= 20) samples.removeFirst();
            samples.addLast(reason + " | " + sample);
        }
    }

    public long rejectedTotal() { long n = 0; for (long v : rejected.values()) n += v; return n; }

    public List<String> recentRejectedSamples() { return List.copyOf(samples); }

    public String summary() {
        return String.format(Locale.ROOT, "%s: detected %d, parsed %d, rejected %d%s, duplicates %d, saved %d",
                name, detected, parsed, rejectedTotal(), rejected.isEmpty() ? "" : " " + rejected, duplicates, saved);
    }

    /** True when counters changed since the last call (used to log summaries only when something happened). */
    public boolean changedSinceLastLog() {
        long t = detected + parsed + saved + duplicates + rejectedTotal();
        if (t == lastLoggedTotal) return false;
        lastLoggedTotal = t;
        return true;
    }
}

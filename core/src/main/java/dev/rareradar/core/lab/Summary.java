package dev.rareradar.core.lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-readable session summary (summary.json); also what the history store keeps for regression comparison. */
public final class Summary {
    public static final class Family {
        public String category, layer;
        public int tests, passed, failed, skipped, aborted;
        public double avgMs;
        public Map<String, Double> metricsAvg = new LinkedHashMap<>();
        /** Pass rate over passed+failed tests, or NaN when none ran. */
        public double passRate() { int n = passed + failed; return n == 0 ? Double.NaN : (double) passed / n; }
    }

    public String schema = "rareradar-lab-summary/1";
    public String sessionId, suite;
    public long seed, startMillis, durationMs;
    public String modVersion, gitSha, minecraftVersion, fabricVersion, cobblemonVersion;
    public String environment, stopReason;
    public int testsRun, passed, failed, skipped, aborted, criticalFailures;
    public Map<String, Family> families = new LinkedHashMap<>();
    public Map<String, Family> categories = new LinkedHashMap<>();
    public Map<String, Integer> failuresByClass = new LinkedHashMap<>();
    public Map<String, Object> performance = new LinkedHashMap<>();
    public java.util.List<String> layersRun = new java.util.ArrayList<>();
}

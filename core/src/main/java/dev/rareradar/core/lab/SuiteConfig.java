package dev.rareradar.core.lab;

/** One Lab run's settings (JSON-friendly). Everything is clamped by {@link #sanitize()}. */
public final class SuiteConfig {
    public String suite = "smoke";
    public int duration_minutes = 15;
    public int max_tests = 100;
    public long random_seed = 12345;
    public boolean stop_on_critical_failure = true;
    public boolean export_diagnostics = true;
    public int max_actions_per_test = 2000;
    public int test_timeout_seconds = 90;
    public int max_consecutive_failures = 5;

    public SuiteConfig sanitize() {
        if (suite == null || suite.isBlank()) suite = "smoke";
        duration_minutes = clamp(duration_minutes, 1, 120);
        max_tests = clamp(max_tests, 1, 2000);
        max_actions_per_test = clamp(max_actions_per_test, 1, 100_000);
        test_timeout_seconds = clamp(test_timeout_seconds, 5, 600);
        max_consecutive_failures = clamp(max_consecutive_failures, 1, 100);
        return this;
    }

    public SuiteConfig copy() {
        SuiteConfig c = new SuiteConfig();
        c.suite = suite; c.duration_minutes = duration_minutes; c.max_tests = max_tests; c.random_seed = random_seed;
        c.stop_on_critical_failure = stop_on_critical_failure; c.export_diagnostics = export_diagnostics;
        c.max_actions_per_test = max_actions_per_test; c.test_timeout_seconds = test_timeout_seconds;
        c.max_consecutive_failures = max_consecutive_failures;
        return c;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

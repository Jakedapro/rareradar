package dev.rareradar.lab;

import dev.rareradar.core.lab.ScenarioRegistry;

/** Registers the in-game scenarios and the suites that combine them with the core ones. */
final class McScenarios {
    private McScenarios() {}

    static void register(ScenarioRegistry r) {
        r.register("NAV_RANDOM", () -> new NavScenario(NavScenario.Kind.SHORT));
        r.register("NAV_LONG", () -> new NavScenario(NavScenario.Kind.LONG));
        r.register("NAV_FAR_GOAL", () -> new NavScenario(NavScenario.Kind.FAR_GOAL));
        r.register("DETECT_SCAN", DetectScenario::new);
        r.register("CAPTURE_BASIC", CaptureScenario::new);
        r.register("PERF_STATIONARY", PerfScenario::new);
        r.suite("navigation_basic", "NAV_RANDOM", "NAV_RANDOM", "NAV_LONG", "NAV_FAR_GOAL");
        r.suite("detection_basic", "DETECT_SCAN");
        r.suite("performance_basic", "PERF_STATIONARY");
        r.suite("capture_basic", "CAPTURE_BASIC");
        r.suite("full", "NAV_RANDOM", "DETECT_SCAN", "PERF_STATIONARY", "NAV_LONG", "NAV_FAR_GOAL", "ECON_BALANCE", "ECON_TRANSACTIONS", "ECON_EARNINGS", "CAPTURE_BASIC");
    }
}

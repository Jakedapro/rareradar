package dev.rareradar.core.lab;

import java.time.Instant;
import java.util.*;

/** Builds the summary, failures.json content and the Markdown report from what actually ran. Never invents results. */
public final class ReportGenerator {
    private ReportGenerator() {}

    public static final class Input {
        public String sessionId, stopReason;
        public SuiteConfig cfg;
        public EnvironmentVerifier.Env env;
        public BuildInfo build;
        public long startMillis, endMillis;
        public List<TestResult> results = new ArrayList<>();
        public Map<String, Object> performance = new LinkedHashMap<>();
        public List<String> notRun = new ArrayList<>();
        public int rotations;
    }

    public static Summary summarize(Input in) {
        Summary s = new Summary();
        s.sessionId = in.sessionId; s.suite = in.cfg.suite; s.seed = in.cfg.random_seed;
        s.startMillis = in.startMillis; s.durationMs = Math.max(0, in.endMillis - in.startMillis);
        s.modVersion = in.build.modVersion(); s.gitSha = in.build.gitSha(); s.minecraftVersion = in.build.minecraftVersion();
        s.fabricVersion = in.build.fabricVersion(); s.cobblemonVersion = in.build.cobblemonVersion();
        s.environment = in.env.kind().name(); s.stopReason = in.stopReason; s.performance = in.performance;
        Map<String, double[]> sums = new HashMap<>();
        Map<String, Map<String, double[]>> metricSums = new HashMap<>();
        for (TestResult r : in.results) {
            s.testsRun++;
            Summary.Family f = s.families.computeIfAbsent(r.scenario, k -> { Summary.Family n = new Summary.Family(); n.category = r.category; n.layer = r.layer; return n; });
            Summary.Family c = s.categories.computeIfAbsent(r.category, k -> { Summary.Family n = new Summary.Family(); n.category = r.category; n.layer = r.layer; return n; });
            for (Summary.Family x : List.of(f, c)) {
                x.tests++;
                switch (r.status) {
                    case PASS -> x.passed++;
                    case FAIL -> x.failed++;
                    case SKIPPED -> x.skipped++;
                    case ABORTED -> x.aborted++;
                }
            }
            switch (r.status) {
                case PASS -> s.passed++;
                case FAIL -> s.failed++;
                case SKIPPED -> s.skipped++;
                case ABORTED -> s.aborted++;
            }
            if (r.failureClass != null && (r.status == TestResult.Status.FAIL || r.status == TestResult.Status.ABORTED)) {
                s.failuresByClass.merge(r.failureClass.name(), 1, Integer::sum);
                if (r.failureClass.critical) s.criticalFailures++;
            }
            if (r.counted()) {
                sums.computeIfAbsent(r.scenario, k -> new double[2]);
                sums.get(r.scenario)[0] += r.elapsedMs();
                sums.get(r.scenario)[1]++;
                var ms = metricSums.computeIfAbsent(r.scenario, k -> new LinkedHashMap<>());
                r.metrics.forEach((k, v) -> { if (Double.isFinite(v)) { double[] a = ms.computeIfAbsent(k, kk -> new double[2]); a[0] += v; a[1]++; } });
            }
            if (r.status != TestResult.Status.SKIPPED && !s.layersRun.contains(r.layer)) s.layersRun.add(r.layer);
        }
        for (var e : sums.entrySet()) s.families.get(e.getKey()).avgMs = e.getValue()[0] / Math.max(1, e.getValue()[1]);
        for (var e : metricSums.entrySet())
            for (var m : e.getValue().entrySet()) s.families.get(e.getKey()).metricsAvg.put(m.getKey(), round(m.getValue()[0] / m.getValue()[1]));
        return s;
    }

    /** failures.json: every failed/aborted test with its details and the grouping by class. */
    public static Map<String, Object> failures(Input in) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byClass = new LinkedHashMap<>();
        for (TestResult r : in.results) {
            if (r.status != TestResult.Status.FAIL && r.status != TestResult.Status.ABORTED) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("test_id", r.testId); m.put("scenario", r.scenario); m.put("seed", r.seed); m.put("status", r.status.name());
            m.put("reason", r.failureReason); m.put("expected", r.expected); m.put("observed", r.observed);
            m.put("tags", new ArrayList<>(r.tags)); m.put("metrics", r.metrics); m.put("elapsed_ms", r.elapsedMs());
            byClass.computeIfAbsent(r.failureClass == null ? "UNCLASSIFIED" : r.failureClass.name(), k -> new ArrayList<>()).add(m);
        }
        out.put("session", in.sessionId);
        out.put("by_class", byClass);
        out.put("recommendations", recommendations(in));
        return out;
    }

    /** Prioritised, evidence-linked recommendations. Only produced for failures that actually occurred. */
    public static List<String> recommendations(Input in) {
        record Group(FailureClass cls, List<TestResult> tests) {}
        Map<FailureClass, List<TestResult>> by = new EnumMap<>(FailureClass.class);
        for (TestResult r : in.results)
            if ((r.status == TestResult.Status.FAIL || r.status == TestResult.Status.ABORTED) && r.failureClass != null)
                by.computeIfAbsent(r.failureClass, k -> new ArrayList<>()).add(r);
        List<Group> groups = new ArrayList<>();
        by.forEach((k, v) -> groups.add(new Group(k, v)));
        groups.sort(Comparator.<Group>comparingInt(g -> g.cls.critical ? 0 : 1).thenComparingInt(g -> -g.tests.size()));
        List<String> out = new ArrayList<>();
        int p = 1;
        for (Group g : groups) {
            Set<String> scen = new LinkedHashSet<>();
            List<String> ids = new ArrayList<>();
            for (TestResult r : g.tests) { scen.add(r.scenario); if (ids.size() < 5) ids.add(r.testId); }
            int totalOfScen = 0;
            for (TestResult r : in.results) if (scen.contains(r.scenario) && r.counted()) totalOfScen++;
            StringBuilder sb = new StringBuilder();
            sb.append("Priority ").append(p++).append(": ").append(g.cls.name()).append(" — ").append(g.cls.description).append('\n');
            sb.append("  Evidence: ").append(g.tests.size()).append(" failure(s) across ").append(totalOfScen).append(" counted test(s) of ")
                    .append(String.join(", ", scen)).append("; e.g. ").append(String.join(", ", ids)).append('\n');
            String cluster = tagCluster(g.tests(), in.results);
            if (cluster != null) sb.append("  Pattern: ").append(cluster).append('\n');
            sb.append("  Suggested investigation: ").append(investigate(g.cls));
            out.add(sb.toString());
        }
        return out;
    }

    private static String tagCluster(List<TestResult> tests, List<TestResult> all) {
        Map<String, int[]> perTag = new HashMap<>();
        for (TestResult r : all) {
            if (!r.counted()) continue;
            boolean failed = tests.contains(r);
            for (String t : r.tags) { int[] a = perTag.computeIfAbsent(t, k -> new int[2]); a[0]++; if (failed) a[1]++; }
        }
        String best = null; double bestRate = 0;
        for (var e : perTag.entrySet()) {
            int[] a = e.getValue();
            double rate = (double) a[1] / a[0];
            if (a[0] >= 3 && a[1] >= 2 && rate >= 0.3 && rate > bestRate) { best = e.getKey() + ": " + a[1] + " of " + a[0] + " such tests failed"; bestRate = rate; }
        }
        return best;
    }

    private static String investigate(FailureClass c) {
        return switch (c) {
            case NAVIGATION_STUCK -> "Replay the logged route in navigation.jsonl; check waypoint selection and jump/step handling at the stuck coordinates.";
            case PATH_NOT_FOUND -> "Check terrain classification and the destination chosen (standable, loaded, reachable) in navigation.jsonl.";
            case TARGET_DESPAWNED -> "Check how long targets live relative to approach time; consider re-targeting earlier.";
            case CAPTURE_TIMEOUT, CAPTURE_FAILED -> "Read captures.jsonl for the machine state at the end; check aim, range and ball selection.";
            case INVALID_ENTITY_STATE -> "Inspect the entity fields read at failure time for nulls or unloaded data.";
            case INVENTORY_DESYNC -> "Compare ball counts before/after in captures.jsonl with the classified outcome.";
            case DETECTION_MISMATCH -> "Compare detection.jsonl truth vs tracked ids; check scan range, caching (30 s refresh) and owned-Pokémon filtering.";
            case ECONOMY_ASSERTION -> "Run the named economy fixture in core tests; the failing assertion text is in the test's reason.";
            case PERFORMANCE_DEGRADED -> "Open performance.csv; correlate the slow window with the scenario column.";
            case TEST_TIMEOUT, ACTION_LIMIT -> "Check whether the limit is too tight for this scenario or the test stalled; see events.jsonl.";
            case UNEXPECTED_EXCEPTION -> "Read the stack trace in the failing test's reason / failures.json — this is a defect in RareRadar or the Lab.";
            case ENVIRONMENT_CHANGED -> "Expected if you left the world; otherwise check the connection events at the end of events.jsonl.";
            case SAFETY_ABORT -> "Review what damaged the player; the navigation hazard filter may have a gap.";
            case EMERGENCY_STOP -> "User-initiated; no action needed.";
        };
    }

    public static String markdown(Input in, Summary s, List<String> comparison) {
        StringBuilder b = new StringBuilder();
        b.append("# RareRadar Lab — Session Report\n\n");
        b.append("- Session: `").append(s.sessionId).append("`\n");
        b.append("- Suite: `").append(s.suite).append("` · seed ").append(s.seed).append('\n');
        b.append("- Environment: ").append(s.environment.toLowerCase(Locale.ROOT)).append(" · Cobblemon ").append(s.cobblemonVersion).append('\n');
        b.append("- Build: RareRadar ").append(s.modVersion).append(" (git ").append(s.gitSha).append(") · Minecraft ").append(s.minecraftVersion)
                .append(" · Fabric ").append(s.fabricVersion).append('\n');
        b.append("- Started: ").append(Instant.ofEpochMilli(s.startMillis)).append(" · duration ").append(fmtDuration(s.durationMs)).append('\n');
        b.append("- Stopped because: ").append(s.stopReason).append("\n\n");

        b.append("## Executive summary\n\n");
        b.append("| Tests run | Passed | Failed | Skipped | Aborted | Critical failures |\n|---|---|---|---|---|---|\n");
        b.append(String.format("| %d | %d | %d | %d | %d | %d |\n\n", s.testsRun, s.passed, s.failed, s.skipped, s.aborted, s.criticalFailures));
        for (var e : s.categories.entrySet()) {
            Summary.Family f = e.getValue();
            b.append("- ").append(cap(e.getKey())).append(": ").append(f.tests).append(" test(s) — ").append(f.passed).append(" passed, ").append(f.failed)
                    .append(" failed, ").append(f.skipped).append(" skipped");
            if (!Double.isNaN(f.passRate())) b.append(String.format(Locale.ROOT, " (success rate %.0f%%)", f.passRate() * 100));
            b.append('\n');
        }
        b.append("\n## What this session verifies\n\n");
        boolean inGame = s.layersRun.contains("IN_GAME");
        b.append(inGame ? "- IN_GAME tests executed in a real Minecraft world; those results reflect real client behaviour **for the conditions seen in this session only**.\n"
                : "- No IN_GAME test completed in this session, so nothing here verifies in-game behaviour.\n");
        if (s.layersRun.contains("FIXTURE")) b.append("- FIXTURE tests use synthetic data (for example economy messages). They check logic only and do **not** validate any real server's economy; real-server behaviour needs manual verification.\n");
        if (s.layersRun.contains("SIMULATED")) b.append("- SIMULATED tests ran against a modelled world, not Minecraft.\n");
        b.append("- A passing test shows the checked condition held in that run; it does not prove all conditions are covered.\n\n");

        b.append("## Failure classification\n\n");
        if (s.failuresByClass.isEmpty()) b.append("No failures recorded.\n\n");
        else { b.append("| Class | Count |\n|---|---|\n"); s.failuresByClass.forEach((k, v) -> b.append("| ").append(k).append(" | ").append(v).append(" |\n")); b.append('\n'); }

        b.append("## Recommendations (from observed failures only)\n\n");
        List<String> recs = recommendations(in);
        if (recs.isEmpty()) b.append("No failures were observed, so there are no evidence-based recommendations.\n\n");
        else for (String r : recs) b.append("```\n").append(r).append("\n```\n\n");

        b.append("## Per-scenario results\n\n| Scenario | Layer | Tests | Pass | Fail | Skip | Success | Avg ms |\n|---|---|---|---|---|---|---|---|\n");
        for (var e : s.families.entrySet()) {
            Summary.Family f = e.getValue();
            b.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %s | %.0f |\n", e.getKey(), f.layer, f.tests, f.passed, f.failed, f.skipped,
                    Double.isNaN(f.passRate()) ? "n/a" : String.format(Locale.ROOT, "%.0f%%", f.passRate() * 100), f.avgMs));
        }
        b.append('\n');
        for (var e : s.families.entrySet())
            if (!e.getValue().metricsAvg.isEmpty()) b.append("- ").append(e.getKey()).append(" averages: ").append(e.getValue().metricsAvg).append('\n');

        b.append("\n## Performance\n\n");
        s.performance.forEach((k, v) -> b.append("- ").append(k).append(": ").append(v).append('\n'));

        b.append("\n## Comparison with previous session\n\n");
        for (String c : comparison) b.append("- ").append(c).append('\n');

        b.append("\n## Not run in this session\n\n");
        if (in.notRun.isEmpty()) b.append("Every registered scenario was part of the suite.\n");
        else for (String n : in.notRun) b.append("- ").append(n).append('\n');
        if (in.rotations > 0) b.append("\nNote: ").append(in.rotations).append(" log file rotation(s) occurred; older lines are in the `.1` backups.\n");
        b.append("\n## Files\n\nsummary.json · events.jsonl · navigation.jsonl · detection.jsonl · captures.jsonl · performance.csv · failures.json · environment.json · report.md\n");
        return b.toString();
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String fmtDuration(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%dm %02ds", sec / 60, sec % 60);
    }

    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
}

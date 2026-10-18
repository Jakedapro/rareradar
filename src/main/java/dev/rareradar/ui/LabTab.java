package dev.rareradar.ui;

import dev.rareradar.core.lab.EnvironmentVerifier;
import dev.rareradar.core.lab.LabController;
import dev.rareradar.core.lab.SuiteConfig;
import dev.rareradar.core.report.Line;
import dev.rareradar.lab.LabRuntime;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** RareRadar Lab page: environment check, suite configuration, controls, live statistics and diagnostics. */
final class LabTab implements Tab {
    private static final int[] DURATIONS = {5, 15, 30, 60};
    private static final int[] MAX_TESTS = {10, 25, 100, 250, 1000};
    private TextFieldWidget seed;

    public String name() { return "Lab"; }

    private static long now() { return System.currentTimeMillis(); }

    public int widgets(DashboardScreen s, int x, int y, int w) {
        if (!LabRuntime.available()) return 0;
        LabController lab = LabRuntime.controller();
        SuiteConfig cfg = LabRuntime.config().last;
        boolean running = lab.running(), paused = lab.state() == LabController.State.PAUSED;
        boolean allowed = LabRuntime.verdict().allowed();

        ButtonWidget start = s.button(x, y, 70, running ? "Running" : "Start", () -> {
            applySeed(cfg);
            String msg = LabRuntime.start(cfg);
            s.flash(msg);
            s.rebuild();
        });
        start.active = !running && allowed;
        ButtonWidget pause = s.button(x + 74, y, 70, paused ? "Resume" : "Pause", () -> {
            if (paused) lab.resume(now()); else lab.pause(now());
            s.rebuild();
        });
        pause.active = running;
        ButtonWidget stop = s.button(x + 148, y, 60, "Stop", () -> { LabRuntime.stop("stopped by user"); s.flash("Lab stopped; report written"); s.rebuild(); });
        stop.active = running;
        s.button(x + 212, y, 130, "§cEMERGENCY STOP", () -> { LabRuntime.emergencyStop("dashboard emergency stop"); s.flash("§cEmergency stop"); s.rebuild(); });

        int y2 = y + 22;
        List<String> suites = lab.registry().suiteNames();
        ButtonWidget suite = s.button(x, y2, 190, "Suite: " + cfg.suite, () -> {
            int i = suites.indexOf(cfg.suite);
            cfg.suite = suites.get((i + 1) % suites.size());
            keepSeed(cfg);
            s.rebuild();
        });
        suite.active = !running;
        ButtonWidget dur = s.button(x + 194, y2, 110, "Duration: " + cfg.duration_minutes + " min", () -> { cfg.duration_minutes = next(DURATIONS, cfg.duration_minutes); keepSeed(cfg); s.rebuild(); });
        dur.active = !running;
        ButtonWidget max = s.button(x + 308, y2, 110, "Max tests: " + cfg.max_tests, () -> { cfg.max_tests = next(MAX_TESTS, cfg.max_tests); keepSeed(cfg); s.rebuild(); });
        max.active = !running;
        seed = s.field(x + 422, y2, 90, "seed");
        seed.setText(Long.toString(cfg.random_seed));
        seed.active = !running;

        int y3 = y2 + 22;
        ButtonWidget crit = s.button(x, y3, 190, "Stop on critical: " + (cfg.stop_on_critical_failure ? "yes" : "no"), () -> { cfg.stop_on_critical_failure = !cfg.stop_on_critical_failure; keepSeed(cfg); s.rebuild(); });
        crit.active = !running;
        s.button(x + 194, y3, 110, "Open report", () -> openLast(s, "report.md"));
        s.button(x + 308, y3, 150, "Open session folder", () -> openLast(s, ""));
        return 66;
    }

    private void applySeed(SuiteConfig cfg) {
        if (seed == null || seed.getText().isBlank()) return;
        try { cfg.random_seed = Long.parseLong(seed.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Seed must be a whole number"); }
    }

    /** Keeps a half-edited seed when the page is rebuilt by another control. */
    private void keepSeed(SuiteConfig cfg) {
        try { applySeed(cfg); } catch (IllegalArgumentException ignored) { /* invalid text is reported on Start */ }
    }

    private static int next(int[] a, int cur) {
        for (int i = 0; i < a.length; i++) if (a[i] == cur) return a[(i + 1) % a.length];
        return a[0];
    }

    private static void openLast(DashboardScreen s, String file) {
        String dir = LabRuntime.available() ? LabRuntime.controller().lastSessionDir() : "";
        if (dir.isEmpty()) { s.flash("§eNo Lab session yet"); return; }
        Path p = file.isEmpty() ? Path.of(dir) : Path.of(dir, file);
        if (!Files.exists(p)) { s.flash("§e" + (file.isEmpty() ? "Folder" : "Report") + " not written yet (the report is written when the session stops): " + p); return; }
        s.flash(LabRuntime.openFolder(p) ? "Opened " + p : "Could not open a file manager; path: " + p);
    }

    public void lines(List<Line> out) {
        if (!LabRuntime.available()) { out.add(Line.warn("RareRadar Lab failed to initialise — see latest.log.")); return; }
        LabController lab = LabRuntime.controller();
        var env = LabRuntime.environment();
        var verdict = LabRuntime.verdict();
        out.add(Line.h("ENVIRONMENT"));
        out.add(Line.t("World: " + switch (env.kind()) {
            case SINGLEPLAYER -> "local single-player";
            case MULTIPLAYER -> "multiplayer " + env.address();
            case NOT_IN_WORLD -> "not in a world";
        }));
        out.add(verdict.allowed() ? Line.good("Lab allowed: " + verdict.reason()) : Line.warn("Lab blocked: " + verdict.reason()));
        out.add(env.cobblemonPresent() ? Line.t("Cobblemon: installed") : Line.warn("Cobblemon: not installed — detection and capture tests will be skipped"));
        out.add(Line.dim("Automation permission: tests only run after you press Start, only here, and stop on leaving. Backspace = emergency stop."));
        out.add(Line.dim("Public servers and CobbleGalaxy are always refused. Private test servers need an entry in lab_config.json → allowedServers."));
        out.add(Line.blank());

        var s = lab.snapshot(now());
        out.add(Line.h("STATUS"));
        out.add(Line.t("State: " + s.state() + (s.suite().isEmpty() ? "" : " · suite " + s.suite()) + (s.currentTest().isEmpty() ? "" : " · running " + s.currentTest())));
        out.add(Line.t(String.format(Locale.ROOT, "Tests: %d done · %d passed · %d failed · %d skipped · elapsed %ds",
                s.testsDone(), s.passed(), s.failed(), s.skipped(), s.elapsedMs() / 1000)));
        out.add(Line.t("Navigation success: " + pct(s.navRate()) + " · Capture success: " + pct(s.captureRate())));
        if (!s.stopReason().isEmpty() && lab.state() == LabController.State.IDLE) out.add(Line.dim("Last stop: " + s.stopReason()));
        if (!s.recentFailures().isEmpty()) {
            out.add(Line.warn("Recent failures:"));
            for (String f : s.recentFailures()) out.add(Line.warn("  " + f));
        }
        out.add(Line.blank());

        out.add(Line.h("DIAGNOSTICS"));
        String dir = lab.lastSessionDir();
        out.add(dir.isEmpty() ? Line.dim("No session yet. Logs go to " + LabRuntime.dir() + "/sessions/") : Line.t("Last session: " + dir));
        var errs = lab.recentErrors();
        if (errs.isEmpty()) out.add(Line.dim("No Lab errors recorded."));
        else { out.add(Line.warn("Recent errors:")); for (String e : errs) out.add(Line.warn("  " + e)); }
        out.add(Line.blank());

        out.add(Line.h("SUITES"));
        for (String name : lab.registry().suiteNames())
            out.add(Line.t(name + ": " + String.join(", ", lab.registry().suiteScenarios(name))));
        out.add(Line.blank());
        out.add(Line.h("WHAT THESE TESTS CAN AND CANNOT SHOW"));
        out.add(Line.dim("IN_GAME tests use your real world; results describe only the terrain, Pokémon and inventory present during the run."));
        out.add(Line.dim("FIXTURE tests (economy) use made-up messages: they check logic, not CobbleGalaxy's real chat formats or prices."));
        out.add(Line.dim("The Lab cannot spawn Pokémon or build terrain. For repeatable runs use a dedicated test world (docs/LAB.md) and the same seed."));
    }

    private static String pct(double d) { return Double.isNaN(d) ? "n/a (none run)" : String.format(Locale.ROOT, "%.0f%%", d * 100); }
}

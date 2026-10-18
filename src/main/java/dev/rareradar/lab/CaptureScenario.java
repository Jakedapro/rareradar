package dev.rareradar.lab;

import dev.rareradar.AutoHunt;
import dev.rareradar.CobblemonCompat;
import dev.rareradar.RadarScanner;
import dev.rareradar.core.autohunt.AutoHuntMachine;
import dev.rareradar.core.autohunt.CaptureOutcome;
import dev.rareradar.core.lab.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * Drives the existing AutoHunt on whatever qualifying Pokémon is already in range (single-player only) and classifies
 * the end result. Limits that are recorded rather than hidden: the Lab cannot spawn a target, AutoHunt only targets what
 * the radar rates as qualifying, and "captured" is AutoHunt's own classification (its ball reached the CAPTURED state);
 * the Lab cross-checks it against the ball count but does not have an independent capture signal.
 */
final class CaptureScenario implements Scenario {
    private int ballsBefore, outcomes;
    private double travelled, lastX, lastZ;
    private boolean started;
    private CaptureOutcome last;

    public String id() { return "CAPTURE_BASIC"; }
    public String idPrefix() { return "CAP"; }
    public String category() { return "capture"; }
    public String description() { return "One AutoHunt run on an in-range qualifying Pokémon (single-player only)"; }
    public Layer layer() { return Layer.IN_GAME; }
    public String expected() { return "AutoHunt finishes with a classified outcome; a capture is confirmed by its ball-state check and the ball count dropped"; }

    public void begin(TestContext c) {
        c.test.timeoutTicks = 20 * 120;
        c.test.maxActions = Math.max(c.test.maxActions, 5000);
    }

    public Step tick(TestContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return Step.fail(FailureClass.INVALID_ENTITY_STATE, "player or world missing", "");
        if (!started) return start(c, mc, p);
        travelled += Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
        lastX = p.getX(); lastZ = p.getZ();
        CaptureOutcome o = AutoHunt.lastOutcome;
        if (o != null && o != last) { last = o; outcomes++; c.domain("captures.jsonl", "throw_outcome", "outcome", o.name(), "balls_in_inventory", balls(p)); }
        if (AutoHunt.active) return Step.RUNNING;
        return finish(c, p);
    }

    private Step start(TestContext c, MinecraftClient mc, ClientPlayerEntity p) {
        if (!c.host.environment().cobblemonPresent()) return Step.skip("Cobblemon is not installed");
        if (c.host.environment().kind() != EnvironmentVerifier.Kind.SINGLEPLAYER) return Step.skip("capture tests run in single-player only");
        if (AutoHunt.active) return Step.skip("Auto Hunt/Train is already running");
        ballsBefore = balls(p);
        if (ballsBefore < 1) return Step.skip("INSUFFICIENT_BALLS: no Poké Balls in the inventory");
        if (RadarScanner.hits().isEmpty()) return Step.skip("INVALID_TARGET: no Pokémon currently qualifies as an AutoHunt target (the Lab cannot spawn one)");
        started = true;
        lastX = p.getX(); lastZ = p.getZ();
        AutoHunt.lastOutcome = null;
        LabRuntime.autoHuntAllowed = true;
        AutoHunt.toggle(mc);
        c.action();
        c.domain("captures.jsonl", "capture_started", "balls_before", ballsBefore, "candidates", RadarScanner.hits().size());
        if (!AutoHunt.active) {
            LabRuntime.autoHuntAllowed = false;
            return Step.skip("AutoHunt refused to start (see chat/log for the reason)");
        }
        return Step.RUNNING;
    }

    private Step finish(TestContext c, ClientPlayerEntity p) {
        AutoHuntMachine m = AutoHunt.machine();
        int ballsAfter = balls(p), used = Math.max(0, ballsBefore - ballsAfter);
        int caught = m == null ? 0 : m.captures();
        String stop = m == null ? "" : m.stopReason();
        c.metric("captures", caught);
        c.metric("balls_used", used);
        c.metric("distance_blocks", Math.round(travelled * 10) / 10.0);
        c.metric("time_s", c.ticks / 20.0);
        c.domain("captures.jsonl", "capture_finished", "captures", caught, "balls_used", used, "last_outcome", last == null ? "NONE" : last.name(),
                "stop_reason", stop, "result", caught > 0 ? "CAPTURE_SUCCESS" : "NOT_CAPTURED");
        if (caught > 0) {
            if (used == 0) return Step.fail(FailureClass.INVENTORY_DESYNC, "AutoHunt reported a capture but no ball left the inventory", "captures=" + caught);
            return Step.pass("CAPTURE_SUCCESS (AutoHunt ball-state check), " + used + " ball(s) used");
        }
        String s = stop.toLowerCase(java.util.Locale.ROOT);
        if (last == CaptureOutcome.TARGET_DESPAWNED) return Step.fail(FailureClass.TARGET_DESPAWNED, "TARGET_DESPAWNED", stop);
        if (last == CaptureOutcome.BROKE_FREE) return Step.fail(FailureClass.CAPTURE_FAILED, "CAPTURE_FAILED: broke free", stop);
        if (last == CaptureOutcome.MISSED) return Step.fail(FailureClass.CAPTURE_FAILED, "INTERACTION_FAILED: ball missed", stop);
        if (last == CaptureOutcome.NO_BALLS) return Step.skip("INSUFFICIENT_BALLS during the run");
        if (last == CaptureOutcome.TARGET_IN_BATTLE) return Step.fail(FailureClass.INVALID_ENTITY_STATE, "INVALID_TARGET: target entered a battle", stop);
        if (s.contains("stuck") || s.contains("unreach") || s.contains("path")) return Step.fail(FailureClass.NAVIGATION_STUCK, "TARGET_UNREACHABLE: " + stop, stop);
        if (s.contains("time")) return Step.fail(FailureClass.CAPTURE_TIMEOUT, "TIMEOUT: " + stop, stop);
        if (used == 0 && outcomes == 0) return Step.skip("no throw happened (" + (stop.isEmpty() ? "AutoHunt stopped" : stop) + ")");
        return Step.fail(FailureClass.CAPTURE_FAILED, "UNKNOWN: no confirmed capture (stop reason: " + stop + ")", stop);
    }

    @Override
    public void end(TestContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (AutoHunt.active && mc != null) AutoHunt.stop(mc, "Lab test ended");
        LabRuntime.autoHuntAllowed = false;
    }

    private static int balls(ClientPlayerEntity p) {
        int n = 0;
        for (ItemStack s : p.getInventory().main) if (CobblemonCompat.ballId(s) != null) n += s.getCount();
        return n;
    }
}

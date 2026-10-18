package dev.rareradar.lab;

import dev.rareradar.Navigator;
import dev.rareradar.Pathfinder;
import dev.rareradar.core.lab.*;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * Walks the real player to a seeded, pre-validated destination with the production Pathfinder/Navigator, watching for
 * stuck states with bounded recovery. Terrain tags (water boundary, forest, hills) are logged so failures can be grouped.
 * The Lab does not build terrain: what it covers depends on the world you run it in.
 */
final class NavScenario implements Scenario {
    enum Kind { SHORT, LONG, FAR_GOAL }

    private static final double REACH = 1.5;
    private static final int MAX_RECOVERIES = 3;
    private static final float DAMAGE_ABORT = 2.0f;

    private final Kind kind;
    private final Navigator nav = new Navigator();
    private StuckDetector stuck;
    private BlockPos goal;
    private double expectedLen, straight, travelled, lastX, lastZ;
    private float startHealth;
    private int recoveries, stuckEvents, planCalls;
    private boolean planned;
    private long startedNanos;

    NavScenario(Kind kind) { this.kind = kind; }

    public String id() { return switch (kind) { case SHORT -> "NAV_RANDOM"; case LONG -> "NAV_LONG"; case FAR_GOAL -> "NAV_FAR_GOAL"; }; }
    public String idPrefix() { return "NAV"; }
    public String category() { return "navigation"; }
    public String description() {
        return switch (kind) {
            case SHORT -> "Walk to a random reachable spot 8–24 blocks away";
            case LONG -> "Walk to a random reachable spot 30–60 blocks away";
            case FAR_GOAL -> "Plan toward a goal ~200 blocks away: the planner must not report a partial path as success";
        };
    }
    public Layer layer() { return Layer.IN_GAME; }
    public String expected() {
        return kind == Kind.FAR_GOAL ? "Navigator.plan() returns false for a goal beyond the search budget, quickly, without moving the player"
                : "Player reaches within " + REACH + " blocks of the goal without leaving safe terrain, with at most " + MAX_RECOVERIES + " recoveries";
    }

    public void begin(TestContext c) {
        c.test.timeoutTicks = kind == Kind.LONG ? 20 * 120 : 20 * 60;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        startHealth = p.getHealth();
        lastX = p.getX(); lastZ = p.getZ();
        stuck = new StuckDetector(20 * 5, 1.5, 4);
        startedNanos = System.nanoTime();
    }

    public Step tick(TestContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) return Step.fail(FailureClass.INVALID_ENTITY_STATE, "player or world vanished", "");
        if (p.getHealth() < startHealth - DAMAGE_ABORT) {
            return Step.fail(FailureClass.SAFETY_ABORT, String.format("health fell from %.1f to %.1f during navigation", startHealth, p.getHealth()), "");
        }
        if (!planned) return plan(c, mc, p, w);

        double dx = p.getX() - goal.getX() - 0.5, dz = p.getZ() - goal.getZ() - 0.5;
        double dist = Math.hypot(dx, dz);
        travelled += Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
        lastX = p.getX(); lastZ = p.getZ();

        if (dist <= REACH || nav.finished()) {
            return arrived(c, dist);
        }
        nav.follow(mc);
        if (c.ticks % 20 == 0) c.action();

        StuckDetector.Reason r = stuck.observe(c.ticks, p.getX(), p.getZ(), dist);
        if (r != StuckDetector.Reason.NONE) {
            stuckEvents++;
            c.domain("navigation.jsonl", "stuck_detected", "reason", r.name(), "x", round(p.getX()), "y", round(p.getY()), "z", round(p.getZ()),
                    "dist_to_goal", round(dist), "recoveries_used", recoveries);
            if (recoveries >= MAX_RECOVERIES || stuck.noteReplan()) {
                c.metric("stuck_events", stuckEvents);
                c.metric("recoveries", recoveries);
                c.tag("stuck");
                return Step.fail(FailureClass.NAVIGATION_STUCK, r + " after " + recoveries + " recovery attempt(s) at " + pos(p.getBlockPos()),
                        String.format("%.1f blocks from goal", dist));
            }
            recoveries++;
            c.action();
            boolean ok = nav.plan(mc, goal, REACH);
            planCalls++;
            c.domain("navigation.jsonl", "recovery_replan", "attempt", recoveries, "path_found", ok);
            stuck.resetWindow();
            if (!ok) return Step.fail(FailureClass.PATH_NOT_FOUND, "replan failed during recovery " + recoveries, "");
        }
        return Step.RUNNING;
    }

    private Step plan(TestContext c, MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        planned = true;
        BlockPos from = p.getBlockPos();
        if (kind == Kind.FAR_GOAL) return planFar(c, mc, p, from);
        double lo = kind == Kind.SHORT ? 8 : 30, hi = kind == Kind.SHORT ? 24 : 60;
        List<BlockPos> path = List.of();
        int tries = 0;
        for (; tries < 12 && goal == null; tries++) {
            BlockPos cand = candidate(w, from, c.rng, lo, hi);
            if (cand == null) continue;
            List<BlockPos> pth = Pathfinder.find(w, from, cand, REACH);
            if (pth.size() < 2) continue;
            BlockPos end = pth.get(pth.size() - 1);
            if (Math.hypot(end.getX() - cand.getX(), end.getZ() - cand.getZ()) <= REACH + 1.5 && Math.abs(end.getY() - cand.getY()) <= 4) { goal = cand; path = pth; }
        }
        c.metric("goal_resamples", tries - 1);
        if (goal == null) return Step.skip("no reachable destination found nearby (precondition, not a navigation failure)");
        straight = Math.hypot(goal.getX() - from.getX(), goal.getZ() - from.getZ());
        expectedLen = polyline(path);
        for (String t : NavTerrain.classify(w, path, expectedLen, straight)) c.tag(t);
        c.metric("straight_blocks", round(straight));
        c.metric("expected_path_blocks", round(expectedLen));
        c.domain("navigation.jsonl", "route_planned", "from", pos(from), "to", pos(goal), "straight_blocks", round(straight),
                "expected_path_blocks", round(expectedLen), "path_nodes", path.size(), "plan_ms", round(Pathfinder.lastFindNanos / 1e6), "tags", c.test.tags);
        boolean ok = nav.plan(mc, goal, REACH);
        planCalls++;
        if (!ok) return Step.fail(FailureClass.PATH_NOT_FOUND, "Navigator.plan() rejected a goal the Pathfinder had just reached", "");
        c.action();
        return Step.RUNNING;
    }

    private Step planFar(TestContext c, MinecraftClient mc, ClientPlayerEntity p, BlockPos from) {
        double a = c.rng.nextDouble() * Math.PI * 2;
        goal = from.add((int) (Math.cos(a) * 200), 0, (int) (Math.sin(a) * 200));
        long t0 = System.nanoTime();
        boolean ok = nav.plan(mc, goal, REACH);
        double ms = (System.nanoTime() - t0) / 1e6;
        c.metric("plan_ms", round(ms));
        c.domain("navigation.jsonl", "far_goal_planned", "goal", pos(goal), "plan_accepted", ok, "plan_ms", round(ms));
        if (ok) return Step.fail(FailureClass.PATH_NOT_FOUND, "plan() accepted a goal ~200 blocks away; partial paths must not count as success", "plan accepted");
        if (ms > 100) return Step.fail(FailureClass.PERFORMANCE_DEGRADED, String.format("planning took %.0f ms (budget is a few ms)", ms), "");
        return Step.pass(String.format("plan rejected in %.1f ms", ms));
    }

    private Step arrived(TestContext c, double dist) {
        double sec = c.ticks / 20.0;
        c.metric("actual_path_blocks", round(travelled));
        c.metric("time_s", round(sec));
        c.metric("avg_speed_bps", round(travelled / Math.max(0.05, sec)));
        c.metric("path_efficiency", round(expectedLen > 0 ? travelled / expectedLen : 0));
        c.metric("stuck_events", stuckEvents);
        c.metric("recoveries", recoveries);
        c.metric("plan_calls", planCalls);
        c.domain("navigation.jsonl", "navigation_completed", "distance_blocks", round(travelled), "expected_blocks", round(expectedLen),
                "recalculations", planCalls - 1, "stuck_events", stuckEvents, "final_dist", round(dist), "result", "PASS");
        return Step.pass(String.format("arrived in %.1fs, %.1f blocks (expected %.1f)", sec, travelled, expectedLen));
    }

    @Override
    public void end(TestContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) Navigator.release(mc);
        c.metric("stuck_events", stuckEvents);
        c.metric("recoveries", recoveries);
    }

    // ---------------------------------------------------------------- helpers

    /** A standable ground cell at a random bearing/distance, in a loaded chunk, never in or above lava/fire. */
    private static BlockPos candidate(ClientWorld w, BlockPos from, Random rng, double lo, double hi) {
        double a = rng.nextDouble() * Math.PI * 2, d = lo + rng.nextDouble() * (hi - lo);
        int x = from.getX() + (int) Math.round(Math.cos(a) * d), z = from.getZ() + (int) Math.round(Math.sin(a) * d);
        if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = from.getY() + 8; y >= from.getY() - 12; y--) {
            BlockPos feet = from.add(x - from.getX(), y - from.getY(), z - from.getZ());
            if (!NavTerrain.air(w, feet) || !NavTerrain.air(w, feet.up())) continue;
            BlockState below = w.getBlockState(feet.down());
            if (below.getFluidState().isIn(FluidTags.LAVA) || below.getFluidState().isIn(FluidTags.WATER)) return null;
            if (below.getCollisionShape(w, feet.down()).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    static double polyline(List<BlockPos> p) {
        double s = 0;
        for (int i = 1; i < p.size(); i++) s += Math.hypot(p.get(i).getX() - p.get(i - 1).getX(), p.get(i).getZ() - p.get(i - 1).getZ());
        return s;
    }

    static String pos(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
}

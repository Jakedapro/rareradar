package dev.rareradar;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * Walks in long, gently turning legs so Auto Hunt can search for a target instead of standing still. Every leg goal is
 * a standable spot in a loaded chunk that the Pathfinder has already reached on paper; stuck legs are abandoned and a
 * new heading is tried. It only moves; it never interacts with anything.
 */
final class Explorer {
    private static final double REACH = 2.0;
    private static final int MAX_LEG_TICKS = 20 * 45, STUCK_TICKS = 20 * 3, TRIES_PER_TICK = 3;

    private final Navigator nav = new Navigator();
    private final Random rng = new Random();
    private BlockPos goal;
    private double heading = Double.NaN;
    private int legTicks, stuck, legs, failedPicks;

    String status() { return "EXPLORING — leg " + legs + (goal == null ? ", looking for a route" : ", walking to " + goal.getX() + "," + goal.getZ()); }

    void reset() { goal = null; legTicks = 0; stuck = 0; failedPicks = 0; }

    /** One tick of exploring. Returns false if it could not move at all this tick. */
    boolean step(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        ClientWorld w = c.world;
        if (p == null || w == null) return false;
        if (goal != null) {
            double d = Math.hypot(p.getX() - goal.getX() - 0.5, p.getZ() - goal.getZ() - 0.5);
            if (d <= REACH || nav.finished() || ++legTicks > MAX_LEG_TICKS || stuck > STUCK_TICKS) goal = null;
        }
        if (goal == null) {
            Navigator.release(c);
            pick(c, p, w);
            return false;
        }
        boolean moving = nav.follow(c);
        stuck = moving ? 0 : stuck + 1;
        return moving;
    }

    private void pick(MinecraftClient c, ClientPlayerEntity p, ClientWorld w) {
        BlockPos from = p.getBlockPos();
        for (int i = 0; i < TRIES_PER_TICK; i++) {
            // Keep roughly the same direction (±~60°) so it covers new ground; after failures turn further.
            if (Double.isNaN(heading)) heading = rng.nextDouble() * Math.PI * 2;
            else heading += (rng.nextDouble() - 0.5) * (failedPicks > 2 ? Math.PI * 1.5 : Math.PI / 1.5);
            double d = 24 + rng.nextDouble() * 24;
            BlockPos cand = ground(w, from, (int) Math.round(Math.cos(heading) * d), (int) Math.round(Math.sin(heading) * d));
            if (cand == null) { failedPicks++; continue; }
            List<BlockPos> path = Pathfinder.find(w, from, cand, REACH);
            if (path.size() < 2) { failedPicks++; continue; }
            BlockPos end = path.get(path.size() - 1);
            if (Math.hypot(end.getX() - cand.getX(), end.getZ() - cand.getZ()) > REACH + 1.5 || Math.abs(end.getY() - cand.getY()) > 4) { failedPicks++; continue; }
            if (nav.plan(c, cand, REACH)) {
                goal = cand;
                legTicks = 0;
                stuck = 0;
                failedPicks = 0;
                legs++;
                return;
            }
            failedPicks++;
        }
    }

    private static BlockPos ground(ClientWorld w, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx, z = from.getZ() + dz;
        if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = from.getY() + 8; y >= from.getY() - 12; y--) {
            BlockPos feet = from.add(dx, y - from.getY(), dz);
            if (!free(w, feet) || !free(w, feet.up())) continue;
            BlockPos below = feet.down();
            BlockState b = w.getBlockState(below);
            if (b.getFluidState().isIn(FluidTags.LAVA) || b.getFluidState().isIn(FluidTags.WATER)) return null;
            if (b.getCollisionShape(w, below).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    private static boolean free(ClientWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty() && !s.getFluidState().isIn(FluidTags.WATER) && !s.getFluidState().isIn(FluidTags.LAVA);
    }
}

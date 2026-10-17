package dev.rareradar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Follows an A* path by pressing movement keys. Progress is reported to the state machine, which owns timeouts. */
final class Navigator {
    private static final float MAX_TURN = 7.0f;
    private List<BlockPos> path = List.of();
    private int idx;
    private Vec3d lastPos = Vec3d.ZERO;
    private int stillTicks;

    /** Plans a path; true only if it actually ends within reach of the goal (A* returns partial paths otherwise). */
    boolean plan(MinecraftClient c, BlockPos goal, double reach) {
        path = Pathfinder.find(c.world, c.player.getBlockPos(), goal, reach);
        idx = Math.min(1, Math.max(0, path.size() - 1));
        stillTicks = 0;
        if (path.size() < 2) return false;
        BlockPos end = path.get(path.size() - 1);
        return Math.hypot(end.getX() - goal.getX(), end.getZ() - goal.getZ()) <= reach + 1.5 && Math.abs(end.getY() - goal.getY()) <= 4;
    }

    boolean finished() { return path.isEmpty() || idx >= path.size() - 1 && closeTo(path.get(path.size() - 1)); }

    private boolean closeTo(BlockPos p) {
        ClientPlayerEntity pl = MinecraftClient.getInstance().player;
        return pl != null && Vec3d.ofBottomCenter(p).squaredDistanceTo(pl.getPos()) < 0.8;
    }

    /** Steps along the path; returns true while the player is moving (false = no movement for several ticks). */
    boolean follow(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (path.size() < 2) { release(client); return false; }
        BlockPos node = path.get(idx);
        Vec3d c = Vec3d.ofBottomCenter(node);
        double dxz = Math.hypot(c.x - p.getX(), c.z - p.getZ());
        if (dxz < 0.45 && Math.abs(c.y - p.getY()) < 1.3 && idx < path.size() - 1) {
            idx++;
            node = path.get(idx);
            c = Vec3d.ofBottomCenter(node);
            dxz = Math.hypot(c.x - p.getX(), c.z - p.getZ());
        }
        float wantYaw = (float) Math.toDegrees(Math.atan2(-(c.x - p.getX()), c.z - p.getZ()));
        p.setYaw(p.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(wantYaw - p.getYaw()), -MAX_TURN * 1.5f, MAX_TURN * 1.5f));
        p.setPitch(p.getPitch() + MathHelper.clamp(-p.getPitch(), -MAX_TURN, MAX_TURN));
        boolean facing = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())) < 35;
        client.options.forwardKey.setPressed(facing);
        client.options.sprintKey.setPressed(facing && dxz > 1.5);
        boolean needUp = c.y > p.getY() + 0.1 && dxz < 1.6;
        client.options.jumpKey.setPressed((needUp && p.isOnGround()) || p.isTouchingWater() || (p.horizontalCollision && p.isOnGround()));
        Vec3d now = p.getPos();
        stillTicks = now.squaredDistanceTo(lastPos) < 0.0004 ? stillTicks + 1 : 0;
        lastPos = now;
        return stillTicks < 10;
    }

    static void release(MinecraftClient c) {
        c.options.forwardKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.sprintKey.setPressed(false);
    }

    static boolean faceToward(ClientPlayerEntity p, Vec3d aim, float tolerance) {
        Vec3d eye = p.getEyePos();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float dyaw = MathHelper.wrapDegrees(wantYaw - p.getYaw()), dpitch = wantPitch - p.getPitch();
        p.setYaw(p.getYaw() + MathHelper.clamp(dyaw, -MAX_TURN, MAX_TURN));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(dpitch, -MAX_TURN, MAX_TURN), -90, 90));
        return Math.abs(dyaw) < tolerance && Math.abs(dpitch) < tolerance;
    }
}

package dev.rareradar;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/** Small client-side A* over walkable block positions (walk, 1-block step up, up to 3-block drop, diagonals). */
public final class Pathfinder {
    private static final int MAX_NODES = 6000;
    /** Wall-clock budget per search so a single plan can never stall a client tick. */
    private static final long MAX_NANOS = 4_000_000L;
    private static final int MAX_DROP = 3;
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE, Blocks.POINTED_DRIPSTONE, Blocks.TNT);
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private static final class Node {
        final BlockPos pos; final double g, f; final Node parent;
        Node(BlockPos pos, double g, double f, Node parent) { this.pos = pos; this.g = g; this.f = f; this.parent = parent; }
    }

    private Pathfinder() {}

    /** Path to within {@code reach} blocks (horizontal) of goal; if goal is too far, returns the path to the closest explored node. */
    public static List<BlockPos> find(ClientWorld w, BlockPos start, BlockPos goal, double reach) {
        if (w == null || start == null || goal == null) return List.of();
        BlockPos s = settle(w, start);
        if (s == null) return List.of();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Double> best = new HashMap<>();
        Node first = new Node(s, 0, h(s, goal), null);
        open.add(first);
        best.put(s, 0.0);
        Node closest = first;
        int expanded = 0;
        long deadline = System.nanoTime() + MAX_NANOS;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            if ((expanded & 127) == 0 && System.nanoTime() > deadline) break; // return best partial path
            Node cur = open.poll();
            if (cur.g > best.getOrDefault(cur.pos, Double.MAX_VALUE)) continue;
            if (h(cur.pos, goal) < h(closest.pos, goal)) closest = cur;
            if (hdist(cur.pos, goal) <= reach && Math.abs(cur.pos.getY() - goal.getY()) <= 3) { closest = cur; break; }
            for (int[] d : DIRS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                // Diagonal moves need both corner columns clear at foot AND head height (V1 only checked feet).
                if (diag && !(passable(w, cur.pos.add(d[0], 0, 0)) && passable(w, cur.pos.add(d[0], 1, 0))
                        && passable(w, cur.pos.add(0, 0, d[1])) && passable(w, cur.pos.add(0, 1, d[1])))) continue;
                BlockPos next = step(w, cur.pos, d[0], d[1]);
                if (next == null) continue;
                double cost = (diag ? 1.414 : 1.0) + Math.abs(next.getY() - cur.pos.getY()) * 0.5
                        + (w.getFluidState(next).isIn(FluidTags.WATER) ? 3.0 : 0.0);
                double g = cur.g + cost;
                if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
                    best.put(next, g);
                    open.add(new Node(next, g, g + h(next, goal), cur));
                }
            }
        }
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Node n = closest; n != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        return out;
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX()), dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(a.getY() - b.getY()) * 0.5;
    }

    private static double hdist(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Neighbour reachable by moving (dx,dz): flat, 1 up, or up to MAX_DROP down. */
    private static BlockPos step(ClientWorld w, BlockPos from, int dx, int dz) {
        BlockPos flat = from.add(dx, 0, dz);
        if (standable(w, flat)) return flat;
        BlockPos up = from.add(dx, 1, dz);
        if (passable(w, from.up(2)) && standable(w, up)) return up; // headroom to jump
        if (passable(w, flat) && passable(w, flat.up())) {
            for (int i = 1; i <= MAX_DROP; i++) {
                BlockPos down = from.add(dx, -i, dz);
                if (standable(w, down)) return down;
                if (!passable(w, down)) break;
            }
        }
        return null;
    }

    private static BlockPos settle(ClientWorld w, BlockPos p) {
        for (int i = 0; i <= 4; i++) {
            BlockPos q = p.down(i);
            if (standable(w, q)) return q;
        }
        return standable(w, p) ? p : null;
    }

    private static boolean loaded(ClientWorld w, BlockPos p) {
        return w.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
    }

    /** Body can occupy this block: no collision, nothing harmful. */
    private static boolean passable(ClientWorld w, BlockPos p) {
        if (!loaded(w, p)) return false;
        BlockState s = w.getBlockState(p);
        if (DANGEROUS.contains(s.getBlock()) || s.getFluidState().isIn(FluidTags.LAVA)) return false;
        return s.getCollisionShape(w, p).isEmpty();
    }

    /** Feet at p, head clear, and a floor below (solid with top <= 1 block, or water to swim on). */
    private static boolean standable(ClientWorld w, BlockPos p) {
        if (!passable(w, p) || !passable(w, p.up())) return false;
        BlockPos below = p.down();
        BlockState b = w.getBlockState(below);
        if (DANGEROUS.contains(b.getBlock())) return false;
        if (b.getFluidState().isIn(FluidTags.LAVA)) return false;
        if (b.getFluidState().isIn(FluidTags.WATER)) return true;
        var shape = b.getCollisionShape(w, below);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) <= 1.0;
    }
}

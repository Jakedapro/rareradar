package dev.rareradar.lab;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Labels a planned route with simple terrain tags so failures can be grouped (e.g. "water_boundary"). */
final class NavTerrain {
    private NavTerrain() {}

    static boolean air(ClientWorld w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty() && !w.getFluidState(p).isIn(FluidTags.WATER) && !w.getFluidState(p).isIn(FluidTags.LAVA);
    }

    static List<String> classify(ClientWorld w, List<BlockPos> path, double pathLen, double straight) {
        List<String> tags = new ArrayList<>();
        if (path.isEmpty()) return tags;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, water = 0, foliage = 0;
        for (BlockPos n : path) {
            minY = Math.min(minY, n.getY());
            maxY = Math.max(maxY, n.getY());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = n.add(dx, 0, dz);
                BlockState s = w.getBlockState(q);
                if (s.getFluidState().isIn(FluidTags.WATER) || w.getBlockState(q.down()).getFluidState().isIn(FluidTags.WATER)) water++;
                if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)) foliage++;
            }
        }
        if (water > 0) tags.add("water_boundary");
        if (foliage >= 6) tags.add("forest");
        if (maxY - minY >= 4) tags.add("hills");
        else if (maxY - minY <= 1) tags.add("flat");
        if (straight > 0 && pathLen / straight > 1.4) tags.add("detour");
        return tags;
    }
}

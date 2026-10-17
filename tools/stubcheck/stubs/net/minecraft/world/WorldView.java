package net.minecraft.world;
public interface WorldView extends BlockView {
    default net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> getBiome(net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
}

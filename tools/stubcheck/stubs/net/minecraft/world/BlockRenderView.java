package net.minecraft.world;
public interface BlockRenderView extends BlockView {
    default boolean isSkyVisible(net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
    default int getLightLevel(LightType type, net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
}

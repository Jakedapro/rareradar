package net.minecraft.world;
public interface BlockView {
    net.minecraft.block.BlockState getBlockState(net.minecraft.util.math.BlockPos pos);
    net.minecraft.fluid.FluidState getFluidState(net.minecraft.util.math.BlockPos pos);
}

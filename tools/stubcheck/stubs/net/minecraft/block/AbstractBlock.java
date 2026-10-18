package net.minecraft.block;
public abstract class AbstractBlock {
    public abstract static class AbstractBlockState {
        public boolean isIn(net.minecraft.registry.tag.TagKey<Block> tag) { throw new UnsupportedOperationException(); }
        public Block getBlock() { throw new UnsupportedOperationException(); }
        public net.minecraft.fluid.FluidState getFluidState() { throw new UnsupportedOperationException(); }
        public net.minecraft.util.shape.VoxelShape getCollisionShape(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
    }
}

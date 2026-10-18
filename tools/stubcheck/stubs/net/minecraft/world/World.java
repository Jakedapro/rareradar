package net.minecraft.world;
public abstract class World implements WorldAccess, AutoCloseable {
    public net.minecraft.registry.RegistryKey<World> getRegistryKey() { throw new UnsupportedOperationException(); }
    public long getTimeOfDay() { throw new UnsupportedOperationException(); }
    public boolean isThundering() { throw new UnsupportedOperationException(); }
    public net.minecraft.scoreboard.Scoreboard getScoreboard() { throw new UnsupportedOperationException(); }
    public boolean isRaining() { throw new UnsupportedOperationException(); }
    public abstract net.minecraft.entity.Entity getEntityById(int id);
    @Override public <T extends net.minecraft.entity.Entity> java.util.List<T> getEntitiesByClass(Class<T> cls, net.minecraft.util.math.Box box, java.util.function.Predicate<? super T> pred) { throw new UnsupportedOperationException(); }
    @Override public net.minecraft.block.BlockState getBlockState(net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
    @Override public net.minecraft.fluid.FluidState getFluidState(net.minecraft.util.math.BlockPos pos) { throw new UnsupportedOperationException(); }
}

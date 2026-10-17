package net.minecraft.world;
public interface EntityView {
    default <T extends net.minecraft.entity.Entity> java.util.List<T> getEntitiesByClass(Class<T> cls, net.minecraft.util.math.Box box, java.util.function.Predicate<? super T> pred) { throw new UnsupportedOperationException(); }
}

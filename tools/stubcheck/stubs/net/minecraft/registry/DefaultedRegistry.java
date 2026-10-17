package net.minecraft.registry;
public interface DefaultedRegistry<T> extends Registry<T> {
    @Override net.minecraft.util.Identifier getId(T value);
}

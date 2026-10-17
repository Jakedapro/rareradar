package net.minecraft.registry;
public interface Registry<T> {
    net.minecraft.util.Identifier getId(T value);
}

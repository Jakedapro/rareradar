package net.minecraft.registry.entry;
public interface RegistryEntry<T> {
    java.util.Optional<net.minecraft.registry.RegistryKey<T>> getKey();
}

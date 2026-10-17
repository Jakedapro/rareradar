package net.minecraft.registry.entry;
// yarn: RegistryEntry.getKey() (method_40230), streamTags() (method_40228) -> Stream<TagKey<T>>
public interface RegistryEntry<T> {
    java.util.Optional<net.minecraft.registry.RegistryKey<T>> getKey();
    java.util.stream.Stream<net.minecraft.registry.tag.TagKey<T>> streamTags();
}

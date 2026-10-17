package net.fabricmc.loader.api;
public interface FabricLoader {
    static FabricLoader getInstance() { throw new UnsupportedOperationException(); }
    java.util.Optional<ModContainer> getModContainer(String id);
    boolean isModLoaded(String id);
    java.nio.file.Path getConfigDir();
    java.nio.file.Path getGameDir();
}

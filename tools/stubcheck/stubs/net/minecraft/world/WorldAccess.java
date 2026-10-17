package net.minecraft.world;
public interface WorldAccess extends WorldView, EntityView {
    net.minecraft.world.chunk.ChunkManager getChunkManager();
}

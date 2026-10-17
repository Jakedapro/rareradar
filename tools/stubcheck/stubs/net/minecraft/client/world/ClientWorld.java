package net.minecraft.client.world;
public class ClientWorld extends net.minecraft.world.World {
    public Iterable<net.minecraft.entity.Entity> getEntities() { throw new UnsupportedOperationException(); }
    @Override public net.minecraft.entity.Entity getEntityById(int id) { throw new UnsupportedOperationException(); }
    @Override public ClientChunkManager getChunkManager() { throw new UnsupportedOperationException(); }
    @Override public void close() {}
}

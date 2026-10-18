package net.minecraft.client;
public class MinecraftClient extends net.minecraft.util.thread.ReentrantThreadExecutor<Runnable> {
    public net.minecraft.client.world.ClientWorld world;
    public net.minecraft.client.network.ClientPlayerEntity player;
    public final net.minecraft.client.option.GameOptions options = null;
    public net.minecraft.client.gui.screen.Screen currentScreen;
    public net.minecraft.client.network.ClientPlayerInteractionManager interactionManager;
    public final net.minecraft.client.font.TextRenderer textRenderer = null;
    public static MinecraftClient getInstance() { throw new UnsupportedOperationException(); }
    public void setScreen(net.minecraft.client.gui.screen.Screen s) { throw new UnsupportedOperationException(); }
    public int getCurrentFps() { throw new UnsupportedOperationException(); }
    public boolean isInSingleplayer() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.network.ServerInfo getCurrentServerEntry() { throw new UnsupportedOperationException(); }
    public boolean hasOutline(net.minecraft.entity.Entity e) { throw new UnsupportedOperationException(); }
}

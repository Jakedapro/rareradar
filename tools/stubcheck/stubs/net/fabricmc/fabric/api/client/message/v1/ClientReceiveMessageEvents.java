package net.fabricmc.fabric.api.client.message.v1;
// Fabric API 1.21.1 fabric-message-api-v1: Event<Game> GAME; Game.onReceiveGameMessage(Text message, boolean overlay)
public final class ClientReceiveMessageEvents {
    public static final net.fabricmc.fabric.api.event.Event<Game> GAME = null;
    @FunctionalInterface public interface Game { void onReceiveGameMessage(net.minecraft.text.Text message, boolean overlay); }
}

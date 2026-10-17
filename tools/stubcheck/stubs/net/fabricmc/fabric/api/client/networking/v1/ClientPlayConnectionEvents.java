package net.fabricmc.fabric.api.client.networking.v1;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
public final class ClientPlayConnectionEvents {
    public static final Event<Init> INIT = null;
    public static final Event<Join> JOIN = null;
    public static final Event<Disconnect> DISCONNECT = null;
    private ClientPlayConnectionEvents() {}
    @FunctionalInterface public interface Init { void onPlayInit(ClientPlayNetworkHandler handler, MinecraftClient client); }
    @FunctionalInterface public interface Join { void onPlayReady(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client); }
    @FunctionalInterface public interface Disconnect { void onPlayDisconnect(ClientPlayNetworkHandler handler, MinecraftClient client); }
}

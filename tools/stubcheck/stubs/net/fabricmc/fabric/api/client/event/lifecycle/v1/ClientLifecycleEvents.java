package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.MinecraftClient;
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {}
    public static final Event<ClientStarted> CLIENT_STARTED = null;
    public static final Event<ClientStopping> CLIENT_STOPPING = null;
    @FunctionalInterface public interface ClientStarted { void onClientStarted(MinecraftClient client); }
    @FunctionalInterface public interface ClientStopping { void onClientStopping(MinecraftClient client); }
}

package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.MinecraftClient;
public final class ClientTickEvents {
    private ClientTickEvents() {}
    public static final Event<StartTick> START_CLIENT_TICK = null;
    public static final Event<EndTick> END_CLIENT_TICK = null;
    @FunctionalInterface public interface StartTick { void onStartTick(MinecraftClient client); }
    @FunctionalInterface public interface EndTick { void onEndTick(MinecraftClient client); }
}

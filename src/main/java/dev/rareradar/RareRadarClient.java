package dev.rareradar;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RareRadarClient implements ClientModInitializer {
    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("RareRadar");
    public static volatile boolean enabled = true;
    private static final double RANGE = 160.0;
    private static final int MAX_ROWS = 8;

    private record Hit(PokemonEntity entity, Rarity rarity, double dist) {}

    private static final List<Hit> hits = new ArrayList<>();
    private static KeyBinding toggleKey;
    private static KeyBinding huntKey;

    @Override
    public void onInitializeClient() {
        LOG.info("Rare Radar loaded");
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rareradar.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.rareradar"));

        huntKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rareradar.hunt", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.rareradar"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (huntKey.wasPressed()) AutoHunt.toggle(client);
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                LOG.info("Radar {}", enabled ? "enabled" : "disabled");
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("Rare Radar: " + (enabled ? "ON" : "OFF")), true);
                }
            }
            scan(client);
            AutoHunt.tick(client);
        });

        HudRenderCallback.EVENT.register(RareRadarClient::render);
    }

    private static void scan(MinecraftClient client) {
        hits.clear();
        if (!enabled || client.world == null || client.player == null) return;
        Vec3d me = client.player.getPos();
        for (var e : client.world.getEntities()) {
            if (e instanceof PokemonEntity pe && e.squaredDistanceTo(me) <= RANGE * RANGE) {
                Rarity r = Rarity.of(pe);
                if (r != null) hits.add(new Hit(pe, r, Math.sqrt(e.squaredDistanceTo(me))));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::dist));
    }

    private static String arrow(MinecraftClient client, PokemonEntity e) {
        Vec3d d = e.getPos().subtract(client.player.getPos());
        double target = Math.toDegrees(Math.atan2(-d.x, d.z));
        double rel = MathHelper.wrapDegrees(target - client.player.getYaw());
        if (rel > -22.5 && rel <= 22.5) return "↑";
        if (rel > 22.5 && rel <= 67.5) return "↗";
        if (rel > 67.5 && rel <= 112.5) return "→";
        if (rel > 112.5 && rel <= 157.5) return "↘";
        if (rel > -67.5 && rel <= -22.5) return "↖";
        if (rel > -112.5 && rel <= -67.5) return "←";
        if (rel > -157.5 && rel <= -112.5) return "↙";
        return "↓";
    }

    private static void render(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || client.options.hudHidden || hits.isEmpty()) return;
        int x = 6, y = 6;
        ctx.drawTextWithShadow(client.textRenderer, "Rare Radar", x, y, 0xFFFFFF);
        y += 11;
        int shown = 0;
        for (Hit h : new ArrayList<>(hits)) {
            if (shown++ >= MAX_ROWS) break;
            String name = h.entity.getPokemon().getSpecies().getName();
            String line = String.format("%s %s [%s] %dm  (%d, %d, %d)", arrow(client, h.entity), name, h.rarity.label,
                    (int) h.dist, h.entity.getBlockX(), h.entity.getBlockY(), h.entity.getBlockZ());
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, h.rarity.color);
            y += 10;
        }
        if (hits.size() > MAX_ROWS) {
            ctx.drawTextWithShadow(client.textRenderer, "+" + (hits.size() - MAX_ROWS) + " more", x, y, 0xAAAAAA);
        }
    }
}

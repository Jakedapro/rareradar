package dev.rareradar;

import dev.rareradar.core.util.Log;
import dev.rareradar.ui.DashboardScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class RareRadarClient implements ClientModInitializer {
    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("RareRadar");
    public static boolean enabled = true;

    private static KeyBinding toggleKey, huntKey, trainKey, menuKey, settingsKey, stopKey;
    private static int inventoryTick;

    @Override
    public void onInitializeClient() {
        Log.setSink((level, msg, t) -> {
            switch (level) {
                case "ERROR" -> LOG.error(msg, t);
                case "WARN" -> { if (t == null) LOG.warn(msg); else LOG.warn(msg, t); }
                default -> LOG.info(msg);
            }
        });
        Settings.load();
        Galaxy.init();
        dev.rareradar.lab.LabRuntime.init();
        checkCobblemonVersion();
        LOG.info("Rare Radar V3 loaded");

        toggleKey = key("key.rareradar.toggle", GLFW.GLFW_KEY_J);
        huntKey = key("key.rareradar.hunt", GLFW.GLFW_KEY_K);
        trainKey = key("key.rareradar.train", GLFW.GLFW_KEY_L);
        menuKey = key("key.rareradar.menu", GLFW.GLFW_KEY_GRAVE_ACCENT);
        settingsKey = key("key.rareradar.settings", GLFW.GLFW_KEY_UNKNOWN);
        stopKey = key("key.rareradar.stop", GLFW.GLFW_KEY_BACKSPACE);

        ClientTickEvents.END_CLIENT_TICK.register(RareRadarClient::tick);
        GtsCapture.register();
        LOG.info("[GTS capture] CobbleGalaxy client mod {}; chat capture {}, chest-menu capture {}, CobbleGalaxy-screen capture {}; {} stored price observations",
                CobbleGalaxyBridge.present() ? "installed" : "NOT installed (its custom /gts screen cannot be read)",
                Galaxy.get().cfg.captureGtsChat ? "on" : "off", Galaxy.get().cfg.captureGtsChestMenu ? "on" : "off",
                Galaxy.get().cfg.cobbleGalaxyBridge && Galaxy.get().cfg.captureGtsListings ? "on" : "off", Galaxy.get().market.size());
        HudRenderCallback.EVENT.register(RadarHud::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            RadarScanner.clear();
            String address = client.getCurrentServerEntry() == null ? null : client.getCurrentServerEntry().address;
            String world = client.isInSingleplayer() ? "singleplayer" : address == null ? "multiplayer" : address;
            Galaxy g = Galaxy.get();
            g.sessions.begin(System.currentTimeMillis(), world);
            var profile = g.profiles.resolve(client.isInSingleplayer(), address);
            g.controller.setProfile(profile, System.currentTimeMillis());
            g.saveProfiles();
            LOG.info("[Automation] connected to {} → profile '{}' (automation {}{})", world, profile.id,
                    profile.automationAuthorized ? "authorised: " + profile.authorizationSource : "NOT authorised",
                    profile.requireUserEnable ? ", needs enabling each session" : "");
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            AutoHunt.stop(client, "disconnected");
            dev.rareradar.lab.LabRuntime.stop("disconnected");
            MoneyCapture.onDisconnect();
            Galaxy.get().controller.setProfile(null, System.currentTimeMillis());
            RadarScanner.clear();
            Galaxy g = Galaxy.get();
            g.sessions.end(System.currentTimeMillis());
            g.exposure.save();
            g.saveConfig();
        }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AutoHunt.stop(client, "game closing");
            dev.rareradar.lab.LabRuntime.stop("game closing");
            Galaxy g = Galaxy.get();
            g.sessions.end(System.currentTimeMillis());
            Settings.save();
            g.shutdown();
        });
    }

    private static KeyBinding key(String id, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, code, "key.categories.rareradar"));
    }

    private static void tick(MinecraftClient client) {
        // Emergency stop first, and it works even while a screen is open.
        while (stopKey.wasPressed()) {
            Galaxy.get().controller.emergencyStop(System.currentTimeMillis(), "emergency stop key");
            AutoHunt.stop(client, "emergency stop key");
            dev.rareradar.lab.LabRuntime.emergencyStop("emergency stop key");
        }
        while (menuKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new DashboardScreen());
        while (settingsKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new SettingsScreen());
        while (trainKey.wasPressed()) AutoHunt.toggleTrain(client);
        while (huntKey.wasPressed()) AutoHunt.toggle(client);
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            LOG.info("Radar {}", enabled ? "enabled" : "disabled");
            Settings.save();
            if (client.player != null) client.player.sendMessage(Text.literal("Rare Radar: " + (enabled ? "ON" : "OFF")), true);
        }
        try {
            Galaxy g = Galaxy.get();
            g.adoptModel();
            RadarScanner.tick(client);
            CobbleGalaxyBridge.tick(client);
            GtsCapture.tick(client);
            MoneyCapture.tick(client);
            AutoHunt.tick(client);
            dev.rareradar.lab.LabRuntime.tick(client);
            if (client.player != null) {
                ++inventoryTick;
                if (inventoryTick % 40 == 0) countInventory(client);
                // Crash safety: persist observation time and the live session every 2 minutes so rates stay honest.
                if (inventoryTick % 2400 == 0) { g.exposure.save(); g.sessions.checkpoint(); }
                if (inventoryTick % 6000 == 0) GtsCapture.logSummaries();
            }
        } catch (RuntimeException | LinkageError e) {
            // Fail safe: stop automation and release keys, keep the game running, log with stack trace.
            LOG.error("RareRadar tick failed", e);
            try { AutoHunt.stop(client, "internal error (see log)"); } catch (RuntimeException | LinkageError ignored) { /* already logged */ }
            Navigator.release(client);
            dev.rareradar.lab.LabRuntime.emergencyStop("internal error in tick");
        }
    }

    /** Updates resource counts for the tracked items (cheap: 41 slots every 2 seconds). */
    private static void countInventory(MinecraftClient client) {
        var book = Galaxy.get().cfg.resources;
        Map<String, Integer> counts = new HashMap<>();
        var inv = client.player.getInventory();
        for (ItemStack s : inv.main) add(counts, s);
        for (ItemStack s : inv.offHand) add(counts, s);
        for (var r : book.resources.values()) r.count = counts.getOrDefault(r.id, 0);
    }

    private static void add(Map<String, Integer> counts, ItemStack s) {
        if (s.isEmpty()) return;
        counts.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
    }

    private static void checkCobblemonVersion() {
        FabricLoader.getInstance().getModContainer("cobblemon").ifPresent(c -> {
            String v = c.getMetadata().getVersion().getFriendlyString();
            if (!v.startsWith("1.8") && !v.startsWith("1.7") && !v.startsWith("1.6"))
                LOG.warn("RareRadar was written against Cobblemon 1.8.2; found {}. Features that hit API changes disable themselves.", v);
            else LOG.info("Cobblemon {} detected", v);
        });
    }
}

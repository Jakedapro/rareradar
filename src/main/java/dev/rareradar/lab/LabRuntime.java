package dev.rareradar.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.rareradar.Galaxy;
import dev.rareradar.core.lab.*;
import dev.rareradar.core.util.Log;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Wires RareRadar Lab into the client: one controller, ticked from the client tick, stopped on disconnect or the
 * emergency-stop key. Lab settings and logs live under config/rareradar/lab/ and never touch production data.
 */
public final class LabRuntime {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static LabController controller;
    private static LabConfig config = new LabConfig();
    private static Path labDir;
    private static final McLabHost HOST = new McLabHost();
    /** The capture scenario sets this while it drives AutoHunt on purpose. */
    static volatile boolean autoHuntAllowed;

    private LabRuntime() {}

    public static void init() {
        try {
            labDir = Galaxy.get().dir.resolve("lab");
            Files.createDirectories(labDir);
            config = load();
            ScenarioRegistry reg = CoreScenarios.register(new ScenarioRegistry());
            McScenarios.register(reg);
            controller = new LabController(HOST, reg, labDir, Galaxy.get().io, buildInfo(), () -> config.allowedServers);
            Log.info("RareRadar Lab ready (suites: " + reg.suiteNames() + ") — logs in " + labDir);
        } catch (IOException | RuntimeException e) {
            Log.error("RareRadar Lab failed to initialise; Lab disabled", e);
            controller = null;
        }
    }

    public static boolean available() { return controller != null; }
    public static LabController controller() { return controller; }
    public static LabConfig config() { return config; }
    public static Path dir() { return labDir; }
    public static boolean running() { return controller != null && controller.running(); }

    /** True while the Lab owns movement; AutoHunt must not start unless the Lab itself is driving it. */
    public static boolean blocksAutoHunt() { return running() && !autoHuntAllowed; }

    public static EnvironmentVerifier.Env environment() { return HOST.environment(); }

    public static EnvironmentVerifier.Verdict verdict() { return EnvironmentVerifier.verify(HOST.environment(), config.allowedServers); }

    public static String start(SuiteConfig cfg) {
        if (controller == null) return "Lab unavailable";
        if (dev.rareradar.AutoHunt.active) return "Lab refused: stop Auto Hunt/Train first";
        config.last = cfg.copy();
        saveConfig();
        return controller.start(cfg, System.currentTimeMillis()).message();
    }

    public static void tick(MinecraftClient client) {
        if (controller == null || !controller.running()) return;
        controller.tick(System.currentTimeMillis());
    }

    public static void emergencyStop(String why) {
        if (controller != null) controller.emergencyStop(System.currentTimeMillis(), why);
    }

    public static void stop(String why) {
        if (controller != null) controller.stop(System.currentTimeMillis(), why);
    }

    public static void saveConfig() {
        if (labDir == null) return;
        try { Files.writeString(labDir.resolve("lab_config.json"), GSON.toJson(config.sanitize()), StandardCharsets.UTF_8); }
        catch (IOException e) { Log.warn("Lab: cannot save lab_config.json", e); }
    }

    private static LabConfig load() {
        Path f = labDir.resolve("lab_config.json");
        if (Files.exists(f)) {
            try { LabConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LabConfig.class); if (c != null) return c.sanitize(); }
            catch (IOException | RuntimeException e) { Log.warn("Lab: unreadable lab_config.json, using defaults", e); }
        }
        return new LabConfig().sanitize();
    }

    private static BuildInfo buildInfo() {
        String mod = ver("rareradar"), mc = ver("minecraft"), fabric = ver("fabricloader"), cobble = ver("cobblemon");
        String sha = "unknown";
        try (InputStream in = LabRuntime.class.getResourceAsStream("/rareradar-build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String s = p.getProperty("git_sha", "unknown");
                if (!s.isBlank() && !s.startsWith("$")) sha = s;
            }
        } catch (IOException ignored) { /* stays unknown */ }
        return new BuildInfo(mod, sha, mc, fabric, cobble);
    }

    private static String ver(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
    }

    /** Opens the folder in the system file manager (no Minecraft API needed; works on macOS, Windows and Linux). */
    public static boolean openFolder(Path p) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            String[] cmd = os.contains("mac") ? new String[]{"open", p.toString()} : os.contains("win") ? new String[]{"explorer", p.toString()} : new String[]{"xdg-open", p.toString()};
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }
}

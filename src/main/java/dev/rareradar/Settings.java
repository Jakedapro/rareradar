package dev.rareradar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** User settings, saved to config/rareradar.json and edited from the ` (tilde) screen. */
public final class Settings {
    public boolean radarEnabled = true;
    public boolean outlines = true;
    public boolean hudList = true;
    public int scanRange = 160;        // blocks; the client can only see what the server has sent it
    public int hudRows = 8;
    public boolean shiny = true, legendary = true, mythical = true, ultraBeast = true, paradox = true;
    public int throwRange = 9;         // blocks, catch mode
    public int trainRange = 6;         // blocks, training mode
    public int levelWindow = 6;        // +/- levels of your lead for training targets
    public int minLeadHpPercent = 40;  // training stops below this

    public static Settings S = new Settings();

    /** Clamps hand-edited values into the ranges the settings screen allows. */
    void sanitize() {
        scanRange = clamp(scanRange, 16, 1000);
        hudRows = clamp(hudRows, 1, 20);
        throwRange = clamp(throwRange, 3, 20);
        trainRange = clamp(trainRange, 3, 15);
        levelWindow = clamp(levelWindow, 0, 30);
        minLeadHpPercent = clamp(minLeadHpPercent, 10, 100);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("rareradar.json"); }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Settings s = GSON.fromJson(Files.readString(f), Settings.class);
                if (s != null) S = s;
            }
            S.sanitize();
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not read config/rareradar.json ({}); using defaults (bad file kept as .invalid)", e.toString());
            try { Files.copy(file(), file().resolveSibling("rareradar.json.invalid"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) { /* best effort; the warning above reports the problem */ }
            S = new Settings();
        }
        RareRadarClient.enabled = S.radarEnabled;
    }

    public static void save() {
        try {
            S.radarEnabled = RareRadarClient.enabled;
            Galaxy.writeAtomic(file(), GSON.toJson(S));
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not save settings: {}", e.toString());
        }
    }
}

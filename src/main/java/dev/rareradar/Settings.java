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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("rareradar.json"); }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Settings s = GSON.fromJson(Files.readString(f), Settings.class);
                if (s != null) S = s;
            }
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not read settings: {}", e.toString());
        }
        RareRadarClient.enabled = S.radarEnabled;
    }

    public static void save() {
        try {
            S.radarEnabled = RareRadarClient.enabled;
            Files.writeString(file(), GSON.toJson(S));
        } catch (Exception e) {
            RareRadarClient.LOG.warn("Could not save settings: {}", e.toString());
        }
    }
}

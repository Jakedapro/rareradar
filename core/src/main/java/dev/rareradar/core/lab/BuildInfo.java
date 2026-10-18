package dev.rareradar.core.lab;

/** Versions recorded in every session (all "unknown" when not available; git SHA only if baked in at build time). */
public record BuildInfo(String modVersion, String gitSha, String minecraftVersion, String fabricVersion, String cobblemonVersion) {
    public static BuildInfo unknown() { return new BuildInfo("unknown", "unknown", "unknown", "unknown", "unknown"); }
}

package dev.rareradar.lab;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.rareradar.Navigator;
import dev.rareradar.Pathfinder;
import dev.rareradar.RadarScanner;
import dev.rareradar.core.lab.EnvironmentVerifier;
import dev.rareradar.core.lab.LabHost;
import dev.rareradar.core.lab.PerfSample;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/** Minecraft implementation of the Lab host: environment facts, control release, performance readings. */
final class McLabHost implements LabHost {
    @Override
    public EnvironmentVerifier.Env environment() {
        MinecraftClient c = MinecraftClient.getInstance();
        boolean cobblemon = FabricLoader.getInstance().isModLoaded("cobblemon");
        if (c == null || c.world == null || c.player == null) return new EnvironmentVerifier.Env(EnvironmentVerifier.Kind.NOT_IN_WORLD, null, cobblemon);
        if (c.isInSingleplayer()) return new EnvironmentVerifier.Env(EnvironmentVerifier.Kind.SINGLEPLAYER, null, cobblemon);
        var info = c.getCurrentServerEntry();
        return new EnvironmentVerifier.Env(EnvironmentVerifier.Kind.MULTIPLAYER, info == null ? null : info.address, cobblemon);
    }

    @Override
    public void releaseControls() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.options != null) Navigator.release(c);
    }

    @Override
    public PerfSample samplePerf() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null) return null;
        Runtime rt = Runtime.getRuntime();
        double heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        int pokemon = 0;
        if (c.world != null) for (Entity e : c.world.getEntities()) if (e instanceof PokemonEntity) pokemon++;
        return new PerfSample(System.currentTimeMillis(), c.getCurrentFps(), 0, heapMb, pokemon, -1,
                RadarScanner.lastScanNanos / 1e6, RadarScanner.maxScanNanos / 1e6, Pathfinder.lastFindNanos / 1e6);
    }
}

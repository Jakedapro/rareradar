package dev.rareradar.core.lab;

/** One performance reading; -1 means "not available". */
public record PerfSample(long tMillis, int fps, double labTickMs, double heapUsedMb, int pokemonEntities, int loadedChunks,
                         double scannerLastMs, double scannerMaxMs, double pathfindMs) {
    public PerfSample withLabTick(double ms) {
        return new PerfSample(tMillis, fps, ms, heapUsedMb, pokemonEntities, loadedChunks, scannerLastMs, scannerMaxMs, pathfindMs);
    }
}

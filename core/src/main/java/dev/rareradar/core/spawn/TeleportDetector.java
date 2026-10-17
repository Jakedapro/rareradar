package dev.rareradar.core.spawn;

import java.util.Objects;

/**
 * Detects that the player was moved a long way at once (e.g. a manually run /wild, /home or warp) or changed
 * dimension, so local statistics can be reset and the radar refreshed. RareRadar never issues such commands.
 */
public final class TeleportDetector {
    private final double jumpBlocks;
    private String dim;
    private double x, z;
    private boolean has;

    public TeleportDetector(double jumpBlocks) { this.jumpBlocks = jumpBlocks; }

    /** Feed the current position each tick; returns true on a teleport/dimension change. */
    public boolean update(String dimension, double nx, double nz) {
        boolean tp = false;
        if (has) {
            if (!Objects.equals(dim, dimension)) tp = true;
            else if (Math.hypot(nx - x, nz - z) > jumpBlocks) tp = true;
        }
        dim = dimension; x = nx; z = nz; has = true;
        return tp;
    }

    public void reset() { has = false; }
}

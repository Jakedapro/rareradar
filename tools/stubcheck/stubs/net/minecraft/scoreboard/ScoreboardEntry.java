package net.minecraft.scoreboard;
/** A record in Minecraft; its component accessors are unmapped in Yarn 1.21.1, so RareRadar reads them reflectively. */
public final class ScoreboardEntry {
    public boolean hidden() { throw new UnsupportedOperationException(); }
    public net.minecraft.text.Text name() { throw new UnsupportedOperationException(); }
}

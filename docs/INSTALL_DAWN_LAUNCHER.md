# Installing RareRadar on Dawn Launcher (macOS, Apple Silicon)

You need an instance with **Minecraft 1.21.1** and **Fabric Loader** (0.17.2 or newer; 0.19.5 is confirmed to load RareRadar alpha.3), plus these mods:

| Mod | Why |
|---|---|
| Fabric API (for 1.21.1) | Required by RareRadar |
| Cobblemon 1.8.x for Fabric 1.21.1 | Required. RareRadar reads Pokémon through Cobblemon, and CobbleGalaxy's own mod also depends on it. |
| CobbleGalaxy client mod (optional) | Lets RareRadar read CobbleGalaxy's GTS screen, pinned hunts, PokéBuilder and Dex Rewards data |
| `rareradar-<version>.jar` | From https://github.com/Jakedapro/rareradar/releases/tag/dev-latest |

## Steps

1. In Dawn Launcher, open your 1.21.1 Fabric instance, then open its folder ("Open folder" / "Show in Finder").
2. Put the RareRadar jar into `mods/`. Remove any older `rareradar-*.jar` first, so that only one copy is installed.
3. Start the game.
4. Check `logs/latest.log` for `Rare Radar V3 loaded` and the `Knowledge base: … species` line.
5. Open the dashboard with **`** (the backtick key; on some macOS keyboard layouts this is the key left of `1`, `§`/`±`). You can rebind it under Options → Controls → Rare Radar.

Your data stays in `config/rareradar/` and is kept across upgrades.
Back it up any time from Dashboard → History → Backup.

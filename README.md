# Rare Radar

Client-side Fabric mod for Minecraft 1.21.1 + Cobblemon 1.8.2.

- Glow outline and HUD list for nearby wild shiny, legendary, mythical, Ultra Beast and Paradox Pokemon.
- `J` toggles the radar, `K` toggles Auto Hunt (A* path to the nearest rare, then throws Poke Balls from your hotbar).
- Logs to `logs/latest.log` under the `RareRadar` logger.

Only use Auto Hunt where the server rules allow automation (singleplayer or your own server).

## Build
Put a Cobblemon Fabric 1.21.1 jar at `libs/cobblemon.jar` (not included), then run `gradle build` with Java 21.

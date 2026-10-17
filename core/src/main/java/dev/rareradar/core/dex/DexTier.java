package dev.rareradar.core.dex;

/** A Pokédex reward milestone as sent by the server (Dex Rewards screen) or entered by the user. */
public record DexTier(String view, String id, String name, double percentage, boolean claimed, boolean claimable) {}

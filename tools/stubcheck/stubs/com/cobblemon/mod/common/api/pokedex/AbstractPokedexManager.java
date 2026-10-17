package com.cobblemon.mod.common.api.pokedex;
public abstract class AbstractPokedexManager {
    public java.util.Map<net.minecraft.util.Identifier, SpeciesDexRecord> getSpeciesRecords() { throw new UnsupportedOperationException(); }
    public final PokedexEntryProgress getKnowledgeForSpecies(net.minecraft.util.Identifier speciesId) { throw new UnsupportedOperationException(); }
}

package dev.rareradar.core.knowledge;

/** Dataset-level source of the knowledge base (written by tools/knowledge/build_knowledge.py). */
public record Provenance(String name, String repo, String version, String commit, boolean commitVerified,
                         String license, String confidence) {
    public static final Provenance NONE = new Provenance(null, null, null, null, false, null, null);
}

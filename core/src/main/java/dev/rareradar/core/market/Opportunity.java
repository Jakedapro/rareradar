package dev.rareradar.core.market;

import dev.rareradar.core.util.Confidence;

/** A flagged possibility, never a guarantee. {@code explanation} always states the evidence and its limits. */
public record Opportunity(Type type, String subject, double score, Confidence confidence, String explanation) {
    public enum Type { POTENTIALLY_UNDERVALUED, STRONG_DEMAND, HIGH_SHINY_PREMIUM, BREEDING_CANDIDATE, CONSIDER_KEEPING, CONSIDER_SELLING }
}

package dev.rareradar.core.automation;

/** Automation modules that can request control. Ordered by default priority (lower ordinal = higher priority). */
public enum Module {
    SAFETY("Safety"), CAPTURE("Capture"), HUNTING("Hunting"), TRAINING("Training"), NAVIGATION("Navigation"), TRADING("Trading");

    public final String label;

    Module(String label) { this.label = label; }
}

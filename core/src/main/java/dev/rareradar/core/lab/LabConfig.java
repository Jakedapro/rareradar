package dev.rareradar.core.lab;

import java.util.ArrayList;
import java.util.List;

/** Persistent Lab settings, kept in config/rareradar/lab/lab_config.json (separate from production settings). */
public final class LabConfig {
    /** Private/loopback multiplayer hosts the user explicitly approved for Lab automation. Empty by default. */
    public List<String> allowedServers = new ArrayList<>();
    public SuiteConfig last = new SuiteConfig();

    public LabConfig sanitize() {
        if (allowedServers == null) allowedServers = new ArrayList<>();
        if (last == null) last = new SuiteConfig();
        last.sanitize();
        return this;
    }
}

package dev.rareradar.core.automation;

/** One auditable automation event. */
public record AuditEvent(long ts, String server, Module module, String action, String detail, String outcome) {}

package com.chronos.tracker.jira;

public enum JiraSyncStatus {
    NOT_CONFIGURED("não configurado"),
    SYNCING("sincronizando..."),
    SYNCED("✓ sincronizado"),
    ERROR("✗ erro de conexão");

    private final String label;

    JiraSyncStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

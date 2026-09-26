package com.chronos.tracker.jira;

public enum JiraSyncStatus {
    NOT_CONFIGURED("não configurado"),
    SYNCING("sincronizando..."),
    SYNCED("✓ sincronizado"),
    AUTH_ERROR("✗ credenciais inválidas"),
    QUERY_ERROR("✗ consulta recusada"),
    ERROR("✗ erro de conexão");

    private final String label;

    JiraSyncStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isError() {
        return this == AUTH_ERROR || this == QUERY_ERROR || this == ERROR;
    }
}

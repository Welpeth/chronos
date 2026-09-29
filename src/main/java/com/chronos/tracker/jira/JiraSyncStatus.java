package com.chronos.tracker.jira;

import com.chronos.tracker.config.I18n;

public enum JiraSyncStatus {
    NOT_CONFIGURED,
    SYNCING,
    SYNCED,
    AUTH_ERROR,
    QUERY_ERROR,
    ERROR;

    /** Texto no idioma atual (traduzido a cada chamada, pois o idioma pode mudar com o app aberto). */
    public String label() {
        return switch (this) {
            case NOT_CONFIGURED -> I18n.t("não configurado");
            case SYNCING -> I18n.t("sincronizando...");
            case SYNCED -> I18n.t("✓ sincronizado");
            case AUTH_ERROR -> I18n.t("✗ credenciais inválidas");
            case QUERY_ERROR -> I18n.t("✗ consulta recusada");
            case ERROR -> I18n.t("✗ erro de conexão");
        };
    }

    public boolean isError() {
        return this == AUTH_ERROR || this == QUERY_ERROR || this == ERROR;
    }
}

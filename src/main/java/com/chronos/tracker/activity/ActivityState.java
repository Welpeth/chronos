package com.chronos.tracker.activity;

import com.chronos.tracker.config.I18n;

/**
 * Estado de atividade do usuário, derivado do tempo desde a última interação.
 */
public enum ActivityState {
    ACTIVE,
    POSSIBLY_IDLE,
    INACTIVE;

    /** Texto no idioma atual (traduzido a cada chamada, pois o idioma pode mudar com o app aberto). */
    public String label() {
        return switch (this) {
            case ACTIVE -> I18n.t("ATIVO");
            case POSSIBLY_IDLE -> I18n.t("POSSIVELMENTE IDLE");
            case INACTIVE -> I18n.t("INATIVO");
        };
    }
}

package com.chronos.tracker.activity;

/**
 * Estado de atividade do usuário, derivado do tempo desde a última interação.
 */
public enum ActivityState {
    ACTIVE("ATIVO"),
    POSSIBLY_IDLE("POSSIVELMENTE IDLE"),
    INACTIVE("INATIVO");

    private final String label;

    ActivityState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

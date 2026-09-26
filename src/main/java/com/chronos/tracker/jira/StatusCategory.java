package com.chronos.tracker.jira;

/**
 * Categoria de status do Jira. Cada status (inclusive os personalizados, como "Em análise")
 * pertence a uma destas três categorias.
 */
public enum StatusCategory {
    TO_DO,
    IN_PROGRESS,
    DONE;

    /** Converte a chave da API ({@code new}, {@code indeterminate}, {@code done}). */
    public static StatusCategory fromJiraKey(String key) {
        return switch (key) {
            case "indeterminate" -> IN_PROGRESS;
            case "done" -> DONE;
            default -> TO_DO;
        };
    }
}

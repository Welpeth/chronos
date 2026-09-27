package com.chronos.tracker.jira;

/**
 * Issue do Jira atribuída ao usuário.
 *
 * @param key        chave da issue, por exemplo {@code PROJ-123}
 * @param summary    título da issue
 * @param statusName nome do status como aparece no quadro, por exemplo "Em andamento"
 * @param category   categoria do status
 * @param issueType  tipo da issue, por exemplo "Bug Cliente" (vazio se o Jira não informou)
 */
public record JiraIssue(String key, String summary, String statusName, StatusCategory category, String issueType) {

    public JiraIssue(String key, String summary, String statusName, StatusCategory category) {
        this(key, summary, statusName, category, "");
    }

    public JiraIssue(String key, String summary) {
        this(key, summary, "", StatusCategory.IN_PROGRESS);
    }

    public boolean isInProgress() {
        return category == StatusCategory.IN_PROGRESS;
    }
}

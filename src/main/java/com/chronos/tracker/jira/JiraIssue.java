package com.chronos.tracker.jira;

/**
 * Issue do Jira que aparece para o usuário: as atribuídas a ele e, com a coluna inteira ligada, as de outros
 * responsáveis que estão nas colunas monitoradas.
 *
 * @param key        chave da issue, por exemplo {@code PROJ-123}
 * @param summary    título da issue
 * @param statusName nome do status como aparece no quadro, por exemplo "Em andamento"
 * @param category   categoria do status
 * @param issueType  tipo da issue, por exemplo "Bug Cliente" (vazio se o Jira não informou)
 * @param assignee   nome do responsável (vazio se não tem ou o Jira não informou)
 * @param mine       se veio da busca das tasks do usuário; as de outros responsáveis não começam a contar sozinhas
 */
public record JiraIssue(String key, String summary, String statusName, StatusCategory category, String issueType,
                        String assignee, boolean mine) {

    public JiraIssue(String key, String summary, String statusName, StatusCategory category, String issueType) {
        this(key, summary, statusName, category, issueType, "", true);
    }

    public JiraIssue(String key, String summary, String statusName, StatusCategory category) {
        this(key, summary, statusName, category, "");
    }

    public JiraIssue(String key, String summary) {
        this(key, summary, "", StatusCategory.IN_PROGRESS);
    }

    public boolean isInProgress() {
        return category == StatusCategory.IN_PROGRESS;
    }

    /** A mesma issue marcada como de outro responsável. */
    public JiraIssue asOthers() {
        return new JiraIssue(key, summary, statusName, category, issueType, assignee, false);
    }
}

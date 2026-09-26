package com.chronos.tracker.jira;

import com.chronos.tracker.config.AppConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Considera como "task atual" a issue mais recentemente atualizada que está atribuída ao usuário
 * e em andamento nos projetos configurados.
 *
 * <p>Se o {@code .env} define {@code JIRA_JQL}, essa consulta é usada no lugar da padrão e a
 * primeira issue do resultado vira a task atual.
 */
public final class RestJiraService implements JiraService {

    private final JiraClient client;
    private final String jql;

    public RestJiraService(JiraClient client, String jql) {
        this.client = client;
        this.jql = jql;
    }

    public static JiraService from(AppConfig config) {
        if (!config.isJiraConfigured()) {
            return new UnconfiguredJiraService();
        }
        JiraClient client = new JiraClient(
                config.jiraBaseUrl().orElseThrow(),
                config.jiraEmail().orElseThrow(),
                config.jiraApiToken().orElseThrow());
        String jql = config.jiraJql().orElseGet(() -> defaultJql(config.jiraProjectKeys()));
        return new RestJiraService(client, withOrdering(jql));
    }

    static String defaultJql(List<String> projectKeys) {
        String projects = projectKeys.stream()
                .map(key -> "\"" + key.replace("\"", "") + "\"")
                .collect(Collectors.joining(", "));
        return "project in (" + projects + ") AND assignee = currentUser() AND statusCategory = \"In Progress\"";
    }

    /** Garante uma ordem estável: sem ORDER BY, a issue atualizada por último vem primeiro. */
    static String withOrdering(String jql) {
        if (jql.toLowerCase(Locale.ROOT).contains("order by")) {
            return jql;
        }
        return jql + " ORDER BY updated DESC";
    }

    String jql() {
        return jql;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Optional<JiraIssue> fetchCurrentIssue() throws JiraException {
        return client.search(jql, 1).stream().findFirst();
    }
}

package com.chronos.tracker.jira;

import com.chronos.tracker.config.AppConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Busca as issues do usuário nos projetos configurados: tudo que ainda não foi concluído, mais o que
 * foi concluído hoje (para o resumo do dia).
 *
 * <p>Se o {@code .env} define {@code JIRA_JQL}, essa consulta é usada no lugar da padrão.
 */
public final class RestJiraService implements JiraService {

    static final int MAX_ISSUES = 50;

    private final JiraClient client;
    private final String jql;
    private final List<String> projectKeys;

    public RestJiraService(JiraClient client, String jql, List<String> projectKeys) {
        this.client = client;
        this.jql = jql;
        this.projectKeys = List.copyOf(projectKeys);
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
        return new RestJiraService(client, withOrdering(jql), config.jiraProjectKeys());
    }

    static String defaultJql(List<String> projectKeys) {
        String projects = projectKeys.stream()
                .map(key -> "\"" + key.replace("\"", "") + "\"")
                .collect(Collectors.joining(", "));
        return "project in (" + projects + ") AND assignee = currentUser()"
                + " AND (statusCategory != Done OR updated >= startOfDay())";
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
    public List<JiraIssue> fetchMyIssues() throws JiraException {
        return client.search(jql, MAX_ISSUES);
    }

    @Override
    public Optional<JiraUser> fetchCurrentUser() throws JiraException {
        return Optional.of(client.myself());
    }

    @Override
    public List<JiraIssue> fetchRecentIssuesOfTypes(List<String> issueTypes) throws JiraException {
        if (projectKeys.isEmpty() || issueTypes.isEmpty()) {
            return List.of();
        }
        return client.search(alertJql(projectKeys, issueTypes), MAX_ISSUES);
    }

    /** Tasks dos tipos avisados criadas nos últimos dias, de qualquer responsável. */
    static String alertJql(List<String> projectKeys, List<String> issueTypes) {
        return "project in (" + quoted(projectKeys) + ") AND issuetype in (" + quoted(issueTypes) + ")"
                + " AND created >= -3d ORDER BY created DESC";
    }

    private static String quoted(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\"", "") + "\"")
                .collect(Collectors.joining(", "));
    }

    @Override
    public String completeIssue(String issueKey) throws JiraException {
        return client.transitionToDone(issueKey);
    }

    @Override
    public String addWorklog(String issueKey, Duration spent, Instant started) throws JiraException {
        return client.addWorklog(issueKey, spent, started.atZone(ZoneId.systemDefault()));
    }

    @Override
    public Optional<String> fetchProjectLabel() throws JiraException {
        if (projectKeys.isEmpty()) {
            return Optional.empty();
        }
        if (projectKeys.size() > 1) {
            return Optional.of(String.join(", ", projectKeys));
        }
        String key = projectKeys.get(0);
        List<String> parts = new ArrayList<>(List.of(key));
        String name = client.projectName(key);
        if (!name.equals(key)) {
            parts.add(name);
        }
        return Optional.of(String.join(" · ", parts));
    }
}

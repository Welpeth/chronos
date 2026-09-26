package com.chronos.tracker.jira;

import java.util.Optional;

/**
 * Serviço usado quando o {@code .env} não tem os dados do Jira.
 */
public final class UnconfiguredJiraService implements JiraService {

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public Optional<JiraIssue> fetchCurrentIssue() {
        return Optional.empty();
    }
}

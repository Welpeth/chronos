package com.chronos.tracker.jira;

import java.util.Optional;

/**
 * Serviço usado até a Fase 3, quando o cliente REST do Jira é implementado.
 */
public final class UnconfiguredJiraService implements JiraService {

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public Optional<String> fetchCurrentIssueKey() {
        return Optional.empty();
    }
}

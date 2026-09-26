package com.chronos.tracker.jira;

/**
 * Issue do Jira em que o usuário está trabalhando.
 *
 * @param key     chave da issue, por exemplo {@code PROJ-123}
 * @param summary título da issue
 */
public record JiraIssue(String key, String summary) {
}

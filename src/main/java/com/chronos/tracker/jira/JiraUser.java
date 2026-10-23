package com.chronos.tracker.jira;

/** Dono do API token, como o Jira o descreve. {@code accountId} fica vazio se o Jira não informou. */
public record JiraUser(String displayName, String email, String accountId) {

    public JiraUser(String displayName, String email) {
        this(displayName, email, "");
    }
}

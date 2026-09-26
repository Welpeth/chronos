package com.chronos.tracker.jira;

/** Dono do API token, como o Jira o descreve. */
public record JiraUser(String displayName, String email) {
}

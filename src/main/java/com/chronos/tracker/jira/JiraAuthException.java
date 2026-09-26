package com.chronos.tracker.jira;

/** O Jira recusou as credenciais (HTTP 401 ou 403). */
public class JiraAuthException extends JiraException {

    public JiraAuthException(String message) {
        super(message);
    }
}

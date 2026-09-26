package com.chronos.tracker.jira;

/** O Jira recusou a consulta (HTTP 400), por exemplo por uma chave de projeto inexistente. */
public class JiraQueryException extends JiraException {

    public JiraQueryException(String message) {
        super(message);
    }
}

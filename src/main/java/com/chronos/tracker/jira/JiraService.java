package com.chronos.tracker.jira;

import java.util.Optional;

/**
 * Descobre no Jira em qual issue o usuário está trabalhando agora.
 */
public interface JiraService {

    /** Indica se o serviço tem configuração para consultar o Jira. */
    boolean isConfigured();

    /**
     * Consulta o Jira. Pode bloquear e deve ser chamado fora da thread da UI.
     *
     * @return a issue atual, ou vazio se não houver nenhuma em andamento
     * @throws JiraException se o Jira estiver inacessível ou responder com erro
     */
    Optional<String> fetchCurrentIssueKey() throws JiraException;
}

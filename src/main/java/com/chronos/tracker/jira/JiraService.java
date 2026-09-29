package com.chronos.tracker.jira;

import com.chronos.tracker.config.I18n;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Busca no Jira as issues do usuário. Todos os métodos podem bloquear e devem ser chamados fora
 * da thread da UI.
 */
public interface JiraService {

    /** Indica se o serviço tem configuração para consultar o Jira. */
    boolean isConfigured();

    /**
     * Issues atribuídas ao usuário que ainda não foram concluídas (ou foram concluídas hoje),
     * da atualizada mais recentemente para a mais antiga.
     *
     * @throws JiraException se o Jira estiver inacessível ou responder com erro
     */
    List<JiraIssue> fetchMyIssues() throws JiraException;

    /** Dono do API token. */
    default Optional<JiraUser> fetchCurrentUser() throws JiraException {
        return Optional.empty();
    }

    /**
     * Issues criadas recentemente nos projetos configurados (de qualquer pessoa) com um dos tipos informados,
     * da mais nova para a mais antiga. Usado pelos avisos de task.
     */
    default List<JiraIssue> fetchRecentIssuesOfTypes(List<String> issueTypes) throws JiraException {
        return List.of();
    }

    /** Move a issue para "Concluído" no Jira e devolve o nome do status em que ela ficou. */
    default String completeIssue(String issueKey) throws JiraException {
        throw new JiraException(I18n.t("O Jira não está configurado"));
    }

    /**
     * Registra {@code spent} no controle de tempo da issue, começando em {@code started}, e devolve o id do
     * registro no Jira.
     */
    default String addWorklog(String issueKey, Duration spent, Instant started) throws JiraException {
        throw new JiraException(I18n.t("O Jira não está configurado"));
    }

    /**
     * Se a issue tem o campo "Controle de tempo", ou vazio se não deu para saber (por exemplo, sem permissão
     * de edição).
     */
    default Optional<Boolean> hasTimeTracking(String issueKey) throws JiraException {
        return Optional.empty();
    }

    /** Põe e tira labels da issue (as tags da validação). */
    default void updateLabels(String issueKey, List<String> add, List<String> remove) throws JiraException {
        throw new JiraException(I18n.t("O Jira não está configurado"));
    }

    /** Adiciona um comentário (Markdown) na issue e devolve o id dele. */
    default String addComment(String issueKey, String markdown) throws JiraException {
        throw new JiraException(I18n.t("O Jira não está configurado"));
    }

    /** Troca o texto de um comentário já feito. */
    default void updateComment(String issueKey, String commentId, String markdown) throws JiraException {
        throw new JiraException(I18n.t("O Jira não está configurado"));
    }

    /** Se o .env separa as tasks por quadro ({@code JIRA_BOARDS}). */
    default boolean usesBoards() {
        return false;
    }

    /**
     * Quadro de cada issue, pelo nome do quadro no Jira. Issues fora de todos os quadros configurados não entram;
     * uma issue em mais de um quadro fica com o primeiro da lista.
     */
    default Map<String, String> fetchBoards(List<String> issueKeys) throws JiraException {
        return Map.of();
    }

    /** Chave e nome dos projetos configurados, por exemplo "SCRUM · Minha equipe de software". */
    default Optional<String> fetchProjectLabel() throws JiraException {
        return Optional.empty();
    }
}

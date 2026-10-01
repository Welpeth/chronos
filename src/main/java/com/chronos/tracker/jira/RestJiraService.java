package com.chronos.tracker.jira;

import com.chronos.tracker.config.AppConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Busca as issues do usuário nos projetos configurados: tudo que ainda não foi concluído, mais o que
 * foi concluído hoje (para o resumo do dia).
 *
 * <p>Se o {@code .env} define {@code JIRA_JQL}, essa consulta é usada no lugar da padrão. Com
 * {@code JIRA_WATCH_WHOLE_COLUMNS}, vêm também as tasks de outros responsáveis (ou sem responsável) que estão nas
 * colunas que contam tempo, como a coluna de teste para quem testa.
 */
public final class RestJiraService implements JiraService {

    static final int MAX_ISSUES = 50;
    /** Sem JIRA_BOARDS, lê as colunas de no máximo tantos quadros dos projetos. */
    static final int MAX_COLUMN_BOARDS = 10;

    private final JiraClient client;
    private final String jql;
    private final List<String> projectKeys;
    private final Optional<String> columnJql;
    private final List<String> boardIds;
    /** JQL extra de cada quadro (JIRA_BOARD_FILTERS), somado ao filtro do próprio quadro. */
    private final Map<String, String> boardFilters;
    /** Nome de cada quadro, lido do Jira uma vez. */
    private final Map<String, String> boardNames = new ConcurrentHashMap<>();

    public RestJiraService(JiraClient client, String jql, List<String> projectKeys) {
        this(client, jql, projectKeys, Optional.empty(), List.of());
    }

    RestJiraService(JiraClient client, String jql, List<String> projectKeys, Optional<String> columnJql,
                    List<String> boardIds) {
        this(client, jql, projectKeys, columnJql, boardIds, Map.of());
    }

    RestJiraService(JiraClient client, String jql, List<String> projectKeys, Optional<String> columnJql,
                    List<String> boardIds, Map<String, String> boardFilters) {
        this.client = client;
        this.jql = jql;
        this.projectKeys = List.copyOf(projectKeys);
        this.columnJql = columnJql;
        this.boardIds = List.copyOf(boardIds);
        this.boardFilters = Map.copyOf(boardFilters);
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
        Optional<String> columnJql = config.watchWholeColumns() && !config.jiraProjectKeys().isEmpty()
                ? Optional.of(columnJql(config.jiraProjectKeys(), config.workingStatuses()))
                : Optional.empty();
        return new RestJiraService(client, withOrdering(jql), config.jiraProjectKeys(), columnJql,
                config.jiraBoards(), config.boardFilters());
    }

    /** Tasks nas colunas que contam tempo que não são do usuário: de outra pessoa ou sem responsável. */
    static String columnJql(List<String> projectKeys, List<String> statuses) {
        return "project in (" + quoted(projectKeys) + ") AND status in (" + quoted(statuses) + ")"
                + " AND (assignee is EMPTY OR assignee != currentUser()) ORDER BY updated DESC";
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

    Optional<String> columnJql() {
        return columnJql;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<JiraIssue> fetchMyIssues() throws JiraException {
        List<JiraIssue> mine = client.search(jql, MAX_ISSUES);
        if (columnJql.isEmpty()) {
            return mine;
        }
        return withOthers(mine, client.search(columnJql.get(), MAX_ISSUES));
    }

    /** As do usuário primeiro; depois as das colunas que ainda não apareceram, marcadas como de outros. */
    static List<JiraIssue> withOthers(List<JiraIssue> mine, List<JiraIssue> column) {
        Set<String> keys = new HashSet<>();
        List<JiraIssue> all = new ArrayList<>(mine);
        mine.forEach(issue -> keys.add(issue.key()));
        column.stream().filter(issue -> keys.add(issue.key())).map(JiraIssue::asOthers).forEach(all::add);
        return List.copyOf(all);
    }

    @Override
    public String addComment(String issueKey, String markdown) throws JiraException {
        return client.addComment(issueKey, markdown);
    }

    @Override
    public void updateComment(String issueKey, String commentId, String markdown) throws JiraException {
        client.updateComment(issueKey, commentId, markdown);
    }

    @Override
    public boolean usesBoards() {
        return !boardIds.isEmpty();
    }

    /**
     * Quadros de cada issue, na ordem de {@code JIRA_BOARDS}. Conta como do quadro a issue que atende ao filtro
     * dele (e ao sub-filtro do Kanban) e está num status que aparece em alguma coluna, como no próprio quadro.
     */
    @Override
    public Map<String, List<String>> fetchBoards(List<String> issueKeys) throws JiraException {
        Map<String, List<String>> boards = new LinkedHashMap<>();
        if (boardIds.isEmpty() || issueKeys.isEmpty()) {
            return boards;
        }
        Map<String, String> labels = boardLabels(boardIds);
        for (String boardId : boardIds) {
            String name = labels.get(boardId);
            JiraClient.BoardConfig config = client.boardConfig(boardId);
            Set<String> shown = config.shownStatusIds();
            for (int from = 0; from < issueKeys.size(); from += MAX_ISSUES) {
                List<String> chunk = issueKeys.subList(from, Math.min(issueKeys.size(), from + MAX_ISSUES));
                String jql = boardJql(keyJql(chunk), config.subQuery(), boardFilters.getOrDefault(boardId, ""));
                for (JiraClient.BoardIssue issue : client.boardIssues(boardId, jql, MAX_ISSUES)) {
                    if (shown.isEmpty() || shown.contains(issue.statusId())) {
                        List<String> of = boards.computeIfAbsent(issue.key(), key -> new ArrayList<>());
                        if (!of.contains(name)) {
                            of.add(name);
                        }
                    }
                }
            }
        }
        return boards;
    }

    /**
     * Colunas dos quadros de {@code JIRA_BOARDS} ou, sem eles, dos quadros dos projetos configurados (até
     * {@link #MAX_COLUMN_BOARDS}), pelo nome de cada quadro.
     */
    @Override
    public Map<String, List<KanbanColumn>> fetchBoardColumns() throws JiraException {
        List<String> boards = new ArrayList<>(boardIds);
        if (boards.isEmpty()) {
            for (String projectKey : projectKeys) {
                client.projectBoardIds(projectKey).stream().filter(id -> !boards.contains(id)).forEach(boards::add);
            }
        }
        Map<String, List<KanbanColumn>> byBoard = new LinkedHashMap<>();
        if (boards.isEmpty()) {
            return byBoard;
        }
        Map<String, String> statusNames = client.statusNames();
        List<String> read = boards.subList(0, Math.min(boards.size(), MAX_COLUMN_BOARDS));
        Map<String, String> labels = boardLabels(read);
        for (String boardId : read) {
            List<KanbanColumn> columns = client.boardConfig(boardId).columns().stream()
                    .map(column -> new KanbanColumn(column.name(), column.statusIds().stream()
                            .map(statusNames::get).filter(java.util.Objects::nonNull).toList()))
                    .toList();
            byBoard.put(labels.get(boardId), columns);
        }
        return byBoard;
    }

    /**
     * Nome de cada quadro para mostrar e filtrar. Dois quadros com o mesmo nome no Jira (o padrão "Quadro RP",
     * por exemplo) ganham o número no fim, "Quadro RP (215)", para não virarem um só.
     */
    Map<String, String> boardLabels(List<String> ids) throws JiraException {
        Map<String, String> names = new LinkedHashMap<>();
        for (String id : ids) {
            names.put(id, boardName(id));
        }
        Map<String, Long> repeated = names.values().stream().collect(java.util.stream.Collectors.groupingBy(
                name -> name.toLowerCase(java.util.Locale.ROOT), java.util.stream.Collectors.counting()));
        Map<String, String> labels = new LinkedHashMap<>();
        names.forEach((id, name) -> labels.put(id,
                repeated.get(name.toLowerCase(java.util.Locale.ROOT)) > 1 ? name + " (" + id + ")" : name));
        return labels;
    }

    private String boardName(String boardId) throws JiraException {
        String cached = boardNames.get(boardId);
        if (cached != null) {
            return cached;
        }
        String name = client.boardName(boardId);
        boardNames.put(boardId, name);
        return name;
    }

    /** As chaves, o sub-filtro do Kanban e o filtro extra do quadro, cada um entre parênteses. */
    static String boardJql(String keys, String subQuery, String extra) {
        StringBuilder jql = new StringBuilder(subQuery.isEmpty() && extra.isEmpty() ? keys : "(" + keys + ")");
        for (String part : List.of(subQuery, extra)) {
            if (!part.isEmpty()) {
                jql.append(" AND (").append(part).append(")");
            }
        }
        return jql.toString();
    }

    static String keyJql(List<String> issueKeys) {
        return "key in (" + quoted(issueKeys) + ")";
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
    public Optional<Boolean> hasTimeTracking(String issueKey) throws JiraException {
        try {
            return Optional.of(client.hasTimeTrackingField(issueKey));
        } catch (JiraAuthException e) {
            // Sem permissão para editar a issue o Jira não mostra os campos: não dá para saber.
            return Optional.empty();
        }
    }

    @Override
    public String addWorklog(String issueKey, Duration spent, Instant started) throws JiraException {
        return client.addWorklog(issueKey, spent, started.atZone(ZoneId.systemDefault()));
    }

    @Override
    public void updateLabels(String issueKey, List<String> add, List<String> remove) throws JiraException {
        client.updateLabels(issueKey, add, remove);
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

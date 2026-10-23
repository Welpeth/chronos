package com.chronos.tracker.jira;

import com.chronos.tracker.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestJiraServiceTest {

    private static final Map<String, String> CREDENTIALS = Map.of(
            "JIRA_BASE_URL", "https://empresa.atlassian.net",
            "JIRA_EMAIL", "eu@empresa.com",
            "JIRA_API_TOKEN", "token");

    @Test
    void defaultJqlLooksForMyOpenIssuesAndTodaysDone() {
        assertEquals(
                "project in (\"PROJ\", \"ABC\") AND assignee = currentUser()"
                        + " AND (statusCategory != Done OR updated >= startOfDay())",
                RestJiraService.defaultJql(List.of("PROJ", "ABC")));
    }

    @Test
    void addsOrderingOnlyWhenMissing() {
        assertEquals("project = X ORDER BY updated DESC", RestJiraService.withOrdering("project = X"));
        assertEquals("project = X order by rank", RestJiraService.withOrdering("project = X order by rank"));
    }

    @Test
    void customJqlWinsOverProjectKeys() {
        Map<String, String> values = new java.util.HashMap<>(CREDENTIALS);
        values.put("JIRA_PROJECT_KEY", "PROJ");
        values.put("JIRA_JQL", "assignee = currentUser()");

        RestJiraService service = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));

        assertEquals("assignee = currentUser() ORDER BY updated DESC", service.jql());
    }

    @Test
    void wholeColumnsAlsoSearchOtherPeoplesTasksInTheCountedColumns() {
        Map<String, String> values = new java.util.HashMap<>(CREDENTIALS);
        values.put("JIRA_PROJECT_KEY", "PROJ");
        values.put("JIRA_IN_PROGRESS_STATUSES", "Test");
        values.put("JIRA_USE_DEFAULT_STATUSES", "false");

        RestJiraService off = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));
        assertTrue(off.columnJql().isEmpty());

        values.put("JIRA_WATCH_WHOLE_COLUMNS", "true");
        RestJiraService on = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));
        assertEquals(Optional.of("project in (\"PROJ\") AND status in (\"Test\")"
                + " AND (assignee is EMPTY OR assignee != currentUser()) ORDER BY updated DESC"), on.columnJql());
    }

    @Test
    void otherPeoplesTasksComeAfterMineWithoutRepeating() {
        JiraIssue mine = new JiraIssue("PROJ-1", "Minha", "Test", StatusCategory.IN_PROGRESS);
        JiraIssue repeated = new JiraIssue("PROJ-1", "Minha", "Test", StatusCategory.IN_PROGRESS);
        JiraIssue others = new JiraIssue("PROJ-2", "Da Ana", "Test", StatusCategory.IN_PROGRESS, "", "Ana", true);

        List<JiraIssue> all = RestJiraService.withOthers(List.of(mine), List.of(repeated, others));

        assertEquals(List.of("PROJ-1", "PROJ-2"), all.stream().map(JiraIssue::key).toList());
        assertTrue(all.get(0).mine());
        assertFalse(all.get(1).mine());
        assertEquals("Ana", all.get(1).assignee());
    }

    @Test
    void missingSettingsGiveAnUnconfiguredService() {
        JiraService service = RestJiraService.from(AppConfig.fromMap(CREDENTIALS));

        assertInstanceOf(UnconfiguredJiraService.class, service);
        assertFalse(service.isConfigured());
    }

    @Test
    void alertJqlLooksForRecentIssuesOfTheChosenTypesFromAnyone() {
        assertEquals(
                "project in (\"SCRUM\") AND issuetype in (\"Bug Cliente\", \"Incidente\")"
                        + " AND created >= -3d ORDER BY created DESC",
                RestJiraService.alertJql(List.of("SCRUM"), List.of("Bug Cliente", "Incidente")));
    }

    @Test
    void doneStatusComesFromTheConfigWithDoneAsDefault() {
        Map<String, String> values = new java.util.HashMap<>(CREDENTIALS);
        values.put("JIRA_PROJECT_KEY", "PROJ");
        assertEquals("Done", ((RestJiraService) RestJiraService.from(AppConfig.fromMap(values))).doneStatus());

        values.put("CHRONOS_DONE_STATUS", " Concluído ");
        assertEquals("Concluído", ((RestJiraService) RestJiraService.from(AppConfig.fromMap(values))).doneStatus());
    }

    @Test
    void doneByMeJqlLooksForTodaysMovesByTheUserIntoTheStatus() {
        assertEquals("project in (\"PROJ\") AND status = \"Done\" AND status CHANGED TO \"Done\""
                        + " BY currentUser() AFTER startOfDay() ORDER BY updated DESC",
                RestJiraService.doneByMeJql(List.of("PROJ"), "Done"));
        assertEquals("status = \"Done\" AND status CHANGED TO \"Done\""
                        + " BY currentUser() AFTER startOfDay() ORDER BY updated DESC",
                RestJiraService.doneByMeJql(List.of(), "Done"));
    }

    @Test
    void completedOnlyWhenTheLastChangeIsTodaysMoveByMeIntoTheStatus() {
        java.time.ZoneId zone = java.time.ZoneId.of("America/Sao_Paulo");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 22);
        java.time.Instant afternoon = java.time.Instant.parse("2026-10-22T18:30:00Z");

        assertTrue(RestJiraService.completedBy(
                new JiraClient.HistoryEntry("eu", afternoon, "done"), "eu", "Done", today, zone));
        // Outra pessoa moveu, ou a última alteração não foi a mudança para o status.
        assertFalse(RestJiraService.completedBy(
                new JiraClient.HistoryEntry("ana", afternoon, "Done"), "eu", "Done", today, zone));
        assertFalse(RestJiraService.completedBy(
                new JiraClient.HistoryEntry("eu", afternoon, ""), "eu", "Done", today, zone));
        assertFalse(RestJiraService.completedBy(
                new JiraClient.HistoryEntry("eu", afternoon, "Test"), "eu", "Done", today, zone));
        // Ontem no horário local, mesmo sendo hoje em UTC.
        assertFalse(RestJiraService.completedBy(new JiraClient.HistoryEntry("eu",
                java.time.Instant.parse("2026-10-22T02:00:00Z"), "Done"), "eu", "Done", today, zone));
        // Sem saber quem é o usuário, não conta.
        assertFalse(RestJiraService.completedBy(
                new JiraClient.HistoryEntry("", afternoon, "Done"), "", "Done", today, zone));
    }

    @Test
    void fetchAddsTasksTheUserMovedToDoneTodayWithoutBeingAssigned() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String now = java.time.OffsetDateTime.now().format(JiraClient.WORKLOG_STARTED);
        java.util.concurrent.atomic.AtomicInteger changelogReads = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/rest/api/3/search/jql", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String issues = body.contains("CHANGED TO") ? """
                    {"key":"PROJ-1","fields":{"summary":"Minha","status":{"name":"Done","statusCategory":{"key":"done"}}}},
                    {"key":"PROJ-2","fields":{"summary":"Testei","assignee":{"displayName":"Dev"},
                      "status":{"name":"Done","statusCategory":{"key":"done"}}}},
                    {"key":"PROJ-3","fields":{"summary":"Mexeram depois","assignee":{"displayName":"Dev"},
                      "status":{"name":"Done","statusCategory":{"key":"done"}}}}
                    """ : """
                    {"key":"PROJ-1","fields":{"summary":"Minha","status":{"name":"Done","statusCategory":{"key":"done"}}}}
                    """;
            respond(exchange, "{\"issues\":[" + issues + "]}");
        });
        server.createContext("/rest/api/3/myself", exchange -> respond(exchange, "{\"accountId\":\"eu\"}"));
        server.createContext("/rest/api/3/issue/", exchange -> {
            changelogReads.incrementAndGet();
            String author = exchange.getRequestURI().getPath().contains("PROJ-2") ? "eu" : "dev";
            String field = author.equals("eu") ? "status" : "labels";
            respond(exchange, "{\"isLast\":true,\"values\":[{\"author\":{\"accountId\":\"" + author
                    + "\"},\"created\":\"" + now + "\",\"items\":[{\"fieldId\":\"" + field
                    + "\",\"toString\":\"Done\"}]}]}");
        });
        server.start();
        try {
            JiraClient client = new JiraClient("http://127.0.0.1:" + server.getAddress().getPort(), "e", "t");
            RestJiraService service = new RestJiraService(client, "assignee = currentUser()", List.of("PROJ"),
                    Optional.empty(), List.of(), Map.of(), "Done");

            assertEquals(List.of("PROJ-1", "PROJ-2"), service.fetchMyIssues().stream().map(JiraIssue::key).toList());
            assertTrue(service.fetchMyIssues().stream().allMatch(JiraIssue::mine));
            // A segunda busca não relê o histórico das tasks que não mudaram.
            assertEquals(2, changelogReads.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

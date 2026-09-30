package com.chronos.tracker.jira;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quadro de cada task pela API de quadros do Jira, contra um servidor local. */
class JiraBoardsTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/agile/1.0/board/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/configuration")) {
                body = path.contains("/215/")
                        ? "{\"columnConfig\":{\"columns\":[{\"name\":\"Test\",\"statuses\":[{\"id\":\"10\"},{\"id\":\"11\"}]}]}}"
                        : "{\"columnConfig\":{\"columns\":[{\"name\":\"Test\",\"statuses\":[{\"id\":\"12\"}]},{\"name\":\"Feito\",\"statuses\":[]}]},"
                        + "\"subQuery\":{\"query\":\"fixVersion is EMPTY\"}}";
            } else if (path.endsWith("/issue")) {
                queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
                // RP-1 está nos dois quadros; RP-2 só no 514; RP-3 em nenhum. RP-4 atende ao filtro do 215, mas
                // está num status que nenhuma coluna dele mostra, então não aparece no quadro.
                body = path.contains("/215/")
                        ? "{\"issues\":[" + issue("RP-1", "10") + "," + issue("RP-4", "99") + "]}"
                        : "{\"issues\":[" + issue("RP-1", "12") + "," + issue("RP-2", "12") + "]}";
            } else {
                body = path.endsWith("/215") ? "{\"id\":215,\"name\":\"Quadro RP\"}" : "{\"id\":514,\"name\":\"Suporte RP\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/rest/agile/1.0/board", exchange -> {
            queries.add("boards " + exchange.getRequestURI().getRawQuery());
            reply(exchange, "{\"values\":[{\"id\":215},{\"id\":514}]}");
        });
        server.createContext("/rest/api/3/status", exchange -> reply(exchange,
                "[{\"id\":\"10\",\"name\":\"Em teste\"},{\"id\":\"11\",\"name\":\"Reteste\"},{\"id\":\"12\",\"name\":\"QA\"}]"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String issue(String key, String statusId) {
        return "{\"key\":\"" + key + "\",\"fields\":{\"status\":{\"id\":\"" + statusId + "\"}}}";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void eachIssueGetsEveryBoardThatShowsIt() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP",
                List.of("RP"), Optional.empty(), List.of("215", "514"));

        Map<String, List<String>> boards = service.fetchBoards(List.of("RP-1", "RP-2", "RP-3", "RP-4"));

        assertTrue(service.usesBoards());
        assertEquals(Map.of("RP-1", List.of("Quadro RP", "Suporte RP"), "RP-2", List.of("Suporte RP")), boards);
        List<String> issueQueries = queries.stream().filter(q -> q.contains("jql=")).toList();
        assertEquals(2, issueQueries.size());
        assertTrue(issueQueries.get(0).contains("jql=key in (\"RP-1\", \"RP-2\", \"RP-3\", \"RP-4\")"), issueQueries.get(0));
        // O sub-filtro do Kanban vale junto, como no próprio quadro.
        assertTrue(issueQueries.get(1).endsWith(") AND (fixVersion is EMPTY)"), issueQueries.get(1));
    }

    @Test
    void withoutBoardsNothingIsAsked() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP", List.of("RP"));

        assertFalse(service.usesBoards());
        assertEquals(Map.of(), service.fetchBoards(List.of("RP-1")));
        assertTrue(queries.isEmpty());
    }

    @Test
    void columnsOfTheConfiguredBoardsMapToStatusNames() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP",
                List.of("RP"), Optional.empty(), List.of("215", "514"));

        Map<String, List<JiraService.KanbanColumn>> boards = service.fetchBoardColumns();
        Map<String, java.util.Set<String>> columns = JiraService.columnStatuses(boards);

        assertEquals(List.of("Quadro RP", "Suporte RP"), List.copyOf(boards.keySet()));
        assertEquals(List.of(new JiraService.KanbanColumn("Test", List.of("QA")), new JiraService.KanbanColumn("Feito", List.of())),
                boards.get("Suporte RP"));
        assertEquals(Map.of("Test", java.util.Set.of("Em teste", "Reteste", "QA"), "Feito", java.util.Set.of()), columns);
        assertTrue(queries.stream().noneMatch(q -> q.startsWith("boards")), queries.toString());
    }

    @Test
    void withoutConfiguredBoardsTheProjectBoardsAreUsed() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP", List.of("RP"));

        Map<String, java.util.Set<String>> columns = JiraService.columnStatuses(service.fetchBoardColumns());

        assertEquals(java.util.Set.of("Em teste", "Reteste", "QA"), columns.get("Test"));
        assertEquals(List.of("boards maxResults=50&projectKeyOrId=RP"), queries);
    }
}

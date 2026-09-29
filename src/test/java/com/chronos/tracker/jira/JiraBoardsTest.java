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
            if (path.endsWith("/issue")) {
                queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
                // RP-1 está nos dois quadros; RP-2 só no 514; RP-3 em nenhum.
                body = path.contains("/215/")
                        ? "{\"issues\":[{\"key\":\"RP-1\"}]}"
                        : "{\"issues\":[{\"key\":\"RP-1\"},{\"key\":\"RP-2\"}]}";
            } else {
                body = path.endsWith("/215") ? "{\"id\":215,\"name\":\"Quadro RP\"}" : "{\"id\":514,\"name\":\"Suporte RP\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void eachIssueGetsTheFirstBoardItIsIn() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP",
                List.of("RP"), Optional.empty(), List.of("215", "514"));

        Map<String, String> boards = service.fetchBoards(List.of("RP-1", "RP-2", "RP-3"));

        assertTrue(service.usesBoards());
        assertEquals(Map.of("RP-1", "Quadro RP", "RP-2", "Suporte RP"), boards);
        assertEquals(2, queries.size());
        assertTrue(queries.get(0).contains("jql=key in (\"RP-1\", \"RP-2\", \"RP-3\")"), queries.get(0));
    }

    @Test
    void withoutBoardsNothingIsAsked() throws JiraException {
        RestJiraService service = new RestJiraService(new JiraClient(baseUrl, "e", "t"), "project = RP", List.of("RP"));

        assertFalse(service.usesBoards());
        assertEquals(Map.of(), service.fetchBoards(List.of("RP-1")));
        assertTrue(queries.isEmpty());
    }
}

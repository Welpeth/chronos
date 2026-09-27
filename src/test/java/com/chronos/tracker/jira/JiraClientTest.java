package com.chronos.tracker.jira;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testa o cliente contra um servidor HTTP local que imita o Jira. */
class JiraClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> receivedAuth = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"issues\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void searchesWithBasicAuthAndParsesIssues() throws JiraException {
        responseBody = """
                {"issues":[
                  {"id":"1","key":"PROJ-123","fields":{"summary":"Corrigir login",
                    "status":{"name":"Em andamento","statusCategory":{"key":"indeterminate"}}}},
                  {"id":"2","key":"PROJ-456","fields":{"summary":"Nova tela",
                    "status":{"name":"Em análise","statusCategory":{"key":"indeterminate"}}}},
                  {"id":"3","key":"PROJ-789","fields":{"summary":"Deploy",
                    "status":{"name":"Concluído","statusCategory":{"key":"done"}}}}
                ]}""";

        List<JiraIssue> issues = new JiraClient(baseUrl, "eu@empresa.com", "token").search("project = PROJ", 2);

        assertEquals(List.of(
                new JiraIssue("PROJ-123", "Corrigir login", "Em andamento", StatusCategory.IN_PROGRESS),
                new JiraIssue("PROJ-456", "Nova tela", "Em análise", StatusCategory.IN_PROGRESS),
                new JiraIssue("PROJ-789", "Deploy", "Concluído", StatusCategory.DONE)), issues);
        // base64("eu@empresa.com:token")
        assertEquals("Basic ZXVAZW1wcmVzYS5jb206dG9rZW4=", receivedAuth.get());
        assertTrue(receivedBody.get().contains("\"jql\":\"project = PROJ\""));
        assertTrue(receivedBody.get().contains("\"maxResults\":2"));
    }

    @Test
    void emptyResultMeansNoIssue() throws JiraException {
        assertEquals(List.of(), new JiraClient(baseUrl, "e", "t").search("x", 1));
    }

    @Test
    void unauthorizedBecomesAuthException() {
        status = 401;
        responseBody = "{\"errorMessages\":[\"no\"]}";

        assertThrows(JiraAuthException.class, () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
    }

    @Test
    void rejectedQueryCarriesJiraMessage() {
        status = 400;
        responseBody = "{\"errorMessages\":[\"O valor 'PROJ' não existe para o campo 'project'.\"]}";

        JiraQueryException error = assertThrows(JiraQueryException.class,
                () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
        assertTrue(error.getMessage().contains("O valor 'PROJ' não existe"));
    }

    @Test
    void serverErrorBecomesJiraException() {
        status = 500;
        responseBody = "boom";

        JiraException error = assertThrows(JiraException.class, () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void unreachableServerBecomesJiraException() {
        server.stop(0);

        assertThrows(JiraException.class, () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
    }

    @Test
    void transitionToDonePicksTheDoneTransition() throws Exception {
        AtomicReference<String> posted = new AtomicReference<>();
        server.createContext("/rest/api/3/issue/PROJ-1/transitions", exchange -> {
            byte[] bytes;
            int code;
            if (exchange.getRequestMethod().equals("POST")) {
                posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                bytes = new byte[0];
                code = 204;
            } else {
                bytes = """
                        {"transitions":[
                          {"id":"21","name":"Iniciar","to":{"name":"Em andamento","statusCategory":{"key":"indeterminate"}}},
                          {"id":"31","name":"Concluir","to":{"name":"Concluído","statusCategory":{"key":"done"}}}
                        ]}""".getBytes(StandardCharsets.UTF_8);
                code = 200;
            }
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        String status = new JiraClient(baseUrl, "e", "t").transitionToDone("PROJ-1");

        assertEquals("Concluído", status);
        assertTrue(posted.get().contains("\"id\":\"31\""), posted.get());
    }

    @Test
    void addWorklogPostsTheTimeAndStart() throws Exception {
        AtomicReference<String> posted = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/rest/api/3/issue/PROJ-1/worklog", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"id\":\"10042\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        String id = new JiraClient(baseUrl, "e", "t").addWorklog("PROJ-1", java.time.Duration.ofMinutes(80),
                java.time.ZonedDateTime.parse("2026-09-27T10:00:00-03:00"));

        assertEquals("10042", id);
        assertEquals("adjustEstimate=auto", query.get());
        assertTrue(posted.get().contains("\"timeSpentSeconds\":4800"), posted.get());
        assertTrue(posted.get().contains("\"started\":\"2026-09-27T10:00:00.000-0300\""), posted.get());
        assertTrue(posted.get().contains("Apontado pelo Chronos"), posted.get());
    }
}

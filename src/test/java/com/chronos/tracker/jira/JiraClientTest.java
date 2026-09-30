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
                  {"id":"2","key":"PROJ-456","fields":{"summary":"Nova tela","assignee":{"displayName":"Ana"},
                    "status":{"name":"Em análise","statusCategory":{"key":"indeterminate"}}}},
                  {"id":"3","key":"PROJ-789","fields":{"summary":"Deploy","updated":"2026-09-29T14:02:11.123-0300",
                    "status":{"name":"Concluído","statusCategory":{"key":"done"}}}}
                ]}""";

        List<JiraIssue> issues = new JiraClient(baseUrl, "eu@empresa.com", "token").search("project = PROJ", 2);

        assertEquals(List.of(
                new JiraIssue("PROJ-123", "Corrigir login", "Em andamento", StatusCategory.IN_PROGRESS),
                new JiraIssue("PROJ-456", "Nova tela", "Em análise", StatusCategory.IN_PROGRESS, "", "Ana", true),
                new JiraIssue("PROJ-789", "Deploy", "Concluído", StatusCategory.DONE, "", "", true,
                        java.util.Optional.of(java.time.Instant.parse("2026-09-29T17:02:11.123Z")))), issues);
        // base64("eu@empresa.com:token")
        assertEquals("Basic ZXVAZW1wcmVzYS5jb206dG9rZW4=", receivedAuth.get());
        assertTrue(receivedBody.get().contains("\"jql\":\"project = PROJ\""));
        assertTrue(receivedBody.get().contains("\"maxResults\":2"));
        assertTrue(receivedBody.get().contains("\"assignee\""));
        assertTrue(receivedBody.get().contains("\"updated\""));
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

    @Test
    void updateLabelsAddsAndRemovesInOneEdit() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        server.createContext("/rest/api/3/issue/PROJ-1", exchange -> {
            method.set(exchange.getRequestMethod());
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        new JiraClient(baseUrl, "e", "t").updateLabels("PROJ-1", List.of("testado"), List.of("em-teste"));

        assertEquals("PUT", method.get());
        assertEquals("{\"update\":{\"labels\":[{\"add\":\"testado\"},{\"remove\":\"em-teste\"}]}}", sent.get());
    }

    private void serve(String path, int code, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void timeTrackingCountsWhenTheIssueHasTheFieldEvenOutsideTheEditScreen() throws Exception {
        serve("/rest/api/3/configuration/timetracking", 200, "{\"key\":\"JIRA\"}");
        // Projeto gerenciado pela equipe: o campo vem na issue, mas não na tela de edição.
        serve("/rest/api/3/issue/PROJ-1", 200, "{\"key\":\"PROJ-1\",\"fields\":{\"timetracking\":{}}}");
        serve("/rest/api/3/issue/PROJ-1/editmeta", 200, "{\"fields\":{\"summary\":{}}}");
        serve("/rest/api/3/issue/PROJ-2", 200, "{\"key\":\"PROJ-2\",\"fields\":{}}");
        serve("/rest/api/3/issue/PROJ-2/editmeta", 200, "{\"fields\":{\"summary\":{}}}");
        serve("/rest/api/3/issue/PROJ-3", 200, "{\"key\":\"PROJ-3\",\"fields\":{}}");
        serve("/rest/api/3/issue/PROJ-3/editmeta", 200, "{\"fields\":{\"timetracking\":{}}}");
        JiraClient client = new JiraClient(baseUrl, "e", "t");

        assertTrue(client.hasTimeTrackingField("PROJ-1"));
        assertEquals(false, client.hasTimeTrackingField("PROJ-2"));
        assertTrue(client.hasTimeTrackingField("PROJ-3"));
    }

    @Test
    void timeTrackingTurnedOffInJiraMeansNoField() throws Exception {
        serve("/rest/api/3/configuration/timetracking", 204, "");

        assertEquals(false, new JiraClient(baseUrl, "e", "t").hasTimeTrackingField("PROJ-1"));
    }
}

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
                  {"id":"1","key":"PROJ-123","fields":{"summary":"Corrigir login"}},
                  {"id":"2","key":"PROJ-456","fields":{"summary":"Nova tela"}}
                ]}""";

        List<JiraIssue> issues = new JiraClient(baseUrl, "eu@empresa.com", "token").search("project = PROJ", 2);

        assertEquals(List.of(new JiraIssue("PROJ-123", "Corrigir login"), new JiraIssue("PROJ-456", "Nova tela")), issues);
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
    void serverErrorBecomesJiraException() {
        status = 400;
        responseBody = "{\"errorMessages\":[\"JQL inválida\"]}";

        JiraException error = assertThrows(JiraException.class, () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
        assertTrue(error.getMessage().contains("400"));
    }

    @Test
    void unreachableServerBecomesJiraException() {
        server.stop(0);

        assertThrows(JiraException.class, () -> new JiraClient(baseUrl, "e", "t").search("x", 1));
    }
}

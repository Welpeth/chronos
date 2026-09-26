package com.chronos.tracker.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;

/**
 * Cliente mínimo da API REST v3 do Jira Cloud, autenticado com e-mail + API token.
 */
public final class JiraClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI searchUri;
    private final String authorization;

    public JiraClient(String baseUrl, String email, String apiToken) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(apiToken, "apiToken");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.searchUri = URI.create(baseUrl.replaceAll("/+$", "") + "/rest/api/3/search/jql");
        String credentials = email + ":" + apiToken;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Executa uma busca JQL e devolve até {@code maxResults} issues, na ordem do Jira.
     */
    public List<JiraIssue> search(String jql, int maxResults) throws JiraException {
        ObjectNode body = mapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        body.putArray("fields").add("summary");

        HttpRequest request = HttpRequest.newBuilder(searchUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JiraException("Não foi possível conectar ao Jira: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraException("Consulta ao Jira interrompida", e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new JiraAuthException("O Jira recusou as credenciais (HTTP " + status + ")");
        }
        if (status != 200) {
            throw new JiraException("O Jira respondeu HTTP " + status + ": " + abbreviate(response.body()));
        }
        return parseIssues(response.body());
    }

    private List<JiraIssue> parseIssues(String json) throws JiraException {
        try {
            JsonNode issues = mapper.readTree(json).path("issues");
            return StreamSupport.stream(issues.spliterator(), false)
                    .map(issue -> new JiraIssue(
                            issue.path("key").asText(),
                            issue.path("fields").path("summary").asText("")))
                    .filter(issue -> !issue.key().isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new JiraException("Resposta inválida do Jira", e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

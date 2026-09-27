package com.chronos.tracker.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
    private final String baseUrl;
    private final String authorization;

    public JiraClient(String baseUrl, String email, String apiToken) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(apiToken, "apiToken");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
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
        body.putArray("fields").add("summary").add("status").add("issuetype");

        HttpRequest request = request("/rest/api/3/search/jql")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return parseIssues(send(request));
    }

    /** Usuário dono do API token. */
    public JiraUser myself() throws JiraException {
        JsonNode user = readTree(send(request("/rest/api/3/myself").GET().build()));
        return new JiraUser(user.path("displayName").asText(""), user.path("emailAddress").asText(""));
    }

    /** Nome do projeto com a chave informada. */
    public String projectName(String projectKey) throws JiraException {
        String path = "/rest/api/3/project/" + URLEncoder.encode(projectKey, StandardCharsets.UTF_8);
        return readTree(send(request(path).GET().build())).path("name").asText(projectKey);
    }

    /**
     * Move a issue para o primeiro status da categoria "Concluído" que o fluxo dela permite, e devolve o nome
     * desse status.
     */
    public String transitionToDone(String issueKey) throws JiraException {
        String path = "/rest/api/3/issue/" + URLEncoder.encode(issueKey, StandardCharsets.UTF_8) + "/transitions";
        JsonNode transitions = readTree(send(request(path).GET().build())).path("transitions");
        JsonNode done = StreamSupport.stream(transitions.spliterator(), false)
                .filter(t -> "done".equals(t.path("to").path("statusCategory").path("key").asText()))
                .findFirst()
                .orElseThrow(() -> new JiraException(
                        "O fluxo de " + issueKey + " não tem como ir direto para um status concluído"));

        ObjectNode body = mapper.createObjectNode();
        body.putObject("transition").put("id", done.path("id").asText());
        send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        return done.path("to").path("name").asText(done.path("name").asText("Concluído"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) throws JiraException {
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
        if (status == 400) {
            throw new JiraQueryException("O Jira recusou a consulta: " + errorMessages(response.body()));
        }
        if (status < 200 || status >= 300) {
            throw new JiraException("O Jira respondeu HTTP " + status + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    private List<JiraIssue> parseIssues(String json) throws JiraException {
        JsonNode issues = readTree(json).path("issues");
        return StreamSupport.stream(issues.spliterator(), false)
                .map(issue -> {
                    JsonNode fields = issue.path("fields");
                    JsonNode status = fields.path("status");
                    return new JiraIssue(
                            issue.path("key").asText(),
                            fields.path("summary").asText(""),
                            status.path("name").asText(""),
                            StatusCategory.fromJiraKey(status.path("statusCategory").path("key").asText("")),
                            fields.path("issuetype").path("name").asText(""));
                })
                .filter(issue -> !issue.key().isEmpty())
                .toList();
    }

    /** Junta as mensagens de erro do Jira, que vêm como {@code {"errorMessages": [...]}}. */
    private String errorMessages(String body) {
        try {
            JsonNode messages = mapper.readTree(body).path("errorMessages");
            if (messages.isArray() && !messages.isEmpty()) {
                return String.join(" ", StreamSupport.stream(messages.spliterator(), false).map(JsonNode::asText).toList());
            }
        } catch (IOException ignored) {
            // Corpo não é JSON: cai no texto bruto abaixo.
        }
        return abbreviate(body);
    }

    private JsonNode readTree(String json) throws JiraException {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new JiraException("Resposta inválida do Jira", e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

package com.chronos.tracker.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Configuração do tracker para um projeto, lida do {@code .env} ao lado do executável.
 *
 * <p>Variáveis de ambiente do sistema preenchem o que o {@code .env} não define, então o mesmo
 * executável funciona em projetos diferentes só trocando o {@code .env}.
 */
public record AppConfig(
        Optional<String> jiraBaseUrl,
        Optional<String> jiraEmail,
        Optional<String> jiraApiToken,
        List<String> jiraProjectKeys,
        Optional<String> jiraJql,
        Duration pollingInterval,
        Duration possiblyIdleAfter,
        Duration inactiveAfter,
        Path databasePath,
        List<String> workingStatuses) {

    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_POSSIBLY_IDLE_AFTER = Duration.ofMinutes(2);
    public static final Duration DEFAULT_INACTIVE_AFTER = Duration.ofMinutes(5);
    public static final String DEFAULT_DATABASE = "chronos.db";
    /** Colunas do quadro em que o tempo conta, quando JIRA_IN_PROGRESS_STATUSES não é definido. */
    public static final List<String> DEFAULT_WORKING_STATUSES = List.of("Em andamento", "Em progresso", "In Progress");

    public static AppConfig load(Path envFile) throws IOException {
        Map<String, String> fileValues = EnvFile.read(envFile);
        return from(key -> Optional.ofNullable(fileValues.get(key)).or(() -> Optional.ofNullable(System.getenv(key))));
    }

    public static AppConfig fromMap(Map<String, String> values) {
        return from(key -> Optional.ofNullable(values.get(key)));
    }

    private static AppConfig from(Function<String, Optional<String>> lookup) {
        Function<String, Optional<String>> nonBlank = key -> lookup.apply(key).map(String::strip).filter(v -> !v.isEmpty());

        List<String> projectKeys = nonBlank.apply("JIRA_PROJECT_KEYS")
                .or(() -> nonBlank.apply("JIRA_PROJECT_KEY"))
                .map(AppConfig::splitList)
                .orElse(List.of());

        return new AppConfig(
                nonBlank.apply("JIRA_BASE_URL").map(url -> url.replaceAll("/+$", "")),
                nonBlank.apply("JIRA_EMAIL"),
                nonBlank.apply("JIRA_API_TOKEN"),
                projectKeys,
                nonBlank.apply("JIRA_JQL"),
                seconds(nonBlank, "POLLING_INTERVAL_SECONDS", DEFAULT_POLLING_INTERVAL),
                seconds(nonBlank, "POSSIBLY_IDLE_SECONDS", DEFAULT_POSSIBLY_IDLE_AFTER),
                seconds(nonBlank, "IDLE_THRESHOLD_SECONDS", DEFAULT_INACTIVE_AFTER),
                Path.of(nonBlank.apply("CHRONOS_DB_PATH").orElse(DEFAULT_DATABASE)),
                nonBlank.apply("JIRA_IN_PROGRESS_STATUSES").map(AppConfig::splitList).orElse(DEFAULT_WORKING_STATUSES));
    }

    /** Indica se há dados suficientes para falar com o Jira. */
    public boolean isJiraConfigured() {
        return jiraBaseUrl.isPresent() && jiraEmail.isPresent() && jiraApiToken.isPresent()
                && (jiraJql.isPresent() || !jiraProjectKeys.isEmpty());
    }

    private static List<String> splitList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Duration seconds(Function<String, Optional<String>> lookup, String key, Duration fallback) {
        return lookup.apply(key).map(value -> {
            try {
                long seconds = Long.parseLong(value);
                if (seconds <= 0) {
                    throw new IllegalArgumentException(key + " deve ser maior que zero: " + value);
                }
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " deve ser um número de segundos: " + value, e);
            }
        }).orElse(fallback);
    }

    @Override
    public String toString() {
        return "AppConfig[jiraBaseUrl=" + jiraBaseUrl.orElse("-")
                + ", jiraEmail=" + jiraEmail.orElse("-")
                + ", jiraApiToken=" + (jiraApiToken.isPresent() ? "***" : "-")
                + ", jiraProjectKeys=" + jiraProjectKeys
                + ", jiraJql=" + jiraJql.orElse("-")
                + ", pollingInterval=" + pollingInterval
                + ", possiblyIdleAfter=" + possiblyIdleAfter
                + ", inactiveAfter=" + inactiveAfter
                + ", databasePath=" + databasePath
                + ", workingStatuses=" + workingStatuses + "]";
    }
}

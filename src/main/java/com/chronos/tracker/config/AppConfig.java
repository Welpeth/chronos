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
        List<String> workingStatuses,
        List<String> alertIssueTypes,
        List<String> typedStatuses,
        boolean useDefaultStatuses,
        boolean autoStart,
        boolean watchWholeColumns,
        boolean onlyWorkingColumns,
        List<String> playLabels,
        List<String> doneLabels,
        boolean darkMode,
        I18n.Language language) {

    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_POSSIBLY_IDLE_AFTER = Duration.ofMinutes(2);
    public static final Duration DEFAULT_INACTIVE_AFTER = Duration.ofMinutes(5);
    public static final String DEFAULT_DATABASE = "chronos.db";
    /** Colunas padrão em que o tempo conta; somam com as de JIRA_IN_PROGRESS_STATUSES se JIRA_USE_DEFAULT_STATUSES. */
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

        List<String> typedStatuses = nonBlank.apply("JIRA_IN_PROGRESS_STATUSES").map(AppConfig::splitList)
                .orElse(List.of());
        boolean useDefaultStatuses = flag(nonBlank, "JIRA_USE_DEFAULT_STATUSES", true);

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
                workingStatuses(typedStatuses, useDefaultStatuses),
                nonBlank.apply("CHRONOS_ALERT_ISSUE_TYPES").map(AppConfig::splitList).orElse(List.of()),
                typedStatuses,
                useDefaultStatuses,
                flag(nonBlank, "CHRONOS_AUTO_START", true),
                flag(nonBlank, "JIRA_WATCH_WHOLE_COLUMNS", false),
                flag(nonBlank, "CHRONOS_ONLY_WORKING_COLUMNS", false),
                labels(nonBlank, "CHRONOS_PLAY_LABELS"),
                labels(nonBlank, "CHRONOS_DONE_LABELS"),
                flag(nonBlank, "CHRONOS_DARK_MODE", false),
                I18n.Language.fromCode(nonBlank.apply("CHRONOS_LANGUAGE").orElse("pt")));
    }

    /**
     * Colunas que contam tempo: as padrão (se ligadas) mais as digitadas, sem repetir. Sem nenhuma, valem as
     * padrão, para o tempo nunca ficar sem coluna.
     */
    static List<String> workingStatuses(List<String> typed, boolean useDefaults) {
        Map<String, String> byName = new java.util.LinkedHashMap<>();
        if (useDefaults) {
            DEFAULT_WORKING_STATUSES.forEach(name -> byName.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), name));
        }
        typed.forEach(name -> byName.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), name));
        return byName.isEmpty() ? DEFAULT_WORKING_STATUSES : List.copyOf(byName.values());
    }

    /** Indica se há dados suficientes para falar com o Jira. */
    /**
     * Identifica o Jira no histórico: o endereço em minúsculas, sem barra no fim. Vazio sem endereço.
     */
    public String jiraSite() {
        return jiraBaseUrl.map(url -> url.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("/+$", ""))
                .orElse("");
    }

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

    /** Labels do Jira não aceitam espaço: "em teste" vira "em-teste". */
    private static List<String> labels(Function<String, Optional<String>> lookup, String key) {
        return lookup.apply(key).map(AppConfig::splitList).orElse(List.of()).stream()
                .map(label -> label.replaceAll("\\s+", "-"))
                .distinct()
                .toList();
    }

    private static boolean flag(Function<String, Optional<String>> lookup, String key, boolean fallback) {
        return lookup.apply(key).map(value -> switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "sim", "1" -> true;
            case "false", "nao", "não", "0" -> false;
            default -> throw new IllegalArgumentException(I18n.t("{0} deve ser true ou false: {1}", key, value));
        }).orElse(fallback);
    }

    private static Duration seconds(Function<String, Optional<String>> lookup, String key, Duration fallback) {
        return lookup.apply(key).map(value -> {
            try {
                long seconds = Long.parseLong(value);
                if (seconds <= 0) {
                    throw new IllegalArgumentException(I18n.t("{0} deve ser maior que zero: {1}", key, value));
                }
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(I18n.t("{0} deve ser um número de segundos: {1}", key, value), e);
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
                + ", workingStatuses=" + workingStatuses
                + ", alertIssueTypes=" + alertIssueTypes
                + ", autoStart=" + autoStart
                + ", watchWholeColumns=" + watchWholeColumns
                + ", onlyWorkingColumns=" + onlyWorkingColumns
                + ", playLabels=" + playLabels
                + ", doneLabels=" + doneLabels
                + ", darkMode=" + darkMode
                + ", language=" + language.code + "]";
    }
}

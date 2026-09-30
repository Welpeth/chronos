package com.chronos.tracker.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void boardsAcceptNumbersOrPastedAddresses() {
        assertEquals(List.of("215", "514"), AppConfig.boardIds(
                "https://valesoft.atlassian.net/jira/software/c/projects/RP/boards/215, 514, 215, sem número"));
        assertEquals(List.of("215"), AppConfig.fromMap(Map.of("JIRA_BOARDS", " 215 ")).jiraBoards());
        assertEquals(List.of(), AppConfig.fromMap(Map.of()).jiraBoards());
    }

    @Test
    void parsesEnvFileLines() {
        Map<String, String> values = EnvFile.parse(List.of(
                "# comentário",
                "",
                "JIRA_BASE_URL=https://empresa.atlassian.net/",
                "export JIRA_EMAIL = eu@empresa.com ",
                "JIRA_JQL=\"project = PROJ AND assignee = currentUser()\"",
                "linha inválida"));

        assertEquals(Map.of(
                "JIRA_BASE_URL", "https://empresa.atlassian.net/",
                "JIRA_EMAIL", "eu@empresa.com",
                "JIRA_JQL", "project = PROJ AND assignee = currentUser()"), values);
    }

    @Test
    void usesDefaultsWhenEmpty() {
        AppConfig config = AppConfig.fromMap(Map.of());

        assertEquals(Duration.ofSeconds(5), config.pollingInterval());
        assertEquals(Duration.ofMinutes(2), config.possiblyIdleAfter());
        assertEquals(Duration.ofMinutes(5), config.inactiveAfter());
        assertFalse(config.isJiraConfigured());
    }

    @Test
    void readsProjectSettings() {
        AppConfig config = AppConfig.fromMap(Map.of(
                "JIRA_BASE_URL", "https://empresa.atlassian.net/",
                "JIRA_EMAIL", "eu@empresa.com",
                "JIRA_API_TOKEN", "segredo",
                "JIRA_PROJECT_KEYS", "PROJ, ABC,,XYZ",
                "POLLING_INTERVAL_SECONDS", "10",
                "IDLE_THRESHOLD_SECONDS", "600"));

        assertEquals(Optional.of("https://empresa.atlassian.net"), config.jiraBaseUrl());
        assertEquals(List.of("PROJ", "ABC", "XYZ"), config.jiraProjectKeys());
        assertEquals(Duration.ofSeconds(10), config.pollingInterval());
        assertEquals(Duration.ofMinutes(10), config.inactiveAfter());
        assertTrue(config.isJiraConfigured());
    }

    @Test
    void singleProjectKeyIsAccepted() {
        AppConfig config = AppConfig.fromMap(Map.of("JIRA_PROJECT_KEY", "PROJA"));

        assertEquals(List.of("PROJA"), config.jiraProjectKeys());
    }

    @Test
    void toStringHidesTheToken() {
        AppConfig config = AppConfig.fromMap(Map.of("JIRA_API_TOKEN", "segredo"));

        assertFalse(config.toString().contains("segredo"));
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromMap(Map.of("POLLING_INTERVAL_SECONDS", "abc")));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromMap(Map.of("IDLE_THRESHOLD_SECONDS", "0")));
    }

    @Test
    void workingStatusesHaveADefault() {
        assertEquals(AppConfig.DEFAULT_WORKING_STATUSES, AppConfig.fromMap(Map.of()).workingStatuses());
        assertEquals(AppConfig.DEFAULT_WORKING_STATUSES,
                AppConfig.fromMap(Map.of("JIRA_IN_PROGRESS_STATUSES", " ")).workingStatuses());
        assertTrue(AppConfig.fromMap(Map.of()).autoStart());
    }

    @Test
    void typedColumnsAddToTheDefaultsUnlessTheDefaultsAreOff() {
        assertEquals(List.of("Em andamento", "Em progresso", "In Progress", "Test"),
                AppConfig.fromMap(Map.of("JIRA_IN_PROGRESS_STATUSES", "Test, in progress")).workingStatuses());
        assertEquals(List.of("Test"), AppConfig.fromMap(Map.of(
                "JIRA_IN_PROGRESS_STATUSES", "Test", "JIRA_USE_DEFAULT_STATUSES", "false")).workingStatuses());
        // Sem padrão e sem nada digitado, as padrão continuam valendo.
        assertEquals(AppConfig.DEFAULT_WORKING_STATUSES,
                AppConfig.fromMap(Map.of("JIRA_USE_DEFAULT_STATUSES", "false")).workingStatuses());
    }

    @Test
    void autoStartCanBeTurnedOff() {
        assertFalse(AppConfig.fromMap(Map.of("CHRONOS_AUTO_START", "false")).autoStart());
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromMap(Map.of("CHRONOS_AUTO_START", "talvez")));
    }
}

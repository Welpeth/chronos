package com.chronos.tracker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvFileWriteTest {

    @TempDir
    Path dir;

    @Test
    void updatesKeepCommentsAndOrder() throws Exception {
        Path env = dir.resolve(".env");
        Files.write(env, List.of(
                "# Jira",
                "JIRA_BASE_URL=https://antigo.atlassian.net",
                "JIRA_API_TOKEN=velho",
                "# CHRONOS_DB_PATH=chronos.db",
                "POLLING_INTERVAL_SECONDS=5"), StandardCharsets.UTF_8);

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("JIRA_BASE_URL", "https://novo.atlassian.net");
        updates.put("JIRA_API_TOKEN", "novo-token");
        updates.put("CHRONOS_DB_PATH", "C:/dados/chronos.db");
        updates.put("JIRA_JQL", "project = SCRUM AND assignee = currentUser()");
        EnvFile.write(env, updates);

        assertEquals(List.of(
                "# Jira",
                "JIRA_BASE_URL=https://novo.atlassian.net",
                "JIRA_API_TOKEN=novo-token",
                "CHRONOS_DB_PATH=C:/dados/chronos.db",
                "POLLING_INTERVAL_SECONDS=5",
                "JIRA_JQL=\"project = SCRUM AND assignee = currentUser()\""), Files.readAllLines(env));
        assertEquals("project = SCRUM AND assignee = currentUser()", EnvFile.read(env).get("JIRA_JQL"));
    }

    @Test
    void createsTheFileWhenMissing() throws Exception {
        Path env = dir.resolve("novo.env");
        EnvFile.write(env, Map.of("JIRA_EMAIL", "eu@empresa.com"));
        assertEquals(Map.of("JIRA_EMAIL", "eu@empresa.com"), EnvFile.read(env));
    }
}

package com.chronos.tracker.ui;

import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoardSummaryTest {

    @Test
    void countsTheTasksOfEachConfiguredBoard() throws Exception {
        JiraService service = new JiraService() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<JiraIssue> fetchMyIssues() {
                return List.of();
            }

            @Override
            public Map<String, List<String>> fetchBoards(List<String> issueKeys) {
                return Map.of("RP-1", List.of("Quadro RP", "Suporte RP"), "RP-2", List.of("Suporte RP"));
            }

            @Override
            public Map<String, List<KanbanColumn>> fetchBoardColumns() {
                Map<String, List<KanbanColumn>> boards = new java.util.LinkedHashMap<>();
                boards.put("Quadro RP", List.of());
                boards.put("Suporte RP", List.of());
                boards.put("Vazio", List.of());
                return boards;
            }
        };
        List<JiraIssue> issues = List.of(issue("RP-1"), issue("RP-2"), issue("RP-3"));

        assertEquals("Tasks por quadro: Quadro RP: 1 · Suporte RP: 2 · Vazio: 0",
                SettingsController.boardSummary(service, issues));
    }

    private static JiraIssue issue(String key) {
        return new JiraIssue(key, "");
    }
}

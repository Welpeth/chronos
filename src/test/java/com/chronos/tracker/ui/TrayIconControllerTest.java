package com.chronos.tracker.ui;

import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrayIconControllerTest {

    private static TaskView task(String key, String summary) {
        return new TaskView(key, summary, "Em andamento", StatusCategory.IN_PROGRESS, Duration.ZERO, true, false,
                Optional.empty());
    }

    @Test
    void menuShowsKeyAndShortTitle() {
        assertEquals("SCRUM-2 — Corrigir login", TrayIconController.menuLabel(task("SCRUM-2", "Corrigir login")));
        assertEquals("SCRUM-3", TrayIconController.menuLabel(task("SCRUM-3", "")));
        String longTitle = "Implementar autenticação de usuários com login social e 2FA";
        assertEquals(40, TrayIconController.menuLabel(task("X-1", longTitle)).length() - "X-1 — ".length());
    }

    @Test
    void tooltipCountsRunningTasks() {
        assertEquals("Chronos · nenhuma task contando", TrayIconController.tooltip(0));
        assertEquals("Chronos · 1 task contando", TrayIconController.tooltip(1));
        assertEquals("Chronos · 3 tasks contando", TrayIconController.tooltip(3));
    }
}

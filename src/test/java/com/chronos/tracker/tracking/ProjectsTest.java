package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.jira.JiraService.KanbanColumn;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    @Test
    void projectComesFromTheIssueKey() {
        assertEquals("SCRUM", Projects.of("SCRUM-12"));
        assertEquals("MY-APP", Projects.of("MY-APP-3"));
        assertEquals("", Projects.of("typed by hand"));
        assertEquals("", Projects.of(null));
    }

    @Test
    void emptyProjectMatchesEverything() {
        assertTrue(Projects.matches("SCRUM-1", Projects.ALL));
        assertTrue(Projects.matches("SCRUM-1", "scrum"));
        assertFalse(Projects.matches("OPS-1", "SCRUM"));
        assertEquals(List.of("OPS", "SCRUM"), Projects.distinct(List.of("SCRUM-2", "OPS-1", "SCRUM-1", "loose")));
    }

    @Test
    void snapshotForProjectKeepsOnlyThatProjectAndItsTotals() {
        TaskView scrum = task("SCRUM-1", true);
        TaskView ops = task("OPS-7", false);
        TimeEntry scrumTime = entry("SCRUM-1", 0, 30);
        TimeEntry opsTime = entry("OPS-7", 30, 50);
        ManualEntry opsManual = new ManualEntry(1, "OPS-7", "", LocalDate.of(2026, 9, 29), Duration.ofMinutes(15),
                "", T0);
        Snapshot all = new Snapshot(ActivityState.ACTIVE, Duration.ZERO, Optional.of(scrum), List.of(scrum, ops),
                false, Duration.ofMinutes(50), Duration.ofMinutes(5), Duration.ofMinutes(15), List.of(opsManual),
                JiraSyncStatus.SYNCED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("SCRUM, OPS"),
                List.of(), List.of(opsTime, scrumTime), Map.of(), false);

        assertEquals(List.of("OPS", "SCRUM"), all.projects());
        assertSame(all, all.forProject(Projects.ALL));

        Snapshot onlyOps = all.forProject("OPS");
        assertEquals(List.of(ops), onlyOps.tasks());
        assertEquals(List.of(opsTime), onlyOps.history());
        assertEquals(Duration.ofMinutes(20), onlyOps.activeToday());
        assertEquals(Duration.ofMinutes(15), onlyOps.manualToday());
        assertEquals(Duration.ofMinutes(5), onlyOps.inactiveToday());
        // A task em destaque era de outro projeto: fica a primeira deste em andamento.
        assertEquals(Optional.of(ops), onlyOps.featuredTask());
        assertEquals(Optional.of("OPS"), onlyOps.projectLabel());

        Snapshot onlyScrum = all.forProject("SCRUM");
        assertEquals(Duration.ofMinutes(30), onlyScrum.activeToday());
        assertEquals(Duration.ZERO, onlyScrum.manualToday());
        assertEquals(Optional.of(scrum), onlyScrum.featuredTask());
    }

    @Test
    void withBoardsTheGroupIsTheBoardNotTheProject() {
        TaskView front = task("RP-1", true);
        TaskView support = task("RP-2", false);
        TaskView loose = task("RP-3", false);
        Snapshot all = new Snapshot(ActivityState.ACTIVE, Duration.ZERO, Optional.of(front),
                List.of(front, support, loose), false, Duration.ZERO, Duration.ZERO, Duration.ZERO, List.of(),
                JiraSyncStatus.SYNCED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(entry("RP-2", 0, 10)), Map.of("RP-1", "Quadro RP", "RP-2", "Suporte RP"), true);

        assertEquals(List.of("Quadro RP", "Suporte RP"), all.projects());
        assertEquals(List.of(), all.groupsOf("RP-3"));

        Snapshot onlySupport = all.forProject("Suporte RP");
        assertEquals(List.of(support), onlySupport.tasks());
        assertEquals(Duration.ofMinutes(10), onlySupport.activeToday());
        assertEquals(Optional.of(support), onlySupport.featuredTask());
        assertEquals(List.of(front), all.forProject("Quadro RP").tasks());
    }

    @Test
    void aTaskInTwoBoardsShowsInEitherAndSeveralBoardsCanBeChosen() {
        TaskView shared = task("RP-1", false);
        TaskView support = task("RP-2", false);
        TaskView other = task("RP-3", false);
        Snapshot all = new Snapshot(ActivityState.ACTIVE, Duration.ZERO, Optional.empty(),
                List.of(shared, support, other), false, Duration.ZERO, Duration.ZERO, Duration.ZERO, List.of(),
                JiraSyncStatus.SYNCED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), Map.of("RP-1", "Quadro RP\nSuporte RP", "RP-2", "Suporte RP", "RP-3", "Outro"),
                true);

        assertEquals(List.of("Quadro RP", "Suporte RP"), all.groupsOf("RP-1"));
        assertEquals(List.of("Outro", "Quadro RP", "Suporte RP"), all.projects());
        assertEquals(List.of(shared), all.forGroups(Set.of("Quadro RP")).tasks());
        assertEquals(List.of(shared, support), all.forGroups(Set.of("Suporte RP")).tasks());
        assertEquals(List.of(shared, other), all.forGroups(Set.of("Quadro RP", "Outro")).tasks());
        assertEquals(all, all.forGroups(Set.of()));
    }

    @Test
    void kanbanColumnsFollowTheChosenBoards() {
        Snapshot all = new Snapshot(ActivityState.ACTIVE, Duration.ZERO, Optional.empty(), List.of(), false,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, List.of(), JiraSyncStatus.SYNCED, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), Map.of(), true,
                new java.util.LinkedHashMap<>(Map.of(
                        "Quadro RP", List.of(new KanbanColumn("A fazer", List.of("A fazer")),
                                new KanbanColumn("Test", List.of("Em teste"))),
                        "Suporte RP", List.of(new KanbanColumn("A fazer", List.of("Backlog")),
                                new KanbanColumn("Validação", List.of("Validando"))))));

        assertEquals(List.of(new KanbanColumn("A fazer", List.of("A fazer")), new KanbanColumn("Test", List.of("Em teste"))),
                all.kanbanColumns(Set.of("Quadro RP")));
        List<String> names = all.kanbanColumns(Set.of()).stream().map(KanbanColumn::name).toList();
        assertEquals(3, names.size());
        assertEquals(java.util.Set.of("A fazer", "Test", "Validação"), java.util.Set.copyOf(names));
    }

    private static TaskView task(String key, boolean running) {
        return new TaskView(key, "", "Em andamento", StatusCategory.IN_PROGRESS, Duration.ZERO, running, false,
                running ? Optional.of(T0) : Optional.empty(), "", true, true, true);
    }

    private static TimeEntry entry(String key, int startMinute, int endMinute) {
        Instant start = T0.plus(Duration.ofMinutes(startMinute));
        Instant end = T0.plus(Duration.ofMinutes(endMinute));
        return new TimeEntry(key, start, end, Duration.between(start, end));
    }
}

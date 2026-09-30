package com.chronos.tracker.ui;

import com.chronos.tracker.jira.JiraService.KanbanColumn;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Em que coluna do kanban cada task entra. */
class KanbanBoardTest {

    @Test
    void tasksGoToTheColumnThatShowsTheirStatus() {
        TaskView todo = task("RP-1", "A fazer", StatusCategory.TO_DO);
        TaskView testing = task("RP-2", "Em teste", StatusCategory.IN_PROGRESS);
        TaskView review = task("RP-3", "Em revisão", StatusCategory.IN_PROGRESS);
        List<KanbanColumn> columns = List.of(
                new KanbanColumn("A fazer", List.of("A fazer")),
                new KanbanColumn("Test", List.of("Em teste", "Reteste")),
                new KanbanColumn("Feito", List.of("Concluído")));

        Map<String, List<TaskView>> grouped = KanbanBoard.group(List.of(todo, testing, review), columns);

        // Colunas vazias continuam, como no quadro; quem nenhuma coluna mostra vai para "Outras".
        assertEquals(List.of("A fazer", "Test", "Feito", "Outras"), List.copyOf(grouped.keySet()));
        assertEquals(List.of(testing), grouped.get("Test"));
        assertEquals(List.of(), grouped.get("Feito"));
        assertEquals(List.of(review), grouped.get("Outras"));
    }

    @Test
    void withoutBoardColumnsTasksAreGroupedByStatus() {
        TaskView done = task("RP-1", "Concluído", StatusCategory.DONE);
        TaskView doing = task("RP-2", "Em andamento", StatusCategory.IN_PROGRESS);
        TaskView todo = task("RP-3", "A fazer", StatusCategory.TO_DO);

        Map<String, List<TaskView>> grouped = KanbanBoard.group(List.of(done, doing, todo), List.of());

        assertEquals(List.of("A fazer", "Em andamento", "Concluído"), List.copyOf(grouped.keySet()));
    }

    private static TaskView task(String key, String status, StatusCategory category) {
        return new TaskView(key, "", status, category, Duration.ZERO, false, false, Optional.empty());
    }
}

package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.jira.JiraService.KanbanColumn;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Visão kanban do painel: as tasks nas colunas dos quadros do Jira. Sem as colunas do quadro (sem acesso à API de
 * quadros), agrupa pelo status, na ordem a fazer, em andamento, concluído.
 */
public final class KanbanBoard {

    static final double COLUMN_WIDTH = 230;

    private final HBox columnsBox = new HBox(14);
    private final ScrollPane root = new ScrollPane(columnsBox);
    private final Consumer<TaskView> onToggle;
    private final Consumer<TaskView> onOpen;
    /** Rolagem de cada coluna, mantida entre as atualizações para a lista não voltar ao topo a cada segundo. */
    private final Map<String, ScrollPane> scrolls = new java.util.HashMap<>();

    /**
     * @param onToggle play/pausa da task
     * @param onOpen   clique no cartão: mostra a task em "Task atual"
     */
    public KanbanBoard(Consumer<TaskView> onToggle, Consumer<TaskView> onOpen) {
        this.onToggle = onToggle;
        this.onOpen = onOpen;
        columnsBox.getStyleClass().add("kanban");
        root.setFitToHeight(true);
        root.setFitToWidth(true);
        root.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.getStyleClass().add("kanban-scroll");
    }

    public Node getView() {
        return root;
    }

    public void render(List<TaskView> tasks, List<KanbanColumn> columns) {
        Map<String, List<TaskView>> grouped = group(tasks, columns);
        scrolls.keySet().retainAll(grouped.keySet());
        columnsBox.getChildren().clear();
        grouped.forEach((name, inColumn) -> columnsBox.getChildren().add(column(name, inColumn)));
        if (grouped.isEmpty()) {
            Label empty = new Label(I18n.t("Nenhuma task sua no Jira ainda."));
            empty.getStyleClass().add("muted");
            columnsBox.getChildren().add(empty);
        }
    }

    /**
     * Tasks por coluna, na ordem das colunas. A task entra na coluna que mostra o status dela; as que nenhuma
     * coluna mostra vão para "Outras", no fim. Colunas vazias continuam aparecendo, como no quadro.
     */
    static Map<String, List<TaskView>> group(List<TaskView> tasks, List<KanbanColumn> columns) {
        Map<String, List<TaskView>> grouped = new LinkedHashMap<>();
        if (columns.isEmpty()) {
            for (StatusCategory category : List.of(StatusCategory.TO_DO, StatusCategory.IN_PROGRESS, StatusCategory.DONE)) {
                tasks.stream().filter(task -> task.category() == category)
                        .forEach(task -> grouped.computeIfAbsent(statusName(task), name -> new ArrayList<>()).add(task));
            }
            return grouped;
        }
        columns.forEach(column -> grouped.put(column.name(), new ArrayList<>()));
        List<TaskView> others = new ArrayList<>();
        for (TaskView task : tasks) {
            String status = task.statusName().strip().toLowerCase(Locale.ROOT);
            columns.stream()
                    .filter(column -> column.statuses().stream().anyMatch(s -> s.strip().toLowerCase(Locale.ROOT).equals(status)))
                    .findFirst()
                    .ifPresentOrElse(column -> grouped.get(column.name()).add(task), () -> others.add(task));
        }
        if (!others.isEmpty()) {
            grouped.put(I18n.t("Outras"), others);
        }
        return grouped;
    }

    private static String statusName(TaskView task) {
        return task.statusName().isEmpty() ? I18n.t("Manual") : task.statusName();
    }

    private Node column(String name, List<TaskView> tasks) {
        Label title = new Label(name);
        title.getStyleClass().add("kanban-title");
        Label count = new Label(String.valueOf(tasks.size()));
        count.getStyleClass().addAll("badge", "badge-neutral");
        HBox header = new HBox(8, title, count);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox cards = new VBox(10);
        cards.getStyleClass().add("kanban-cards");
        tasks.forEach(task -> cards.getChildren().add(card(task)));
        ScrollPane scroll = scrolls.computeIfAbsent(name, n -> {
            ScrollPane pane = new ScrollPane();
            pane.setFitToWidth(true);
            pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            pane.getStyleClass().add("kanban-scroll");
            return pane;
        });
        double position = scroll.getVvalue();
        scroll.setContent(cards);
        scroll.setVvalue(position);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox column = new VBox(12, header, scroll);
        column.getStyleClass().add("kanban-column");
        column.setMinWidth(COLUMN_WIDTH);
        column.setPrefWidth(COLUMN_WIDTH);
        column.setMaxWidth(COLUMN_WIDTH * 1.6);
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private Node card(TaskView task) {
        Label key = new Label(task.key());
        key.getStyleClass().add("task-key");
        Label summary = new Label(task.summary().isEmpty() ? I18n.t("Task fora do Jira") : task.summary());
        summary.getStyleClass().add("task-summary");
        summary.setWrapText(true);
        VBox text = new VBox(4, key, summary);
        if (!task.mine()) {
            Label owner = new Label(task.assignee().isEmpty() ? I18n.t("Sem responsável") : I18n.t("De {0}", task.assignee()));
            owner.getStyleClass().add("task-owner");
            text.getChildren().add(owner);
        }

        Label time = new Label(Formats.hoursMinutes(task.totalTime()));
        time.getStyleClass().add(task.running() ? "task-time-running" : "task-time");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toggle = new Button();
        toggle.setGraphic(Icons.of(task.running() ? Icons.PAUSE : Icons.PLAY, 12));
        toggle.getStyleClass().add("row-toggle");
        if (task.running()) {
            toggle.getStyleClass().add("row-toggle-running");
        }
        toggle.setDisable(!task.running() && !task.timeAllowed());
        toggle.setOnAction(e -> onToggle.accept(task));
        HBox footer = new HBox(8, TaskRows.statusBadge(task), spacer, time, toggle);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, text, footer);
        card.getStyleClass().add("kanban-card");
        if (task.running()) {
            card.getStyleClass().add("kanban-card-running");
        }
        Tooltip.install(card, new Tooltip(I18n.t("Clique para ver esta task em Task atual")));
        card.setOnMousePressed(event -> {
            // No aperto, não no clique: a lista é refeita a cada segundo e o clique se perderia entre as duas.
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            for (Node node = (Node) event.getTarget(); node != null && node != card; node = node.getParent()) {
                if (node instanceof Button) {
                    return;
                }
            }
            onOpen.accept(task);
        });
        return card;
    }
}

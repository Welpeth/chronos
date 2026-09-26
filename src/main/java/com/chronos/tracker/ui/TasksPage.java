package com.chronos.tracker.ui;

import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Todas as tasks do usuário, com filtro por status e busca por chave ou título. */
public final class TasksPage {

    private enum Filter {
        ALL("Todas", task -> true),
        RUNNING("Contando", TaskView::running),
        IN_PROGRESS("Em andamento", task -> task.category() == StatusCategory.IN_PROGRESS),
        TO_DO("A fazer", task -> task.category() == StatusCategory.TO_DO),
        DONE("Concluídas hoje", task -> task.category() == StatusCategory.DONE);

        final String label;
        final Predicate<TaskView> predicate;

        Filter(String label, Predicate<TaskView> predicate) {
            this.label = label;
            this.predicate = predicate;
        }
    }

    private final Consumer<TaskView> onToggle;
    private final ScrollPane root;
    private final VBox list = new VBox(0);
    private final Label countLabel = new Label();
    private final TextField search = new TextField();
    private final ToggleGroup filters = new ToggleGroup();
    private Snapshot last;

    public TasksPage(Consumer<TaskView> onToggle) {
        this.onToggle = onToggle;

        Label title = new Label("Tarefas");
        title.getStyleClass().add("page-title");
        countLabel.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, countLabel);
        heading.setAlignment(Pos.BASELINE_LEFT);

        HBox filterBar = new HBox(8);
        for (Filter filter : Filter.values()) {
            ToggleButton button = new ToggleButton(filter.label);
            button.setUserData(filter);
            button.setToggleGroup(filters);
            button.getStyleClass().add("filter-chip");
            filterBar.getChildren().add(button);
        }
        filters.selectToggle(filters.getToggles().get(0));
        // Um filtro sempre fica marcado: clicar no já marcado não desmarca.
        filters.selectedToggleProperty().addListener((obs, before, now) -> {
            if (now == null) {
                filters.selectToggle(before);
            }
            refresh();
        });

        search.setPromptText("Buscar por chave ou título");
        search.setPrefWidth(260);
        search.getStyleClass().add("search");
        search.textProperty().addListener((obs, before, now) -> refresh());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, filterBar, spacer, search);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, list);
        card.getStyleClass().addAll("card", "list-card");

        VBox page = new VBox(18, heading, toolbar, card);
        page.getStyleClass().add("page");

        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");
    }

    public Node getView() {
        return root;
    }

    public void render(Snapshot snapshot) {
        last = snapshot;
        refresh();
    }

    private void refresh() {
        if (last == null) {
            return;
        }
        Filter filter = Optional.ofNullable(filters.getSelectedToggle())
                .map(toggle -> (Filter) toggle.getUserData())
                .orElse(Filter.ALL);
        String query = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);

        List<TaskView> visible = last.tasks().stream()
                .filter(filter.predicate)
                .filter(task -> query.isEmpty()
                        || task.key().toLowerCase(Locale.ROOT).contains(query)
                        || task.summary().toLowerCase(Locale.ROOT).contains(query))
                .toList();

        countLabel.setText(last.tasks().size() + (last.tasks().size() == 1 ? " task" : " tasks")
                + " · " + last.runningCount() + " contando");
        list.getChildren().clear();
        if (visible.isEmpty()) {
            Label empty = new Label("Nenhuma task neste filtro.");
            empty.getStyleClass().add("muted");
            list.getChildren().add(empty);
            return;
        }
        visible.forEach(task -> list.getChildren().add(TaskRows.row(task, onToggle)));
    }
}

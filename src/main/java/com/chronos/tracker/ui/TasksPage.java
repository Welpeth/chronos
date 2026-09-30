package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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

/**
 * Tasks em duas abas: Geral, com todas, e Colunas monitoradas, só com as que estão nas colunas que contam tempo.
 * As duas têm o mesmo filtro por status e a mesma busca por chave ou título.
 */
public final class TasksPage {

    /** Quadros de cada task, para mostrar na linha; vazio sem JIRA_BOARDS. */
    private java.util.function.Function<String, List<String>> boardsOf = key -> List.of();

    private enum Filter {
        ALL(task -> true),
        RUNNING(TaskView::running),
        IN_PROGRESS(task -> task.category() == StatusCategory.IN_PROGRESS),
        TO_DO(task -> task.category() == StatusCategory.TO_DO),
        DONE(task -> task.category() == StatusCategory.DONE);

        final Predicate<TaskView> predicate;

        Filter(Predicate<TaskView> predicate) {
            this.predicate = predicate;
        }

        String label() {
            return switch (this) {
                case ALL -> I18n.t("Todas");
                case RUNNING -> I18n.t("Contando");
                case IN_PROGRESS -> I18n.t("Em andamento");
                case TO_DO -> I18n.t("A fazer");
                case DONE -> I18n.t("Concluídas hoje");
            };
        }
    }

    private final Consumer<TaskView> onToggle;
    private final VBox root;
    private final TabPane tabs = BrowserTabs.create();
    private final Tab generalTab;
    private final Tab columnsTab;
    private final VBox generalList = new VBox(0);
    private final VBox columnsList = new VBox(0);
    private final VBox generalBody;
    private final VBox columnsBody;
    private final HBox toolbar;
    private final Label countLabel = new Label();
    private final TextField search = new TextField();
    private final ToggleGroup filters = new ToggleGroup();
    private Snapshot last;

    public TasksPage(Consumer<TaskView> onToggle) {
        this.onToggle = onToggle;

        Label title = new Label(I18n.t("Tarefas"));
        title.getStyleClass().add("page-title");
        countLabel.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, countLabel);
        heading.setAlignment(Pos.BASELINE_LEFT);

        HBox filterBar = new HBox(8);
        for (Filter filter : Filter.values()) {
            ToggleButton button = new ToggleButton(filter.label());
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

        search.setPromptText(I18n.t("Buscar por chave ou título"));
        search.setPrefWidth(260);
        search.getStyleClass().add("search");
        search.textProperty().addListener((obs, before, now) -> refresh());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar = new HBox(12, filterBar, spacer, search);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        generalList.getStyleClass().add("list-card");
        columnsList.getStyleClass().add("list-card");
        generalBody = tabBody(generalList);
        Label columnsHint = new Label(
                I18n.t("Tasks nas colunas que contam tempo (configuradas na aba Colunas das Configurações)."));
        columnsHint.getStyleClass().add("muted");
        columnsHint.setWrapText(true);
        columnsBody = tabBody(columnsHint, columnsList);
        generalTab = BrowserTabs.tab(I18n.t("Geral"), generalBody);
        columnsTab = BrowserTabs.tab(I18n.t("Colunas monitoradas"), columnsBody);
        tabs.getTabs().addAll(generalTab, columnsTab);
        // A barra de filtros é uma só: vai para a aba aberta.
        generalBody.getChildren().add(0, toolbar);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, before, now) -> {
            (now == columnsTab ? columnsBody : generalBody).getChildren().add(0, toolbar);
            refresh();
        });

        root = new VBox(14, heading, tabs);
        root.getStyleClass().add("page");
    }

    private static VBox tabBody(Node... content) {
        VBox body = new VBox(12, content);
        body.getStyleClass().add("browser-tab-body");
        return body;
    }

    public Node getView() {
        return root;
    }

    public void render(Snapshot snapshot) {
        boardsOf = snapshot.byBoard() ? snapshot::groupsOf : key -> List.of();
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

        boolean columnsOnly = tabs.getSelectionModel().getSelectedItem() == columnsTab;
        VBox list = columnsOnly ? columnsList : generalList;
        long inColumns = last.tasks().stream().filter(TaskView::inWorkingColumn).count();
        generalTab.setText(I18n.t("Geral ({0})", last.tasks().size()));
        columnsTab.setText(I18n.t("Colunas monitoradas ({0})", inColumns));

        List<TaskView> visible = last.tasks().stream()
                .filter(task -> !columnsOnly || task.inWorkingColumn())
                .filter(filter.predicate)
                .filter(task -> query.isEmpty()
                        || task.key().toLowerCase(Locale.ROOT).contains(query)
                        || task.summary().toLowerCase(Locale.ROOT).contains(query))
                .toList();

        countLabel.setText(last.tasks().size() == 1
                ? I18n.t("{0} task · {1} contando", last.tasks().size(), last.runningCount())
                : I18n.t("{0} tasks · {1} contando", last.tasks().size(), last.runningCount()));
        list.getChildren().clear();
        if (visible.isEmpty()) {
            Label empty = new Label(columnsOnly && inColumns == 0
                    ? I18n.t("Nenhuma task nas colunas monitoradas agora.")
                    : I18n.t("Nenhuma task neste filtro."));
            empty.getStyleClass().add("muted");
            list.getChildren().add(empty);
            return;
        }
        visible.forEach(task -> list.getChildren().add(TaskRows.row(task, onToggle, boardsOf.apply(task.key()))));
    }
}

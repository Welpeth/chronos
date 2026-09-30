package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.StatusCategory;
import com.chronos.tracker.tracking.ActivityEvent;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Painel principal: task em destaque com o cronômetro grande, cartões de status, progresso do dia,
 * lista de tarefas do projeto e atividade recente.
 */
public final class DashboardPage {

    static final Duration DAILY_GOAL = TrackingEngine.DAILY_LIMIT;
    /** Quantos itens cada lista mostra no painel; o resto fica no I18n.t("Mostrar mais"). */
    static final int VISIBLE_EVENTS = 4;
    static final int VISIBLE_TASKS = 5;

    private final Consumer<TaskView> onToggle;
    private final BoardChips boardChips;
    /** Quadros de cada task, para mostrar na linha; vazio sem JIRA_BOARDS. */
    private Function<String, List<String>> boardsOf = key -> List.of();
    private final Runnable onAddManual;
    /** Rolagem da visão geral. */
    private final ScrollPane root;
    private final VBox view;
    private final javafx.scene.control.ToggleGroup viewTabs = new javafx.scene.control.ToggleGroup();
    private final javafx.scene.control.ToggleButton overviewTab = new javafx.scene.control.ToggleButton();
    private final javafx.scene.control.ToggleButton kanbanTab = new javafx.scene.control.ToggleButton();
    private final StackPane content = new StackPane();
    private final KanbanBoard kanban;
    private Set<String> selectedGroups = Set.of();

    // Task atual
    private final VBox currentCard = card("current-card");
    private final Label currentKey = new Label();
    private final Label currentSummary = new Label();
    private final HBox currentChips = new HBox(8);
    private final Label currentTime = new Label();
    private final Label currentSince = new Label();
    private final Button currentToggle = new Button();
    private final VBox currentBody = new VBox(14);
    private final VBox currentEmpty = new VBox(6);
    private TaskView featured;
    /** Task escolhida com um clique na lista; vazio segue a automática (a que começou a contar por último). */
    private String focusedKey;
    private final Button backToAuto = new Button();
    private Snapshot last;

    // Cartões de status
    private final Circle activityDot = new Circle(5);
    private final Label activityValue = new Label();
    private final Label activityDetail = new Label();
    private final Circle jiraDot = new Circle(5);
    private final Label jiraValue = new Label();
    private final Label jiraDetail = new Label();
    private final Label projectValue = new Label();
    private final Label projectDetail = new Label();

    // Progresso do dia
    private final Label goalLabel = new Label();
    private final ProgressTrack progress = new ProgressTrack();
    private final Label activeValue = new Label();
    private final Label idleValue = new Label();
    private final Label manualValue = new Label();
    private final Label doneValue = new Label();
    private final Label inProgressValue = new Label();

    // Coluna da direita
    private final VBox taskList = new VBox(0);
    private final VBox eventList = new VBox(0);
    private final Button moreTasks = moreButton();
    private final Button moreEvents = moreButton();
    private List<TaskView> allTasks = List.of();
    private List<ActivityEvent> allEvents = List.of();
    private PagedListDialog<TaskView> tasksDialog;
    private PagedListDialog<ActivityEvent> eventsDialog;

    public DashboardPage(Consumer<TaskView> onToggle, Runnable onAddManual, Consumer<Set<String>> onSelectBoards) {
        this.onToggle = onToggle;
        this.onAddManual = onAddManual;
        this.boardChips = new BoardChips(onSelectBoards);

        VBox left = new VBox(18, buildCurrentCard(), buildStatusCards(), buildProgressCard());
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setMinWidth(0);

        moreTasks.setOnAction(e -> openTasks());
        moreEvents.setOnAction(e -> openEvents());
        VBox right = new VBox(18, buildTasksCard(), buildEventsCard());
        right.setPrefWidth(420);
        right.setMinWidth(380);

        HBox columns = new HBox(18, left, right);
        columns.getStyleClass().add("dashboard-overview");

        root = new ScrollPane(columns);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");

        kanban = new KanbanBoard(onToggle, task -> {
            viewTabs.selectToggle(overviewTab);
            focus(task.key());
        });

        overviewTab.setText(I18n.t("Visão geral"));
        kanbanTab.setText(I18n.t("Visão kanban"));
        for (javafx.scene.control.ToggleButton tab : List.of(overviewTab, kanbanTab)) {
            tab.setToggleGroup(viewTabs);
            tab.getStyleClass().add("view-tab");
        }
        viewTabs.selectToggle(overviewTab);
        // Uma aba sempre fica marcada: clicar na já aberta não desmarca.
        viewTabs.selectedToggleProperty().addListener((obs, before, now) -> {
            if (now == null) {
                viewTabs.selectToggle(before);
                return;
            }
            showView();
        });
        HBox tabsBar = new HBox(4, overviewTab, kanbanTab);
        tabsBar.getStyleClass().add("view-tabs");

        content.getChildren().setAll(root);
        VBox.setVgrow(content, Priority.ALWAYS);
        view = new VBox(14, tabsBar, boardChips.getView(), content);
        view.getStyleClass().add("dashboard");
    }

    private void showView() {
        boolean kanbanOpen = viewTabs.getSelectedToggle() == kanbanTab;
        content.getChildren().setAll(kanbanOpen ? kanban.getView() : root);
        if (last != null) {
            render(last);
        }
    }

    public Node getView() {
        return view;
    }

    /** Chips dos quadros (ou projetos) acima do painel; a escolha vale para todas as páginas. */
    public void setGroups(List<String> groups, Set<String> selected, boolean byBoard) {
        selectedGroups = selected;
        boardChips.update(groups, selected, byBoard);
    }

    public void render(Snapshot snapshot) {
        last = snapshot;
        boardsOf = snapshot.byBoard() ? snapshot::groupsOf : key -> List.of();
        if (viewTabs.getSelectedToggle() == kanbanTab) {
            kanban.render(snapshot.tasks(), snapshot.kanbanColumns(selectedGroups));
            return;
        }
        renderCurrent(snapshot);
        renderStatus(snapshot);
        renderProgress(snapshot);
        renderTasks(snapshot.tasks());
        renderEvents(snapshot.recentEvents());
    }

    // ---- Task atual ------------------------------------------------------------------------------

    private VBox buildCurrentCard() {
        backToAuto.getStyleClass().add("link-chip");
        backToAuto.setOnAction(e -> focus(null));
        currentKey.getStyleClass().add("current-key");
        currentSummary.getStyleClass().add("current-summary");
        currentSummary.setWrapText(true);
        HBox heading = new HBox(14, Icons.of(Icons.DIAMOND, 34, "icon-jira"), new VBox(4, currentKey, currentSummary));
        heading.setAlignment(Pos.CENTER_LEFT);

        Label timerCaption = new Label(I18n.t("Tempo na task"));
        timerCaption.getStyleClass().add("timer-caption");
        HBox captionRow = new HBox(8, Icons.of(Icons.CLOCK, 22, "icon-blue"), timerCaption);
        captionRow.setAlignment(Pos.CENTER_LEFT);
        currentTime.getStyleClass().add("timer");
        currentSince.getStyleClass().add("timer-since");
        VBox timerText = new VBox(6, captionRow, currentTime, currentSince);
        HBox.setHgrow(timerText, Priority.ALWAYS);

        currentToggle.getStyleClass().add("big-toggle");
        currentToggle.setOnAction(event -> {
            if (featured != null) {
                onToggle.accept(featured);
            }
        });
        HBox timerBox = new HBox(16, timerText, currentToggle);
        timerBox.setAlignment(Pos.CENTER_LEFT);
        timerBox.getStyleClass().add("timer-box");

        currentBody.getChildren().addAll(heading, currentChips, timerBox);
        VBox.setMargin(currentChips, new javafx.geometry.Insets(0, 0, 0, 48));

        Label emptyTitle = new Label(I18n.t("Nenhuma task contando tempo"));
        emptyTitle.getStyleClass().add("current-summary");
        Label emptyHint = new Label(I18n.t("Mova uma task para \"Em andamento\" no Jira ou dê play numa task da lista."));
        emptyHint.getStyleClass().add("muted");
        emptyHint.setWrapText(true);
        currentEmpty.getChildren().addAll(emptyTitle, emptyHint);

        currentCard.getChildren().addAll(cardTitle(I18n.t("Task atual")), currentBody);
        return currentCard;
    }

    private void renderCurrent(Snapshot snapshot) {
        Optional<TaskView> automatic = snapshot.featuredTask();
        Optional<TaskView> focused = Optional.ofNullable(focusedKey)
                .flatMap(key -> snapshot.tasks().stream().filter(task -> task.key().equals(key)).findFirst());
        if (focused.isEmpty()) {
            // A escolhida saiu da lista (outro quadro, saiu do Jira): volta para a automática.
            focusedKey = null;
        }
        featured = focused.or(() -> automatic).orElse(null);
        Node body = featured == null ? currentEmpty : currentBody;
        if (currentCard.getChildren().get(1) != body) {
            currentCard.getChildren().set(1, body);
        }
        if (featured == null) {
            return;
        }
        currentKey.setText(featured.key());
        currentSummary.setText(featured.summary().isEmpty() ? I18n.t("Task fora do Jira") : featured.summary());

        currentChips.getChildren().setAll(TaskRows.statusBadge(featured));
        long othersRunning = snapshot.runningCount() - (featured.running() ? 1 : 0);
        if (othersRunning > 0) {
            Label others = new Label(othersRunning == 1
                    ? I18n.t("+{0} outra contando", othersRunning)
                    : I18n.t("+{0} outras contando", othersRunning));
            others.getStyleClass().addAll("badge", "badge-neutral");
            currentChips.getChildren().add(others);
        }
        if (focused.isPresent() && automatic.isPresent() && !automatic.get().key().equals(featured.key())) {
            backToAuto.setText(I18n.t("Voltar para {0}", automatic.get().key()));
            currentChips.getChildren().add(backToAuto);
        }

        currentTime.setText(Formats.hms(featured.totalTime()));
        if (featured.running()) {
            currentSince.setText(I18n.t("Contando desde {0}", Formats.clock(featured.runningSince().orElseThrow())));
        } else if (snapshot.pausedForInactivity()) {
            currentSince.setText(I18n.t("Pausada por inatividade"));
        } else {
            currentSince.setText(I18n.t("Pausada"));
        }

        currentToggle.setGraphic(Icons.of(featured.running() ? Icons.PAUSE : Icons.PLAY, 30, "icon-dark"));
        currentToggle.setTooltip(new Tooltip(featured.running() ? I18n.t("Pausar o tempo desta task") : I18n.t("Contar tempo nesta task")));
    }

    // ---- Cartões de status -----------------------------------------------------------------------

    private GridPane buildStatusCards() {
        GridPane row = new GridPane();
        row.setHgap(18);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            row.getColumnConstraints().add(column);
        }
        row.addRow(0,
                statCard(Icons.MONITOR, I18n.t("Status da atividade"), activityDot, activityValue, activityDetail),
                statCard(Icons.DIAMOND, I18n.t("Sincronização Jira"), jiraDot, jiraValue, jiraDetail),
                statCard(Icons.CHART, I18n.t("Projeto"), null, projectValue, projectDetail));
        return row;
    }

    private VBox statCard(String icon, String title, Circle dot, Label value, Label detail) {
        Label caption = new Label(title);
        caption.getStyleClass().add("stat-title");
        caption.setWrapText(true);
        HBox header = new HBox(10, Icons.of(icon, 18, "icon-blue"), caption);
        header.setAlignment(Pos.CENTER_LEFT);

        value.getStyleClass().add("stat-value");
        value.setWrapText(true);
        HBox valueRow = dot == null ? new HBox(value) : new HBox(8, dot, value);
        valueRow.setAlignment(Pos.CENTER_LEFT);
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);

        VBox body = new VBox(8, valueRow, detail);
        VBox.setMargin(body, new javafx.geometry.Insets(0, 0, 0, 28));
        VBox card = card("stat-card");
        card.getChildren().addAll(header, body);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        return card;
    }

    private void renderStatus(Snapshot snapshot) {
        ActivityState activity = snapshot.activity();
        setDot(activityDot, switch (activity) {
            case ACTIVE -> "dot-green";
            case POSSIBLY_IDLE -> "dot-yellow";
            case INACTIVE -> "dot-red";
        });
        activityValue.setText(switch (activity) {
            case ACTIVE -> I18n.t("Ativo");
            case POSSIBLY_IDLE -> I18n.t("Possivelmente ausente");
            case INACTIVE -> I18n.t("Inativo");
        });
        activityDetail.setText(I18n.t("Última interação: {0}", Formats.ago(snapshot.idleTime())));

        JiraSyncStatus jira = snapshot.jiraStatus();
        setDot(jiraDot, jira == JiraSyncStatus.SYNCED ? "dot-green" : jira.isError() ? "dot-red" : "dot-gray");
        jiraValue.setText(switch (jira) {
            case SYNCED -> I18n.t("Conectado");
            case SYNCING -> I18n.t("Conectando...");
            case NOT_CONFIGURED -> I18n.t("Não configurado");
            case AUTH_ERROR -> I18n.t("Credenciais inválidas");
            case QUERY_ERROR -> I18n.t("Consulta recusada");
            case ERROR -> I18n.t("Sem conexão");
        });
        String lastSync = snapshot.lastSync().map(at -> I18n.t("Última sync: {0}", Formats.clock(at))).orElse(I18n.t("Ainda não sincronizou"));
        jiraDetail.setText(snapshot.jiraError().map(error -> lastSync + "\n" + error).orElse(lastSync));

        String label = snapshot.projectLabel().orElse("—");
        int separator = label.indexOf(" · ");
        projectValue.setText(separator < 0 ? label : label.substring(0, separator));
        projectDetail.setText(separator < 0 ? "" : label.substring(separator + 3));
    }

    private static void setDot(Circle dot, String styleClass) {
        dot.getStyleClass().setAll("dot", styleClass);
    }

    // ---- Progresso do dia ------------------------------------------------------------------------

    private VBox buildProgressCard() {
        Label title = cardTitle(I18n.t("Progresso do dia"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        goalLabel.getStyleClass().add("goal");
        Button addManual = new Button("+");
        addManual.getStyleClass().add("add-manual");
        addManual.setTooltip(new Tooltip(I18n.t("Adicionar tempo manual")));
        addManual.setOnAction(e -> onAddManual.run());
        HBox header = new HBox(12, title, spacer, goalLabel, addManual);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane metrics = new GridPane();
        metrics.getStyleClass().add("metrics");
        for (int i = 0; i < 5; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(20);
            metrics.getColumnConstraints().add(column);
        }
        metrics.add(metric(Icons.PLAY, I18n.t("Tempo ativo"), "dot-blue", activeValue), 0, 0);
        metrics.add(metric(Icons.PAUSE, I18n.t("Tempo ocioso"), "dot-yellow", idleValue), 1, 0);
        metrics.add(metric(Icons.CLOCK, I18n.t("Tempo manual"), "dot-manual", manualValue), 2, 0);
        metrics.add(metric(Icons.CHECK_CIRCLE, I18n.t("Concluídas"), "dot-gray", doneValue), 3, 0);
        metrics.add(metric(Icons.CIRCLE, I18n.t("Em andamento"), "dot-gray", inProgressValue), 4, 0);

        VBox card = card("progress-card");
        card.getChildren().addAll(header, progress, metrics);
        return card;
    }

    private VBox metric(String icon, String caption, String dotClass, Label value) {
        Label label = new Label(caption);
        label.getStyleClass().add("metric-caption");
        label.setWrapText(true);
        HBox top = new HBox(6, Icons.of(icon, 12, "icon-dark"), label);
        top.setAlignment(Pos.CENTER_LEFT);
        Circle dot = new Circle(5);
        setDot(dot, dotClass);
        value.getStyleClass().add("metric-value");
        HBox bottom = new HBox(10, dot, value);
        bottom.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, top, bottom);
        box.getStyleClass().add("metric");
        return box;
    }

    private void renderProgress(Snapshot snapshot) {
        Duration worked = snapshot.workedToday();
        goalLabel.setText(Formats.hoursMinutes(worked) + " / " + DAILY_GOAL.toHours() + "h");
        progress.setFractions(fraction(snapshot.activeToday()), fraction(snapshot.manualToday()),
                fraction(snapshot.inactiveToday()));
        activeValue.setText(Formats.hoursMinutes(snapshot.activeToday()));
        idleValue.setText(Formats.hoursMinutes(snapshot.inactiveToday()));
        manualValue.setText(Formats.hoursMinutes(snapshot.manualToday()));
        doneValue.setText(Long.toString(snapshot.countByCategory(StatusCategory.DONE)));
        inProgressValue.setText(Long.toString(snapshot.countByCategory(StatusCategory.IN_PROGRESS)));
    }

    private static double fraction(Duration duration) {
        return Math.min(1.0, (double) duration.toSeconds() / DAILY_GOAL.toSeconds());
    }

    /** Barra com o tempo ativo, o manual e o ocioso, um depois do outro, proporcionais à meta do dia. */
    private static final class ProgressTrack extends Pane {
        private final Region active = new Region();
        private final Region manual = new Region();
        private final Region idle = new Region();
        private double activeFraction;
        private double manualFraction;
        private double idleFraction;

        ProgressTrack() {
            getStyleClass().add("progress-track");
            active.getStyleClass().add("progress-active");
            manual.getStyleClass().add("progress-manual");
            idle.getStyleClass().add("progress-inactive");
            getChildren().addAll(active, manual, idle);
            setPrefHeight(14);
            setMinHeight(14);
        }

        void setFractions(double activeFraction, double manualFraction, double idleFraction) {
            this.activeFraction = Math.min(activeFraction, 1.0);
            this.manualFraction = Math.min(manualFraction, 1.0 - this.activeFraction);
            this.idleFraction = Math.min(idleFraction, 1.0 - this.activeFraction - this.manualFraction);
            requestLayout();
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            double height = getHeight();
            double activeWidth = width * activeFraction;
            double manualWidth = width * manualFraction;
            active.resizeRelocate(0, 0, activeWidth, height);
            manual.resizeRelocate(activeWidth, 0, manualWidth, height);
            idle.resizeRelocate(activeWidth + manualWidth, 0, width * idleFraction, height);
        }
    }

    // ---- Coluna da direita -----------------------------------------------------------------------

    private VBox buildTasksCard() {
        VBox card = card("list-card");
        card.getChildren().addAll(cardTitle(I18n.t("Tarefas do projeto")), taskList, moreTasks);
        return card;
    }

    private void renderTasks(List<TaskView> tasks) {
        allTasks = tasks;
        taskList.getChildren().clear();
        showMore(moreTasks, tasks.size(), VISIBLE_TASKS);
        if (tasksDialog != null && tasksDialog.isShowing()) {
            tasksDialog.update(tasks);
        }
        if (tasks.isEmpty()) {
            Label empty = new Label(I18n.t("Nenhuma task sua no Jira ainda."));
            empty.getStyleClass().add("muted");
            taskList.getChildren().add(empty);
            return;
        }
        tasks.stream().limit(VISIBLE_TASKS).forEach(task -> taskList.getChildren().add(focusable(task)));
    }

    /** Linha da lista que, clicada fora do botão de play, traz a task para o cartão "Task atual". */
    private Node focusable(TaskView task) {
        HBox row = TaskRows.row(task, onToggle, boardsOf.apply(task.key()));
        row.getStyleClass().add("task-row-clickable");
        if (featured != null && task.key().equals(featured.key())) {
            row.getStyleClass().add("task-row-focused");
        }
        Tooltip.install(row, new Tooltip(I18n.t("Clique para ver esta task em Task atual")));
        row.setOnMousePressed(event -> {
            // No aperto, não no clique: a lista é refeita a cada segundo e o clique se perderia entre as duas.
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            for (Node node = (Node) event.getTarget(); node != null && node != row; node = node.getParent()) {
                if (node instanceof Button) {
                    return;
                }
            }
            focus(task.key());
        });
        return row;
    }

    private void focus(String key) {
        focusedKey = key;
        if (tasksDialog != null && tasksDialog.isShowing()) {
            tasksDialog.hide();
        }
        if (last != null) {
            renderCurrent(last);
            renderTasks(last.tasks());
        }
        root.setVvalue(0);
    }

    private void openTasks() {
        if (tasksDialog == null) {
            tasksDialog = new PagedListDialog<>(root.getScene().getWindow(), Icons.LIST, I18n.t("Tarefas do projeto"),
                    I18n.t("Nenhuma task sua no Jira ainda."), this::focusable);
        }
        tasksDialog.show(allTasks);
    }

    private VBox buildEventsCard() {
        VBox card = card("list-card");
        card.getChildren().addAll(cardTitle(I18n.t("Atividade recente")), eventList, moreEvents);
        return card;
    }

    private void renderEvents(List<ActivityEvent> events) {
        allEvents = events;
        eventList.getChildren().clear();
        showMore(moreEvents, events.size(), VISIBLE_EVENTS);
        if (eventsDialog != null && eventsDialog.isShowing()) {
            eventsDialog.update(events);
        }
        if (events.isEmpty()) {
            Label empty = new Label(I18n.t("Nada por aqui ainda."));
            empty.getStyleClass().add("muted");
            eventList.getChildren().add(empty);
            return;
        }
        events.stream().limit(VISIBLE_EVENTS).forEach(event -> eventList.getChildren().add(eventRow(event)));
    }

    private void openEvents() {
        if (eventsDialog == null) {
            eventsDialog = new PagedListDialog<>(root.getScene().getWindow(), Icons.CLOCK, I18n.t("Atividade recente"),
                    I18n.t("Nada por aqui ainda."), DashboardPage::eventRow);
        }
        eventsDialog.show(allEvents);
    }

    private static Button moreButton() {
        Button button = new Button(I18n.t("Mostrar mais"));
        button.getStyleClass().add("show-more");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    /** O botão só aparece quando há mais itens do que cabem no painel. */
    private static void showMore(Button button, int total, int visible) {
        boolean hidden = total > visible;
        button.setText(I18n.t("Mostrar mais ({0})", total - visible));
        button.setVisible(hidden);
        button.setManaged(hidden);
    }

    private static HBox eventRow(ActivityEvent event) {
        Circle dot = new Circle(5);
        setDot(dot, switch (event.kind()) {
            case SYNC -> "dot-green";
            case ACTIVITY -> "dot-yellow";
            case TASK -> "dot-blue";
            case ALERT, ERROR -> "dot-red";
        });
        StackPane dotBox = new StackPane(dot);
        dotBox.setMinWidth(14);
        dotBox.setAlignment(Pos.TOP_CENTER);
        dotBox.setPadding(new javafx.geometry.Insets(5, 0, 0, 0));

        Label time = new Label(Formats.clock(event.at()));
        time.getStyleClass().add("event-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        Label title = new Label(event.title());
        title.getStyleClass().add("event-title");
        title.setWrapText(true);
        Label detail = new Label(event.detail());
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        VBox text = new VBox(4, title, detail);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(14, dotBox, time, text);
        row.getStyleClass().add("event-row");
        return row;
    }

    // ---- Utilitários -----------------------------------------------------------------------------

    private static VBox card(String styleClass) {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", styleClass);
        return card;
    }

    private static Label cardTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("card-title");
        return label;
    }
}

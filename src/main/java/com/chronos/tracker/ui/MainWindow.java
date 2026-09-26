package com.chronos.tracker.ui;

import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.JiraUser;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Janela principal: barra superior, menu lateral e a página atual. */
public final class MainWindow {

    private enum Page { DASHBOARD, TASKS, HISTORY }

    private final BorderPane root = new BorderPane();
    private final DashboardPage dashboard;
    private final TasksPage tasks;
    private final HistoryPage history;
    private final Map<Page, HBox> navItems = new EnumMap<>(Page.class);
    private Page page = Page.DASHBOARD;
    private Snapshot last;

    private final Circle jiraDot = new Circle(4);
    private final Label jiraLabel = new Label();
    private final Label lastUpdate = new Label();
    private final Label avatar = new Label();
    private final Label userName = new Label();
    private final Label userEmail = new Label();

    public MainWindow(Consumer<TaskView> onToggle, HistoryStore store) {
        this.dashboard = new DashboardPage(onToggle);
        this.tasks = new TasksPage(onToggle);
        this.history = new HistoryPage(store);
        root.getStyleClass().add("app");
        root.setTop(buildTopBar());
        root.setLeft(buildSidebar());
        root.setCenter(dashboard.getView());
    }

    public Parent getView() {
        return root;
    }

    public void render(Snapshot snapshot) {
        JiraSyncStatus jira = snapshot.jiraStatus();
        jiraDot.getStyleClass().setAll("dot",
                jira == JiraSyncStatus.SYNCED ? "dot-green" : jira.isError() ? "dot-red" : "dot-gray");
        jiraLabel.setText(jira == JiraSyncStatus.SYNCED ? "Jira conectado" : "Jira: " + jira.label());
        lastUpdate.setText(snapshot.lastSync().map(at -> "Última atualização: " + Formats.clock(at)).orElse(""));

        JiraUser user = snapshot.user().orElse(new JiraUser("", ""));
        String name = user.displayName().isEmpty() ? "Você" : user.displayName();
        userName.setText(name);
        userEmail.setText(user.email());
        avatar.setText(name.substring(0, 1).toUpperCase());

        last = snapshot;
        renderPage();
    }

    private void show(Page target) {
        page = target;
        navItems.forEach((item, node) -> {
            node.getStyleClass().remove("nav-selected");
            if (item == target) {
                node.getStyleClass().add("nav-selected");
            }
        });
        root.setCenter(switch (target) {
            case DASHBOARD -> dashboard.getView();
            case TASKS -> tasks.getView();
            case HISTORY -> history.getView();
        });
        renderPage();
    }

    /** Só a página visível é atualizada a cada segundo. */
    private void renderPage() {
        if (last == null) {
            return;
        }
        switch (page) {
            case DASHBOARD -> dashboard.render(last);
            case TASKS -> tasks.render(last);
            case HISTORY -> history.render(last);
        }
    }

    private HBox buildTopBar() {
        Label logoText = new Label("Chronos");
        logoText.getStyleClass().add("logo");
        StackPane logoIcon = new StackPane(Icons.of(Icons.CLOCK, 30, "icon-logo"));
        HBox logo = new HBox(10, logoIcon, logoText);
        logo.setAlignment(Pos.CENTER_LEFT);
        logo.setPrefWidth(250);

        HBox jira = new HBox(6, jiraDot, jiraLabel);
        jira.setAlignment(Pos.CENTER_LEFT);
        Label divider = new Label("|");
        divider.getStyleClass().add("muted");
        jiraLabel.getStyleClass().add("topbar-text");
        lastUpdate.getStyleClass().add("topbar-text");
        HBox status = new HBox(14, jira, divider, lastUpdate);
        status.setAlignment(Pos.CENTER);
        HBox.setHgrow(status, Priority.ALWAYS);

        Region balance = new Region();
        balance.setPrefWidth(250);

        HBox bar = new HBox(logo, status, balance);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private VBox buildSidebar() {
        VBox nav = new VBox(6,
                navItem(Page.DASHBOARD, Icons.HOME, "Painel"),
                navItem(Page.TASKS, Icons.LIST, "Tarefas"),
                navItem(Page.HISTORY, Icons.CLOCK, "Histórico"));
        navItems.get(Page.DASHBOARD).getStyleClass().add("nav-selected");

        avatar.getStyleClass().add("avatar");
        avatar.setAlignment(Pos.CENTER);
        userName.getStyleClass().add("user-name");
        userEmail.getStyleClass().add("user-email");
        VBox userText = new VBox(2, userName, userEmail);
        HBox userCard = new HBox(10, avatar, userText);
        userCard.setAlignment(Pos.CENTER_LEFT);
        userCard.getStyleClass().add("user-card");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox sidebar = new VBox(nav, spacer, userCard);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private HBox navItem(Page target, String icon, String text) {
        Label label = new Label(text);
        label.getStyleClass().add("nav-label");
        HBox item = new HBox(14, Icons.of(icon, 20, "icon-nav"), label);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(event -> show(target));
        navItems.put(target, item);
        return item;
    }
}

package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.JiraUser;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.Projects;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;


import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Janela principal: barra superior, menu lateral e a página atual. */
public final class MainWindow {

    private enum Page { DASHBOARD, TASKS, HISTORY, WORKLOG, COMMENTS, SETTINGS }

    private final BorderPane root = new BorderPane();
    private final DashboardPage dashboard;
    private final TasksPage tasks;
    private final HistoryPage history;
    private final WorklogPage worklog;
    private final CommentsPage comments;
    private final Label commentsNavLabel = new Label();
    private boolean commentsEnabled;
    private final SettingsPage settings;
    private final Map<Page, HBox> navItems = new EnumMap<>(Page.class);
    private Page page = Page.DASHBOARD;
    private Snapshot last;

    private final Circle jiraDot = new Circle(4);
    private final Label jiraLabel = new Label();
    private final Label lastUpdate = new Label();
    private final Label avatar = new Label();
    private final Label userName = new Label();
    private final Label userEmail = new Label();
    /** Quadros (ou projetos) mostrados nas páginas; vazio mostra todos. Escolhidos nos chips do painel. */
    private Set<String> project = loadProject();

    public MainWindow(Consumer<TaskView> onToggle, Runnable onAddManual, HistoryStore store,
                      WorklogPage.Handler worklogHandler, CommentsPage.Handler commentsHandler,
                      SettingsPage.Handler settingsHandler) {
        this.dashboard = new DashboardPage(onToggle, onAddManual, this::selectProject);
        this.tasks = new TasksPage(onToggle);
        this.history = new HistoryPage(store);
        this.worklog = new WorklogPage(worklogHandler);
        this.comments = new CommentsPage(commentsHandler);
        comments.setOnPendingCount(count -> commentsNavLabel.setText(count == 0 ? I18n.t("Comentários")
                : I18n.t("Comentários ({0})", count)));
        this.settings = new SettingsPage(settingsHandler);
        history.setGrouping(this::groupOf);
        worklog.setGrouping(this::groupOf);
        comments.setGrouping(this::groupOf);
        history.setProject(project);
        worklog.setProject(project);
        comments.setProject(project);
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
        jiraLabel.setText(jira == JiraSyncStatus.SYNCED ? I18n.t("Jira conectado") : I18n.t("Jira: {0}", jira.label()));
        lastUpdate.setText(snapshot.lastSync().map(at -> I18n.t("Última atualização: {0}", Formats.clock(at))).orElse(""));

        JiraUser user = snapshot.user().orElse(new JiraUser("", ""));
        String name = user.displayName().isEmpty() ? I18n.t("Você") : user.displayName();
        userName.setText(name);
        userEmail.setText(user.email());
        avatar.setText(name.substring(0, 1).toUpperCase());

        last = snapshot;
        updateProjects(snapshot.projects());
        renderPage();
    }

    /** Tira da escolha os quadros que sumiram e atualiza os chips do painel. */
    private void updateProjects(List<String> projects) {
        Set<String> kept = BoardChips.stillPresent(project, projects);
        if (!kept.equals(project)) {
            // O escolhido não existe mais (outro quadro, outro Jira): mostra os que restaram, ou todos.
            applyProject(kept);
        }
        dashboard.setGroups(projects, project, last != null && last.byBoard());
    }

    private List<String> groupOf(String issueKey) {
        Snapshot snapshot = last;
        return snapshot == null ? List.of(Projects.of(issueKey)) : snapshot.groupsOf(issueKey);
    }

    private void selectProject(Set<String> selected) {
        if (selected.equals(project)) {
            return;
        }
        applyProject(selected);
        if (last != null) {
            dashboard.setGroups(last.projects(), project, last.byBoard());
        }
        renderPage();
    }

    private void applyProject(Set<String> selected) {
        project = Set.copyOf(selected);
        saveProject(project);
        history.setProject(project);
        worklog.setProject(project);
        comments.setProject(project);
    }

    private static Set<String> loadProject() {
        try {
            String saved = Preferences.userNodeForPackage(MainWindow.class).get("boards", "");
            return saved.isEmpty() ? Set.of() : Set.of(saved.split("\n"));
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private static void saveProject(Set<String> value) {
        try {
            Preferences.userNodeForPackage(MainWindow.class).put("boards", String.join("\n", new java.util.TreeSet<>(value)));
        } catch (RuntimeException e) {
            // Sem onde guardar: na próxima vez volta a mostrar todos.
        }
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
            case WORKLOG -> {
                worklog.reload();
                yield worklog.getView();
            }
            case COMMENTS -> {
                comments.reload();
                yield comments.getView();
            }
            case SETTINGS -> {
                settings.load();
                yield settings.getView();
            }
        });
        renderPage();
    }

    /** Endereço do Jira, para o link da task atual no Painel. */
    public void setJiraBaseUrl(String url) {
        dashboard.setJiraBaseUrl(url);
    }

    /** Mostra ou esconde a aba Comentários (Configurações > Comentários). */
    public void setCommentsEnabled(boolean enabled) {
        commentsEnabled = enabled;
        HBox item = navItems.get(Page.COMMENTS);
        if (item != null) {
            item.setVisible(enabled);
            item.setManaged(enabled);
        }
        if (!enabled && page == Page.COMMENTS) {
            show(Page.DASHBOARD);
        }
    }

    /** Só a página visível é atualizada a cada segundo. */
    private void renderPage() {
        if (last == null) {
            return;
        }
        Snapshot view = last.forGroups(project);
        if (commentsEnabled && page != Page.COMMENTS) {
            // O contador do menu lateral: as pendências de comentário contam mesmo com outra página aberta.
            comments.countPending(view);
        }
        switch (page) {
            case DASHBOARD -> dashboard.render(view);
            case TASKS -> tasks.render(view);
            case HISTORY -> history.render(view);
            case WORKLOG -> worklog.render(view);
            case COMMENTS -> comments.render(view);
            case SETTINGS -> {
                // Nada muda sozinho nas configurações.
            }
        }
    }

    private HBox buildTopBar() {
        ImageView logoImage = new ImageView(new Image(
                MainWindow.class.getResource("logo-full.png").toExternalForm(), 0, 120, true, true));
        logoImage.setFitHeight(46);
        logoImage.setPreserveRatio(true);
        logoImage.setSmooth(true);
        logoImage.getStyleClass().add("logo-image");
        HBox logo = new HBox(logoImage);
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

        HBox balance = new HBox();
        balance.setAlignment(Pos.CENTER_RIGHT);
        balance.setPrefWidth(250);

        HBox bar = new HBox(logo, status, balance);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private VBox buildSidebar() {
        VBox nav = new VBox(6,
                navItem(Page.DASHBOARD, Icons.HOME, I18n.t("Painel")),
                navItem(Page.TASKS, Icons.LIST, I18n.t("Tarefas")),
                navItem(Page.HISTORY, Icons.CLOCK, I18n.t("Histórico")),
                navItem(Page.WORKLOG, Icons.CLIPBOARD_CHECK, I18n.t("Apontamentos")),
                navItem(Page.COMMENTS, Icons.COMMENT, I18n.t("Comentários")),
                navItem(Page.SETTINGS, Icons.GEAR, I18n.t("Configurações")));
        navItems.get(Page.DASHBOARD).getStyleClass().add("nav-selected");
        setCommentsEnabled(false);

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
        Label label = target == Page.COMMENTS ? commentsNavLabel : new Label();
        label.setText(text);
        label.getStyleClass().add("nav-label");
        HBox item = new HBox(14, Icons.of(icon, 20, "icon-nav"), label);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(event -> show(target));
        navItems.put(target, item);
        return item;
    }
}

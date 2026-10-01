package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.CommentBook;
import com.chronos.tracker.tracking.CommentBook.Item;
import com.chronos.tracker.tracking.CommentBook.Status;
import com.chronos.tracker.tracking.Projects;
import com.chronos.tracker.tracking.TaskComment;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Comentários das tasks, em três abas: as tasks das colunas monitoradas que faltam comentar, o template padrão e
 * o histórico do que já foi comentado (com edição).
 */
public final class CommentsPage {

    /** Leitura e envio dos comentários, fora da thread da UI quando fala com o Jira. */
    public interface Handler {
        List<Item> items(List<TaskView> live) throws Exception;

        List<TaskComment> history() throws Exception;

        String template();

        void saveTemplate(String markdown) throws Exception;

        /** O que o template automático fez por último, ou vazio se ainda não rodou. */
        String templateStatus();

        CompletableFuture<TaskComment> save(String issueKey, String summary, String markdown);
    }

    /** De quanto em quanto tempo relê do banco o que falta comentar. */
    private static final Duration RELOAD_EVERY = Duration.ofSeconds(5);

    private final Handler handler;
    private final VBox root;
    private final Label countLabel = new Label();
    private final Label pageError = new Label();
    private final VBox pendingList = new VBox(8);
    private final VBox historyList = new VBox(8);
    private final TextArea templateArea = new TextArea();
    private final Label templateFeedback = new Label();
    private final Label templateStatus = new Label();
    private Consumer<Long> onPendingCount = count -> { };

    private Snapshot last;
    private Instant loadedAt = Instant.EPOCH;
    private java.util.Set<String> project = java.util.Set.of();
    private java.util.function.Function<String, java.util.List<String>> groupOf = key -> java.util.List.of(Projects.of(key));
    /** Task com o editor aberto; enquanto isso a lista não é refeita, para não perder o que foi digitado. */
    private String editing;

    public CommentsPage(Handler handler) {
        this.handler = handler;

        Label title = new Label(I18n.t("Comentários"));
        title.getStyleClass().add("page-title");
        countLabel.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, countLabel);
        heading.setAlignment(Pos.BASELINE_LEFT);

        pageError.getStyleClass().add("form-error");
        pageError.setWrapText(true);
        pageError.setMaxWidth(Double.MAX_VALUE);
        showPageError(null);

        Label pendingHint = new Label(I18n.t("Tasks suas nas colunas monitoradas. Cada uma precisa de um comentário; \"Salvar no Jira\" publica o texto na task."));
        pendingHint.getStyleClass().add("muted");
        pendingHint.setWrapText(true);
        VBox pendingCard = new VBox(8, pendingList);
        pendingCard.getStyleClass().addAll("card", "list-card");
        VBox pendingTab = new VBox(14, pendingHint, pendingCard);
        pendingTab.getStyleClass().add("browser-tab-body");

        VBox historyCard = new VBox(8, historyList);
        historyCard.getStyleClass().addAll("card", "list-card");
        VBox historyTab = new VBox(14, historyCard);
        historyTab.getStyleClass().add("browser-tab-body");

        TabPane tabs = BrowserTabs.create();
        tabs.getTabs().addAll(
                BrowserTabs.tab(I18n.t("Pendências"), pendingTab),
                BrowserTabs.tab(I18n.t("Template"), templateTab()),
                BrowserTabs.tab(I18n.t("Histórico de comentários"), historyTab));
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, was, now) -> reload());

        root = new VBox(18, heading, pageError, tabs);
        root.getStyleClass().add("page");
    }

    private Node templateTab() {
        Label hint = new Label(I18n.t("Escreva em Markdown: # título, - lista, **negrito**, _itálico_, `código`. Emojis do Jira vão por extenso, como :light_bulb_on: ou :white_check_mark:. Com \"Habilitar template padrão\" ligado em Configurações, este texto vai sozinho para cada task sua numa coluna monitorada que ainda não tem comentário."));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        templateArea.getStyleClass().add("comment-editor");
        templateArea.setWrapText(true);
        templateArea.setPrefRowCount(14);
        templateArea.setPromptText(I18n.t(":light_bulb_on: **O que foi feito**\n- \n\n:white_check_mark: **Como testar**\n- "));
        Button save = new Button(I18n.t("Salvar template"));
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            try {
                handler.saveTemplate(templateArea.getText());
                feedback(templateFeedback, I18n.t("Template salvo."), false);
            } catch (Exception ex) {
                feedback(templateFeedback, I18n.t("Não foi possível salvar o template: {0}", ex.getMessage()), true);
            }
        });
        HBox actions = new HBox(12, save, templateFeedback);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(12, templateArea, actions);
        card.getStyleClass().add("card");
        templateStatus.getStyleClass().add("muted");
        templateStatus.setWrapText(true);
        VBox tab = new VBox(14, hint, templateStatus, card);
        tab.getStyleClass().add("browser-tab-body");
        return tab;
    }

    public Node getView() {
        return root;
    }

    /** Recebe quantas tasks faltam comentar a cada releitura (para o menu lateral). */
    public void setOnPendingCount(Consumer<Long> listener) {
        onPendingCount = listener;
    }

    public void setGrouping(java.util.function.Function<String, java.util.List<String>> groupOf) {
        this.groupOf = groupOf;
    }

    public void setProject(java.util.Set<String> project) {
        if (!this.project.equals(project)) {
            this.project = project;
            reload();
        }
    }

    private boolean inProject(String issueKey) {
        return Projects.matches(groupOf.apply(issueKey), project);
    }

    /** Relê tudo na próxima atualização (ao abrir a página). */
    public void reload() {
        loadedAt = Instant.EPOCH;
        templateArea.setText(handler.template());
        if (last != null) {
            render(last);
        }
    }

    public void render(Snapshot snapshot) {
        last = snapshot;
        String status = handler.templateStatus();
        templateStatus.setText(status);
        templateStatus.setVisible(!status.isEmpty());
        templateStatus.setManaged(!status.isEmpty());
        Instant now = Instant.now();
        if (editing != null || Duration.between(loadedAt, now).compareTo(RELOAD_EVERY) < 0) {
            return;
        }
        loadedAt = now;
        try {
            List<Item> items = handler.items(snapshot.tasks()).stream().filter(i -> inProject(i.key())).toList();
            List<TaskComment> history = handler.history().stream().filter(c -> inProject(c.issueKey())).toList();
            showPageError(null);
            showPending(items);
            showHistory(history);
        } catch (Exception e) {
            showPageError(I18n.t("Não foi possível ler os comentários: {0}", e.getMessage()));
        }
    }

    /** Quantas tasks faltam comentar, para o menu lateral; lê do banco no máximo a cada poucos segundos. */
    public void countPending(Snapshot snapshot) {
        last = snapshot;
        render(snapshot);
    }

    private void showPending(List<Item> items) {
        long pending = items.stream().filter(item -> item.status() == Status.PENDING).count();
        countLabel.setText(pending == 0 ? I18n.t("nada pendente")
                : pending == 1 ? I18n.t("1 task falta comentar") : I18n.t("{0} tasks faltam comentar", pending));
        onPendingCount.accept(pending);
        pendingList.getChildren().clear();
        if (items.isEmpty()) {
            pendingList.getChildren().add(muted(I18n.t("Nenhuma task sua nas colunas monitoradas.")));
            return;
        }
        items.forEach(item -> pendingList.getChildren().add(row(item.key(), item.summary(), item.status(),
                item.comment().map(TaskComment::body).orElse(""), null)));
    }

    private void showHistory(List<TaskComment> comments) {
        historyList.getChildren().clear();
        if (comments.isEmpty()) {
            historyList.getChildren().add(muted(I18n.t("Nenhum comentário feito pelo Chronos ainda.")));
            return;
        }
        comments.forEach(comment -> historyList.getChildren().add(row(comment.issueKey(), comment.summary(),
                comment.kind() == TaskComment.Kind.TEMPLATE ? Status.TEMPLATE : Status.COMMENTED, comment.body(),
                comment.updatedAt())));
    }

    private Node row(String key, String summary, Status status, String body, Instant updatedAt) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("task-key");
        Label summaryLabel = new Label(summary.isEmpty() ? "—" : summary);
        summaryLabel.getStyleClass().add("task-summary");
        VBox text = new VBox(2, keyLabel, summaryLabel);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label badge = new Label(switch (status) {
            case PENDING -> I18n.t("Falta comentar");
            case TEMPLATE -> I18n.t("Template adicionado");
            case COMMENTED -> I18n.t("Comentário adicionado");
        });
        badge.getStyleClass().addAll("badge", switch (status) {
            case PENDING -> "badge-neutral";
            case TEMPLATE -> "badge-progress";
            case COMMENTED -> "badge-done";
        });
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox badgeBox = new HBox(badge);
        badgeBox.setAlignment(Pos.CENTER_LEFT);
        badgeBox.setMinWidth(190);

        HBox line = new HBox(18, text);
        if (updatedAt != null) {
            Label when = new Label(Formats.dateTime(updatedAt));
            when.getStyleClass().add("muted");
            when.setMinWidth(Region.USE_PREF_SIZE);
            line.getChildren().add(when);
        }
        Button edit = new Button(status == Status.PENDING ? I18n.t("Comentar") : I18n.t("Editar"));
        edit.getStyleClass().add(status == Status.PENDING ? "primary-button" : "secondary-button");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        line.getChildren().addAll(badgeBox, edit);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(10, line);
        row.getStyleClass().add("task-row");
        edit.setOnAction(e -> {
            if (editing != null) {
                return;
            }
            editing = key;
            edit.setDisable(true);
            String start = body.isEmpty() ? handler.template() : body;
            row.getChildren().add(editor(key, summary, start, () -> {
                editing = null;
                loadedAt = Instant.EPOCH;
                if (last != null) {
                    render(last);
                }
            }));
        });
        return row;
    }

    private Node editor(String key, String summary, String start, Runnable close) {
        TextArea area = new TextArea(start);
        area.getStyleClass().add("comment-editor");
        area.setWrapText(true);
        area.setPrefRowCount(10);
        Label feedback = new Label();
        Button save = new Button(I18n.t("Salvar no Jira"));
        save.getStyleClass().add("primary-button");
        Button cancel = new Button(I18n.t("Cancelar"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> close.run());
        save.setOnAction(e -> {
            if (area.getText().isBlank()) {
                feedback(feedback, I18n.t("Escreva o comentário antes de salvar."), true);
                return;
            }
            save.setDisable(true);
            cancel.setDisable(true);
            save.setText(I18n.t("Salvando..."));
            handler.save(key, summary, area.getText()).whenComplete((saved, error) -> Platform.runLater(() -> {
                if (error == null) {
                    close.run();
                    return;
                }
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                save.setDisable(false);
                cancel.setDisable(false);
                save.setText(I18n.t("Salvar no Jira"));
                feedback(feedback, I18n.t("O Jira não aceitou o comentário: {0}", cause.getMessage()), true);
            }));
        });
        HBox actions = new HBox(12, save, cancel, feedback);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, area, actions);
        Platform.runLater(area::requestFocus);
        return box;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static void feedback(Label label, String text, boolean error) {
        label.setText(text);
        label.setWrapText(true);
        label.getStyleClass().setAll(error ? "feedback-error" : "feedback-ok");
    }

    private void showPageError(String message) {
        pageError.setText(message == null ? "" : message);
        pageError.setVisible(message != null);
        pageError.setManaged(message != null);
    }
}

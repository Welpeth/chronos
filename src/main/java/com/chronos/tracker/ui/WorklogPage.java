package com.chronos.tracker.ui;

import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import com.chronos.tracker.tracking.WorklogBook;
import com.chronos.tracker.tracking.WorklogBook.Item;
import com.chronos.tracker.tracking.WorklogBook.Status;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Apontamentos: cada task com o tempo total, quanto já foi lançado no Jira e o botão "Apontar" para lançar
 * o que falta no controle de tempo dela.
 */
public final class WorklogPage {

    /** O que a página precisa do resto do app. */
    public interface Handler {
        /** Tasks com tempo gravado, juntando as do momento. Lê o banco; é rápido. */
        List<Item> items(List<TaskView> live) throws Exception;

        /** Lança no Jira o que falta apontar, fora da thread da UI. */
        CompletableFuture<Duration> log(String issueKey);

        /** Se o quadro tem o campo "Controle de tempo" (vazio se não deu para saber), fora da thread da UI. */
        CompletableFuture<Optional<Boolean>> timeTrackingAvailable(List<TaskView> live);
    }

    /** De quanto em quanto tempo a lista relê o banco enquanto está na tela. */
    private static final Duration RELOAD_EVERY = Duration.ofSeconds(5);
    /** De quanto em quanto tempo confere de novo se o quadro tem o campo de controle de tempo. */
    private static final Duration CHECK_FIELD_EVERY = Duration.ofMinutes(2);

    private final Handler handler;
    private final ScrollPane root;
    private final VBox list = new VBox(0);
    private final Label countLabel = new Label();
    private final Label pendingTotal = new Label();
    private final Label pageError = new Label();
    private final HBox fieldWarning;
    private Instant fieldCheckedAt = Instant.EPOCH;
    private boolean checkingField;
    private final Set<String> logging = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private Snapshot last;
    private Instant loadedAt = Instant.EPOCH;

    public WorklogPage(Handler handler) {
        this.handler = handler;

        Label title = new Label("Apontamentos");
        title.getStyleClass().add("page-title");
        countLabel.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, countLabel);
        heading.setAlignment(Pos.BASELINE_LEFT);

        Label hint = new Label("Quando uma task sai da coluna em andamento, ela pausa e o tempo contado aparece "
                + "aqui. \"Apontar\" lança esse tempo no controle de tempo da task no Jira.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        Label pendingCaption = new Label("Falta apontar");
        pendingCaption.getStyleClass().add("muted");
        pendingTotal.getStyleClass().add("worklog-pending-total");
        VBox summary = new VBox(4, pendingCaption, pendingTotal);
        summary.getStyleClass().addAll("card", "worklog-summary");

        Label warningText = new Label("O seu quadro precisa ter o campo \"Controle de tempo\" dentro das tarefas "
                + "para o Chronos apontar as horas. No Jira, adicione o campo Controle de tempo (Time tracking) aos "
                + "tipos de task do projeto.");
        warningText.setWrapText(true);
        warningText.getStyleClass().add("time-tracking-warning-text");
        HBox.setHgrow(warningText, Priority.ALWAYS);
        fieldWarning = new HBox(12, Icons.of(Icons.INFO, 22, "icon-warning"), warningText);
        fieldWarning.setAlignment(Pos.CENTER_LEFT);
        fieldWarning.getStyleClass().add("time-tracking-warning");
        showFieldWarning(false);

        pageError.getStyleClass().add("form-error");
        pageError.setWrapText(true);
        pageError.setMaxWidth(Double.MAX_VALUE);
        showPageError(null);

        VBox card = new VBox(8, list);
        card.getStyleClass().addAll("card", "list-card");

        VBox page = new VBox(18, fieldWarning, heading, hint, summary, pageError, card);
        page.getStyleClass().add("page");

        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");
    }

    public Node getView() {
        return root;
    }

    /** Página aberta: relê o banco na hora. */
    public void reload() {
        loadedAt = Instant.EPOCH;
        if (last != null) {
            render(last);
        }
    }

    public void render(Snapshot snapshot) {
        last = snapshot;
        Instant now = Instant.now();
        if (Duration.between(loadedAt, now).compareTo(RELOAD_EVERY) < 0) {
            return;
        }
        loadedAt = now;
        checkField(snapshot, now);
        List<Item> items;
        try {
            items = handler.items(snapshot.tasks());
            showPageError(null);
        } catch (Exception e) {
            showPageError("Não foi possível ler o histórico: " + e.getMessage());
            return;
        }
        show(items);
    }

    private void show(List<Item> items) {
        long pendingCount = items.stream().filter(item -> item.status() == Status.PENDING).count();
        Duration pending = items.stream()
                .filter(item -> item.status() == Status.PENDING)
                .map(Item::pending)
                .reduce(Duration.ZERO, Duration::plus);
        countLabel.setText(pendingCount == 0 ? "tudo apontado"
                : pendingCount == 1 ? "1 task falta apontar" : pendingCount + " tasks faltam apontar");
        pendingTotal.setText(Formats.hoursMinutes(pending));

        list.getChildren().clear();
        if (items.isEmpty()) {
            Label empty = new Label("Nenhum tempo contado ainda.");
            empty.getStyleClass().add("muted");
            list.getChildren().add(empty);
            return;
        }
        items.forEach(item -> list.getChildren().add(row(item)));
    }

    private Node row(Item item) {
        Label key = new Label(item.key());
        key.getStyleClass().add("task-key");
        Label summary = new Label(item.summary().isEmpty() ? "Task fora do Jira" : item.summary());
        summary.getStyleClass().add("task-summary");
        VBox text = new VBox(2, key, summary);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        VBox total = column("Tempo total", Formats.hoursMinutes(item.total()));
        VBox logged = column("Apontado", Formats.hoursMinutes(item.logged()));

        Label badge = new Label(switch (item.status()) {
            case PENDING -> "Falta apontar " + Formats.hoursMinutes(item.pending());
            case COUNTING -> "Contando";
            case LOGGED -> "Apontado";
        });
        badge.getStyleClass().addAll("badge", switch (item.status()) {
            case PENDING -> "badge-neutral";
            case COUNTING -> "badge-progress";
            case LOGGED -> "badge-done";
        });
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox badgeBox = new HBox(badge);
        badgeBox.setAlignment(Pos.CENTER_LEFT);
        badgeBox.setMinWidth(170);

        Node action;
        if (item.status() == Status.PENDING) {
            Button apply = new Button(logging.contains(item.key()) ? "Apontando..." : "Apontar");
            apply.getStyleClass().addAll("primary-button", "worklog-button");
            apply.setDisable(logging.contains(item.key()));
            apply.setOnAction(e -> log(item.key()));
            action = apply;
        } else {
            action = new Region();
        }
        HBox actionBox = new HBox(action);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        actionBox.setMinWidth(110);

        HBox line = new HBox(18, text, total, logged, badgeBox, actionBox);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(6, line);
        row.getStyleClass().add("task-row");
        String error = errors.get(item.key());
        if (error != null) {
            Label message = new Label(error);
            message.getStyleClass().add("feedback-error");
            message.setWrapText(true);
            row.getChildren().add(message);
        }
        return row;
    }

    private static VBox column(String caption, String value) {
        Label label = new Label(caption);
        label.getStyleClass().add("worklog-caption");
        Label amount = new Label(value);
        amount.getStyleClass().add("task-time");
        VBox box = new VBox(2, label, amount);
        box.setMinWidth(90);
        return box;
    }

    private void log(String issueKey) {
        logging.add(issueKey);
        errors.remove(issueKey);
        reload();
        handler.log(issueKey).whenComplete((spent, error) -> Platform.runLater(() -> {
            logging.remove(issueKey);
            if (error != null) {
                errors.put(issueKey, "Não foi possível apontar: " + rootMessage(error));
            }
            reload();
        }));
    }

    /** Pergunta ao Jira, fora da thread da UI, se o quadro tem o campo de controle de tempo. */
    private void checkField(Snapshot snapshot, Instant now) {
        if (checkingField || snapshot.tasks().isEmpty()
                || Duration.between(fieldCheckedAt, now).compareTo(CHECK_FIELD_EVERY) < 0) {
            return;
        }
        checkingField = true;
        fieldCheckedAt = now;
        handler.timeTrackingAvailable(snapshot.tasks()).whenComplete((available, error) -> Platform.runLater(() -> {
            checkingField = false;
            // Sem resposta (Jira fora, sem permissão) não afirma nada: mantém o que já estava.
            if (error == null && available.isPresent()) {
                showFieldWarning(!available.get());
            }
        }));
    }

    private void showFieldWarning(boolean show) {
        fieldWarning.setVisible(show);
        fieldWarning.setManaged(show);
    }

    private void showPageError(String message) {
        pageError.setText(message == null ? "" : message);
        pageError.setVisible(message != null);
        pageError.setManaged(message != null);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}

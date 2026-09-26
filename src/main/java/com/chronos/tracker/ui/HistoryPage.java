package com.chronos.tracker.ui;

import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TimeEntry;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cada intervalo em que uma task contou tempo, do mais recente para o mais antigo, com o total por task.
 */
public final class HistoryPage {

    private final ScrollPane root;
    private final GridPane totals = new GridPane();
    private final GridPane intervals = new GridPane();
    private final Label subtitle = new Label();

    public HistoryPage() {
        Label title = new Label("Histórico");
        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, subtitle);
        heading.setAlignment(Pos.BASELINE_LEFT);

        Label note = new Label("O histórico fica gravado no arquivo SQLite do app (CHRONOS_DB_PATH, padrão chronos.db) "
                + "e volta ao abrir o Chronos. Intervalos em andamento são gravados a cada 30 segundos.");
        note.getStyleClass().add("notice");
        note.setWrapText(true);

        setupColumns(totals, 60, 40);
        VBox totalsCard = card("Tempo por task", totals);
        setupColumns(intervals, 46, 18, 18, 18);
        VBox intervalsCard = card("Intervalos", intervals);

        HBox.setHgrow(intervalsCard, Priority.ALWAYS);
        totalsCard.setPrefWidth(380);
        totalsCard.setMinWidth(320);
        HBox columns = new HBox(18, intervalsCard, totalsCard);

        VBox page = new VBox(18, heading, note, columns);
        page.getStyleClass().add("page");

        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");
    }

    public Node getView() {
        return root;
    }

    public void render(Snapshot snapshot) {
        Map<String, TaskView> tasks = snapshot.tasks().stream()
                .collect(Collectors.toMap(TaskView::key, Function.identity(), (a, b) -> a));
        subtitle.setText(snapshot.history().size()
                + (snapshot.history().size() == 1 ? " intervalo" : " intervalos"));

        renderIntervals(snapshot, tasks);
        renderTotals(snapshot);
    }

    private void renderIntervals(Snapshot snapshot, Map<String, TaskView> tasks) {
        intervals.getChildren().clear();
        header(intervals, "Task", "Início", "Fim", "Duração");
        if (snapshot.history().isEmpty()) {
            intervals.add(muted("Nenhum tempo contado ainda."), 0, 1, 4, 1);
            return;
        }
        int row = 1;
        for (TimeEntry entry : snapshot.history()) {
            TaskView task = tasks.get(entry.issueKey());
            boolean open = task != null && task.running()
                    && task.runningSince().map(entry.startedAt()::equals).orElse(false);

            Label key = new Label(entry.issueKey());
            key.getStyleClass().add(open ? "task-key-running" : "task-key");
            Label summary = muted(task == null ? "" : task.summary());
            VBox taskCell = new VBox(2, key, summary);
            taskCell.setMinWidth(0);

            intervals.add(taskCell, 0, row);
            intervals.add(cell(Formats.clock(entry.startedAt())), 1, row);
            intervals.add(open ? badge("agora") : cell(Formats.clock(entry.endedAt())), 2, row);
            intervals.add(cell(Formats.hms(entry.activeTime())), 3, row);
            row++;
        }
    }

    private void renderTotals(Snapshot snapshot) {
        totals.getChildren().clear();
        header(totals, "Task", "Total");
        Map<String, Duration> byTask = snapshot.history().stream()
                .collect(Collectors.groupingBy(TimeEntry::issueKey,
                        Collectors.reducing(Duration.ZERO, TimeEntry::activeTime, Duration::plus)));
        if (byTask.isEmpty()) {
            totals.add(muted("—"), 0, 1, 2, 1);
            return;
        }
        int row = 1;
        for (Map.Entry<String, Duration> entry : byTask.entrySet().stream()
                .sorted(Map.Entry.<String, Duration>comparingByValue().reversed())
                .toList()) {
            totals.add(cell(entry.getKey()), 0, row);
            totals.add(cell(Formats.hms(entry.getValue())), 1, row);
            row++;
        }
    }

    private static void setupColumns(GridPane grid, double... percents) {
        grid.getStyleClass().add("history-grid");
        for (double percent : percents) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(percent);
            column.setHalignment(HPos.LEFT);
            grid.getColumnConstraints().add(column);
        }
    }

    private static void header(GridPane grid, String... titles) {
        for (int i = 0; i < titles.length; i++) {
            Label label = new Label(titles[i]);
            label.getStyleClass().add("history-header");
            grid.add(label, i, 0);
        }
    }

    private static VBox card(String title, GridPane content) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        VBox card = new VBox(16, label, content);
        card.getStyleClass().addAll("card", "list-card");
        return card;
    }

    private static Label cell(String text) {
        return new Label(text);
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label badge(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", "badge-progress");
        return label;
    }
}

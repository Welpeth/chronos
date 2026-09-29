package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.Intervals;
import com.chronos.tracker.tracking.ManualEntry;
import com.chronos.tracker.tracking.Projects;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TimeEntry;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tempo contado por dia, lido do histórico gravado, com busca de uma task em todas as datas.
 *
 * <p>O dia de hoje é montado a partir do estado ao vivo (inclui intervalos ainda abertos); outros dias e
 * a busca vêm do banco e só são relidos quando a data ou o texto mudam.
 */
public final class HistoryPage {

    private static final int SEARCH_LIMIT = 500;

    private final HistoryStore store;
    private final ScrollPane root;
    private final GridPane totals = new GridPane();
    private final GridPane intervals = new GridPane();
    private final Label subtitle = new Label();
    private final Label intervalsTitle = new Label();
    private final Label totalsTitle = new Label();
    private final Label error = new Label();
    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final TextField search = new TextField();
    private Set<LocalDate> daysWithEntries = Set.of();
    private Snapshot last;
    private boolean stale = true;
    private String project = Projects.ALL;
    /** Grupo de cada task no seletor do topo (quadro ou projeto). */
    private java.util.function.Function<String, String> groupOf = Projects::of;

    /** Uma linha da tabela: um intervalo contado ou uma inserção manual (sem início e fim). */
    private record Row(String key, String summary, LocalDate day, Instant start, Instant end, Duration duration,
                       boolean open, boolean manual, String note) {

        static Row interval(TimeEntry entry, String summary, boolean open) {
            return new Row(entry.issueKey(), summary, HistoryPage.day(entry), entry.startedAt(), entry.endedAt(),
                    entry.activeTime(), open, false, "");
        }

        static Row manual(ManualEntry entry) {
            return new Row(entry.issueKey(), entry.summary(), entry.day(), entry.createdAt(), null,
                    entry.duration(), false, true, entry.note());
        }
    }

    /** Mais recentes primeiro: por dia e, dentro do dia, manuais antes dos intervalos. */
    private static final Comparator<Row> NEWEST_FIRST = Comparator.comparing(Row::day)
            .thenComparing(Row::manual)
            .thenComparing(Row::start)
            .reversed();

    public HistoryPage(HistoryStore store) {
        this.store = store;

        Label title = new Label(I18n.t("Histórico"));
        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, subtitle);
        heading.setAlignment(Pos.BASELINE_LEFT);

        error.getStyleClass().add("notice");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        setupDatePicker();
        Button previous = navButton("‹", I18n.t("Dia anterior"), () -> datePicker.setValue(selectedDay().minusDays(1)));
        Button next = navButton("›", I18n.t("Próximo dia"), () -> datePicker.setValue(selectedDay().plusDays(1)));
        Button today = new Button(I18n.t("Hoje"));
        today.getStyleClass().add("filter-chip");
        today.setOnAction(e -> {
            search.clear();
            datePicker.setValue(LocalDate.now());
        });

        search.setPromptText(I18n.t("Buscar task em todas as datas"));
        search.setPrefWidth(320);
        search.getStyleClass().add("search");
        search.textProperty().addListener((obs, before, now) -> invalidate());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, previous, datePicker, next, today, spacer, search);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        totalsTitle.getStyleClass().add("card-title");
        VBox totalsCard = card(totalsTitle, totals);
        intervalsTitle.getStyleClass().add("card-title");
        VBox intervalsCard = card(intervalsTitle, intervals);

        HBox.setHgrow(intervalsCard, Priority.ALWAYS);
        totalsCard.setPrefWidth(380);
        totalsCard.setMinWidth(320);
        HBox columns = new HBox(18, intervalsCard, totalsCard);

        VBox page = new VBox(18, heading, toolbar, error, columns);
        page.getStyleClass().add("page");

        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");
    }

    public Node getView() {
        return root;
    }

    /** Como saber o grupo (quadro ou projeto) de uma task. */
    public void setGrouping(java.util.function.Function<String, String> groupOf) {
        this.groupOf = groupOf;
    }

    private boolean inProject(String issueKey) {
        return project.isEmpty() || groupOf.apply(issueKey).equalsIgnoreCase(project);
    }

    /** Mostra só o quadro ou projeto escolhido; {@link Projects#ALL} mostra todos. */
    public void setProject(String project) {
        if (!this.project.equals(project)) {
            this.project = project;
            invalidate();
        }
    }

    public void render(Snapshot snapshot) {
        last = snapshot;
        if (isSearching()) {
            if (stale) {
                showSearch();
            }
        } else if (selectedDay().equals(LocalDate.now())) {
            showToday(snapshot);
        } else if (stale) {
            showStoredDay(selectedDay());
        }
        stale = false;
    }

    private void invalidate() {
        stale = true;
        if (last != null) {
            render(last);
        }
    }

    private void showToday(Snapshot snapshot) {
        Map<String, TaskView> tasks = snapshot.tasks().stream()
                .collect(Collectors.toMap(TaskView::key, Function.identity(), (a, b) -> a));
        List<Row> rows = new ArrayList<>();
        for (TimeEntry entry : snapshot.history()) {
            TaskView task = tasks.get(entry.issueKey());
            boolean open = task != null && task.running()
                    && task.runningSince().map(entry.startedAt()::equals).orElse(false);
            rows.add(Row.interval(entry, task == null ? "" : task.summary(), open));
        }
        snapshot.manualEntries().forEach(entry -> rows.add(Row.manual(entry)));
        rows.sort(NEWEST_FIRST);
        clearError();
        showDay(LocalDate.now(), rows, snapshot.activeToday(), snapshot.manualToday());
    }

    private void showStoredDay(LocalDate day) {
        try {
            List<HistoryStore.StoredEntry> stored = store.entriesOn(day).stream()
                    .filter(s -> inProject(s.entry().issueKey())).toList();
            List<ManualEntry> manual = store.manualOn(day).stream()
                    .filter(m -> inProject(m.issueKey())).toList();
            List<Row> rows = new ArrayList<>();
            stored.forEach(s -> rows.add(Row.interval(s.entry(), s.summary(), false)));
            manual.forEach(m -> rows.add(Row.manual(m)));
            rows.sort(NEWEST_FIRST);
            clearError();
            showDay(day, rows, Intervals.union(stored.stream().map(HistoryStore.StoredEntry::entry).toList()),
                    manual.stream().map(ManualEntry::duration).reduce(Duration.ZERO, Duration::plus));
        } catch (HistoryStore.HistoryException e) {
            showError(e);
        }
    }

    private void showDay(LocalDate day, List<Row> rows, Duration active, Duration manual) {
        String label = day.equals(LocalDate.now()) ? I18n.t("Hoje") : Formats.date(day);
        String records = records(rows.size());
        subtitle.setText(manual.isZero()
                ? I18n.t("{0} · {1} · {2} de tempo ativo", label, records, Formats.hoursMinutes(active))
                : I18n.t("{0} · {1} · {2} de tempo ativo + {3} manual", label, records,
                        Formats.hoursMinutes(active), Formats.hoursMinutes(manual)));
        intervalsTitle.setText(I18n.t("Registros"));
        totalsTitle.setText(I18n.t("Tempo por task no dia"));
        renderIntervals(rows, false, I18n.t("Nenhum tempo contado neste dia."));
        renderTotals(rows, false);
    }

    private void showSearch() {
        String text = search.getText().strip();
        try {
            List<Row> rows = new ArrayList<>();
            store.search(text, SEARCH_LIMIT).stream()
                    .filter(s -> inProject(s.entry().issueKey()))
                    .forEach(s -> rows.add(Row.interval(s.entry(), s.summary(), false)));
            store.searchManual(text, SEARCH_LIMIT).stream()
                    .filter(m -> inProject(m.issueKey()))
                    .forEach(m -> rows.add(Row.manual(m)));
            rows.sort(NEWEST_FIRST);
            clearError();
            long days = rows.stream().map(Row::day).distinct().count();
            // O texto buscado entra por último, para um {n} digitado nele não ser trocado.
            String dayCount = days == 1 ? I18n.t("{0} dia", days) : I18n.t("{0} dias", days);
            subtitle.setText(rows.size() >= SEARCH_LIMIT
                    ? I18n.t("{0} com \"{2}\" em {1} (mostrando os mais recentes)", records(rows.size()), dayCount, text)
                    : I18n.t("{0} com \"{2}\" em {1}", records(rows.size()), dayCount, text));
            intervalsTitle.setText(I18n.t("Resultados da busca"));
            totalsTitle.setText(I18n.t("Tempo por task na busca"));
            renderIntervals(rows, true, I18n.t("Nada gravado com esse texto."));
            renderTotals(rows, true);
        } catch (HistoryStore.HistoryException e) {
            showError(e);
        }
    }

    private void renderIntervals(List<Row> rows, boolean withDate, String emptyText) {
        intervals.getChildren().clear();
        intervals.getColumnConstraints().clear();
        if (withDate) {
            setupColumns(intervals, 18, 40, 14, 14, 14);
            header(intervals, I18n.t("Data"), I18n.t("Task"), I18n.t("Início"), I18n.t("Fim"), I18n.t("Duração"));
        } else {
            setupColumns(intervals, 46, 18, 18, 18);
            header(intervals, I18n.t("Task"), I18n.t("Início"), I18n.t("Fim"), I18n.t("Duração"));
        }
        int columns = withDate ? 5 : 4;
        if (rows.isEmpty()) {
            intervals.add(muted(emptyText), 0, 1, columns, 1);
            return;
        }
        int row = 1;
        for (Row entry : rows) {
            int column = 0;
            if (withDate) {
                LocalDate day = entry.day();
                Button link = new Button(Formats.shortDate(day));
                link.getStyleClass().add("date-link");
                link.setOnAction(e -> {
                    search.clear();
                    datePicker.setValue(day);
                });
                intervals.add(link, column++, row);
            }
            Label key = new Label(entry.key());
            key.getStyleClass().add(entry.open() ? "task-key-running" : "task-key");
            String detail = entry.note().isEmpty() ? entry.summary()
                    : entry.summary().isEmpty() ? entry.note() : entry.summary() + " · " + entry.note();
            VBox taskCell = new VBox(2, key, muted(detail));
            taskCell.setMinWidth(0);

            intervals.add(taskCell, column++, row);
            if (entry.manual()) {
                intervals.add(badge(I18n.t("manual"), "badge-manual"), column, row, 2, 1);
                column += 2;
            } else {
                intervals.add(cell(Formats.clock(entry.start())), column++, row);
                intervals.add(entry.open() ? badge(I18n.t("agora"), "badge-progress") : cell(Formats.clock(entry.end())),
                        column++, row);
            }
            intervals.add(cell(Formats.hms(entry.duration())), column, row);
            row++;
        }
    }

    private void renderTotals(List<Row> rows, boolean withDays) {
        totals.getChildren().clear();
        totals.getColumnConstraints().clear();
        if (withDays) {
            setupColumns(totals, 50, 20, 30);
            header(totals, I18n.t("Task"), I18n.t("Dias"), I18n.t("Total"));
        } else {
            setupColumns(totals, 60, 40);
            header(totals, I18n.t("Task"), I18n.t("Total"));
        }
        if (rows.isEmpty()) {
            totals.add(muted("—"), 0, 1, withDays ? 3 : 2, 1);
            return;
        }
        Map<String, Duration> byTask = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> daysByTask = new LinkedHashMap<>();
        for (Row entry : rows) {
            byTask.merge(entry.key(), entry.duration(), Duration::plus);
            daysByTask.computeIfAbsent(entry.key(), k -> new TreeSet<>()).add(entry.day());
        }
        int row = 1;
        for (Map.Entry<String, Duration> entry : byTask.entrySet().stream()
                .sorted(Map.Entry.<String, Duration>comparingByValue().reversed())
                .toList()) {
            int column = 0;
            totals.add(cell(entry.getKey()), column++, row);
            if (withDays) {
                totals.add(cell(String.valueOf(daysByTask.get(entry.getKey()).size())), column++, row);
            }
            totals.add(cell(Formats.hms(entry.getValue())), column, row);
            row++;
        }
    }

    private void setupDatePicker() {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        datePicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : format.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                try {
                    return text == null || text.isBlank() ? null : LocalDate.parse(text.strip(), format);
                } catch (DateTimeParseException e) {
                    return datePicker.getValue();
                }
            }
        });
        datePicker.setPrefWidth(150);
        datePicker.setEditable(true);
        // Dias com tempo gravado aparecem destacados no calendário.
        datePicker.setOnShowing(e -> {
            try {
                daysWithEntries = store.daysWithEntries();
            } catch (HistoryStore.HistoryException ex) {
                daysWithEntries = Set.of();
            }
        });
        datePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("day-with-history");
                if (!empty && item != null && daysWithEntries.contains(item)) {
                    getStyleClass().add("day-with-history");
                }
            }
        });
        datePicker.valueProperty().addListener((obs, before, now) -> {
            if (now == null) {
                datePicker.setValue(before == null ? LocalDate.now() : before);
                return;
            }
            if (!search.getText().isBlank()) {
                search.clear();
            }
            invalidate();
        });
    }

    private LocalDate selectedDay() {
        return datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue();
    }

    private boolean isSearching() {
        return search.getText() != null && !search.getText().isBlank();
    }

    private static LocalDate day(TimeEntry entry) {
        return LocalDate.ofInstant(entry.startedAt(), ZoneId.systemDefault());
    }

    private static String records(int n) {
        return n == 1 ? I18n.t("{0} registro", n) : I18n.t("{0} registros", n);
    }

    private void showError(HistoryStore.HistoryException e) {
        error.setText(e.getMessage());
        error.setVisible(true);
        error.setManaged(true);
    }

    private void clearError() {
        error.setVisible(false);
        error.setManaged(false);
    }

    private static Button navButton(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("filter-chip");
        button.setAccessibleText(tooltip);
        button.setOnAction(e -> action.run());
        return button;
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

    private static VBox card(Label title, GridPane content) {
        VBox card = new VBox(16, title, content);
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

    private static Label badge(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", style);
        return label;
    }
}

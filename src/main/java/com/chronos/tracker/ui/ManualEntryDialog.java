package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.TaskView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Janela para somar tempo à mão numa task. Se a inserção for recusada (por exemplo, o dia passaria de 8h),
 * o motivo aparece em vermelho e a janela continua aberta para corrigir.
 */
public final class ManualEntryDialog {

    private static final String SEPARATOR = " — ";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Tenta gravar a inserção; a mensagem da exceção é mostrada ao usuário. */
    @FunctionalInterface
    public interface Submitter {
        void submit(String issueKey, LocalDate date, Duration duration, String note) throws Exception;
    }

    private ManualEntryDialog() {
    }

    public static void show(Window owner, List<TaskView> allTasks, String preselectedKey, Submitter submitter) {
        List<TaskView> tasks = allTasks.stream().filter(TaskView::timeAllowed).toList();
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(I18n.t("Adicionar tempo manual"));

        // Campos
        ComboBox<String> task = new ComboBox<>();
        task.setEditable(true);
        task.setPromptText(I18n.t("Selecione uma task..."));
        task.setMaxWidth(Double.MAX_VALUE);
        task.getStyleClass().add("dialog-input");
        for (TaskView view : tasks) {
            task.getItems().add(view.summary().isEmpty() ? view.key() : view.key() + SEPARATOR + view.summary());
        }
        tasks.stream().filter(view -> view.key().equals(preselectedKey)).findFirst()
                .ifPresent(view -> task.getSelectionModel().select(tasks.indexOf(view)));

        DatePicker date = datePicker();

        Spinner<Integer> hours = spinner(0, 8, 1, 1);
        Spinner<Integer> minutes = spinner(0, 55, 0, 5);
        HBox duration = new HBox(12, hours, unit("h"), minutes, unit("min"));
        duration.setAlignment(Pos.CENTER_LEFT);

        TextArea note = new TextArea();
        note.setPromptText(I18n.t("Opcional: o que foi feito..."));
        note.setPrefRowCount(3);
        note.setWrapText(true);
        note.getStyleClass().add("dialog-input");

        // Aviso do limite e erro de validação
        Label info = new Label(I18n.t("O tempo manual soma com o tempo contado das tasks. O dia não pode passar de 8h."));
        info.setWrapText(true);
        info.getStyleClass().add("dialog-info-text");
        Region divider = new Region();
        divider.getStyleClass().add("dialog-info-divider");
        HBox infoBox = new HBox(16, Icons.of(Icons.INFO, 24, "icon-blue"), divider, info);
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.getStyleClass().add("dialog-info");
        HBox.setHgrow(info, Priority.ALWAYS);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.setVisible(false);
        error.setManaged(false);

        VBox body = new VBox(18,
                field(Icons.CLIPBOARD_CHECK, I18n.t("Task"), true, task),
                field(Icons.CALENDAR, I18n.t("Dia"), true, date),
                field(Icons.CLOCK, I18n.t("Tempo"), true, duration),
                field(Icons.NOTE, I18n.t("Nota"), false, note),
                infoBox,
                error);
        body.getStyleClass().add("dialog-body");

        // Cabeçalho azul com fechar
        Label title = new Label(I18n.t("Adicionar tempo manual"));
        title.getStyleClass().add("dialog-title");
        Button close = new Button();
        close.setGraphic(Icons.of(Icons.CLOSE, 22, "icon-white"));
        close.getStyleClass().add("dialog-close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(14, Icons.of(Icons.CLOCK, 30, "icon-white"), title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-header");
        makeDraggable(header, stage);

        // Rodapé
        Button add = new Button(I18n.t("Adicionar"));
        add.setGraphic(Icons.of(Icons.PLUS, 18, "icon-white"));
        add.getStyleClass().add("dialog-primary");
        add.setDefaultButton(true);
        Button cancel = new Button(I18n.t("Cancelar"));
        cancel.getStyleClass().add("dialog-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        HBox footer = new HBox(14, add, cancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("dialog-footer");

        add.setOnAction(event -> {
            try {
                commitSpinner(hours);
                commitSpinner(minutes);
                Duration chosen = Duration.ofHours(hours.getValue()).plusMinutes(minutes.getValue());
                if (chosen.isZero()) {
                    throw new IllegalArgumentException(I18n.t("Informe um tempo maior que zero."));
                }
                submitter.submit(keyOf(task), date.getValue(), chosen, note.getText());
                stage.close();
            } catch (Exception e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                stage.sizeToScene();
            }
        });

        VBox card = new VBox(header, body, footer);
        card.getStyleClass().add("manual-dialog");
        card.setPrefWidth(620);
        StackPane root = new StackPane(card);
        root.getStyleClass().add("dialog-shadow-area");

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.setOnShown(e -> {
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
        });
        stage.showAndWait();
    }

    /** Ícone à esquerda; rótulo (com * nos obrigatórios) e o campo à direita. */
    private static HBox field(String icon, String label, boolean required, Node input) {
        Label name = new Label(label);
        name.getStyleClass().add("dialog-label");
        HBox caption = new HBox(4, name);
        if (required) {
            Label star = new Label("*");
            star.getStyleClass().add("dialog-required");
            caption.getChildren().add(star);
        }
        VBox column = new VBox(8, caption, input);
        HBox.setHgrow(column, Priority.ALWAYS);
        StackPane iconBox = new StackPane(Icons.of(icon, 26, "icon-blue"));
        iconBox.setMinWidth(34);
        iconBox.setAlignment(Pos.TOP_CENTER);
        HBox row = new HBox(16, iconBox, column);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static DatePicker datePicker() {
        DatePicker date = new DatePicker(LocalDate.now());
        date.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate value) {
                return value == null ? "" : DATE.format(value);
            }

            @Override
            public LocalDate fromString(String text) {
                try {
                    return text == null || text.isBlank() ? null : LocalDate.parse(text.strip(), DATE);
                } catch (DateTimeParseException e) {
                    return date.getValue();
                }
            }
        });
        date.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isAfter(LocalDate.now()));
            }
        });
        date.setPrefWidth(340);
        date.getStyleClass().add("dialog-input");
        return date;
    }

    private static Spinner<Integer> spinner(int min, int max, int initial, int step) {
        Spinner<Integer> spinner = new Spinner<>(min, max, initial, step);
        spinner.setEditable(true);
        spinner.setPrefWidth(170);
        spinner.getValueFactory().valueProperty().addListener((obs, old, value) -> {
            if (value == null) {
                spinner.getValueFactory().setValue(old == null ? min : old);
            }
        });
        spinner.getStyleClass().add("dialog-input");
        return spinner;
    }

    private static Label unit(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-unit");
        return label;
    }

    static void makeDraggable(Node handle, Stage stage) {
        double[] offset = new double[2];
        handle.setOnMousePressed(e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
    }

    private static String keyOf(ComboBox<String> task) {
        String text = task.getEditor().getText();
        if (text == null || text.isBlank()) {
            text = task.getValue();
        }
        if (text == null) {
            return "";
        }
        int separator = text.indexOf(SEPARATOR);
        return (separator < 0 ? text : text.substring(0, separator)).strip();
    }

    /** Aceita o que foi digitado no campo do spinner, mesmo sem apertar Enter. Campo vazio vale 0. */
    private static void commitSpinner(Spinner<Integer> spinner) {
        Integer current = spinner.getValue();
        int value = typedValue(spinner.getEditor().getText(), current == null ? 0 : current);
        spinner.getValueFactory().setValue(value);
        spinner.getEditor().setText(String.valueOf(spinner.getValue()));
    }

    /** O número digitado; vazio vira 0 e texto que não é número mantém o valor anterior. */
    static int typedValue(String text, int previous) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return previous;
        }
    }
}

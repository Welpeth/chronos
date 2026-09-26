package com.chronos.tracker.ui;

import com.chronos.tracker.tracking.TaskView;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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

    /** Tenta gravar a inserção; a mensagem da exceção é mostrada ao usuário. */
    @FunctionalInterface
    public interface Submitter {
        void submit(String issueKey, LocalDate date, Duration duration, String note) throws Exception;
    }

    private ManualEntryDialog() {
    }

    public static void show(Window owner, List<TaskView> tasks, String preselectedKey, Submitter submitter) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Adicionar tempo manual");
        dialog.setHeaderText("Adicionar tempo manual");

        ComboBox<String> task = new ComboBox<>();
        task.setEditable(true);
        task.setPromptText("Chave da task, ex.: SCRUM-2");
        task.setMaxWidth(Double.MAX_VALUE);
        for (TaskView view : tasks) {
            task.getItems().add(view.summary().isEmpty() ? view.key() : view.key() + SEPARATOR + view.summary());
        }
        tasks.stream().filter(view -> view.key().equals(preselectedKey)).findFirst()
                .ifPresent(view -> task.getSelectionModel().select(tasks.indexOf(view)));

        DatePicker date = new DatePicker(LocalDate.now());
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        date.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate value) {
                return value == null ? "" : format.format(value);
            }

            @Override
            public LocalDate fromString(String text) {
                try {
                    return text == null || text.isBlank() ? null : LocalDate.parse(text.strip(), format);
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

        Spinner<Integer> hours = new Spinner<>(0, 8, 1);
        Spinner<Integer> minutes = new Spinner<>(0, 55, 0, 5);
        hours.setEditable(true);
        minutes.setEditable(true);
        hours.setPrefWidth(80);
        minutes.setPrefWidth(80);
        HBox duration = new HBox(8, hours, label("h"), minutes, label("min"));
        duration.setAlignment(Pos.CENTER_LEFT);

        TextField note = new TextField();
        note.setPromptText("Opcional: o que foi feito");

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setMaxWidth(420);
        error.setVisible(false);
        error.setManaged(false);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.addRow(0, label("Task"), task);
        form.addRow(1, label("Dia"), date);
        form.addRow(2, label("Tempo"), duration);
        form.addRow(3, label("Nota"), note);
        GridPane.setFillWidth(task, true);
        form.getColumnConstraints().addAll(new ColumnConstraints(60),
                new ColumnConstraints(340));

        Label hint = new Label("O tempo manual soma com o tempo contado das tasks. O dia não pode passar de 8h.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(420);

        VBox content = new VBox(14, form, hint, error);
        content.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().addAll(owner == null || owner.getScene() == null
                ? List.of() : owner.getScene().getStylesheets());

        ButtonType add = new ButtonType("Adicionar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));
        Button addButton = (Button) dialog.getDialogPane().lookupButton(add);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                commitSpinner(hours);
                commitSpinner(minutes);
                Duration chosen = Duration.ofHours(hours.getValue()).plusMinutes(minutes.getValue());
                submitter.submit(keyOf(task), date.getValue(), chosen, note.getText());
            } catch (Exception e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });

        dialog.showAndWait();
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

    /** Aceita o que foi digitado no campo do spinner, mesmo sem apertar Enter. */
    private static void commitSpinner(Spinner<Integer> spinner) {
        String text = spinner.getEditor().getText();
        try {
            int value = Integer.parseInt(text.strip());
            spinner.getValueFactory().setValue(value);
        } catch (NumberFormatException e) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }

    private static Node label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }
}

package com.chronos.tracker.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Configurações do app: tudo que fica no {@code .env} (Jira, tempos, banco) e abrir ao entrar no Windows.
 */
public final class SettingsPage {

    /** O que a página precisa do resto do app. */
    public interface Handler {
        /** Valores atuais do {@code .env}. */
        Map<String, String> currentValues();

        /** Grava e aplica; devolve a mensagem de sucesso ou lança com o motivo da recusa. */
        String save(Map<String, String> values, boolean startWithWindows) throws Exception;

        /** Testa a conexão com os valores digitados (ainda não gravados). */
        CompletableFuture<String> testConnection(Map<String, String> values);

        boolean startWithWindowsAvailable();

        boolean startWithWindowsEnabled();
    }

    private final Handler handler;
    private final ScrollPane root;
    private final Map<String, TextInputControl> fields = new LinkedHashMap<>();
    private final CheckBox startWithWindows = new CheckBox("Abrir o Chronos quando eu entrar no Windows");
    private final Label startHint = new Label();
    private final Label feedback = new Label();
    private final Label connection = new Label();

    public SettingsPage(Handler handler) {
        this.handler = handler;

        Label title = new Label("Configurações");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Gravadas no arquivo .env ao lado do app");
        subtitle.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, subtitle);
        heading.setAlignment(Pos.BASELINE_LEFT);

        GridPane jira = form();
        row(jira, 0, "JIRA_BASE_URL", "Endereço do Jira", "https://empresa.atlassian.net", new TextField());
        row(jira, 1, "JIRA_EMAIL", "E-mail", "seu-email@empresa.com", new TextField());
        row(jira, 2, "JIRA_API_TOKEN", "API token", "Gerado em id.atlassian.com", new PasswordField());
        row(jira, 3, "JIRA_PROJECT_KEY", "Projetos", "Chaves separadas por vírgula, ex.: SCRUM", new TextField());
        row(jira, 4, "JIRA_JQL", "JQL (opcional)", "Substitui a busca padrão pelos projetos", new TextField());
        row(jira, 5, "JIRA_IN_PROGRESS_STATUSES", "Colunas que contam tempo",
                "Padrão: Em andamento, Em progresso, In Progress", new TextField());
        Button test = new Button("Testar conexão");
        test.getStyleClass().add("secondary-button");
        test.setOnAction(e -> testConnection(test));
        connection.getStyleClass().add("muted");
        connection.setWrapText(true);
        HBox testRow = new HBox(12, test, connection);
        testRow.setAlignment(Pos.CENTER_LEFT);
        VBox jiraCard = card("Jira", jira, testRow);

        GridPane timing = form();
        row(timing, 0, "POLLING_INTERVAL_SECONDS", "Consultar o Jira a cada (segundos)", "5", new TextField());
        row(timing, 1, "POSSIBLY_IDLE_SECONDS", "Possivelmente ausente após (segundos)", "120", new TextField());
        row(timing, 2, "IDLE_THRESHOLD_SECONDS", "Pausar por inatividade após (segundos)", "300", new TextField());
        VBox timingCard = card("Tempo", timing);

        GridPane alerts = form();
        row(alerts, 0, "CHRONOS_ALERT_ISSUE_TYPES", "Tipos de task que geram aviso",
                "Ex.: Bug Cliente (separe vários por vírgula)", new TextField());
        Label alertsHint = new Label("Quando uma task desses tipos é criada nos projetos acima, de qualquer pessoa, "
                + "o Chronos mostra uma notificação do Windows e deixa uma bolinha vermelha no ícone até você abrir "
                + "a janela. Precisa dos projetos preenchidos (não vale só com JQL).");
        alertsHint.getStyleClass().add("muted");
        alertsHint.setWrapText(true);
        VBox alertsCard = card("Avisos de task", alerts, alertsHint);

        GridPane storage = form();
        row(storage, 0, "CHRONOS_DB_PATH", "Arquivo do histórico", "chronos.db (vale ao reabrir o app)",
                new TextField());
        VBox storageCard = card("Histórico", storage);

        startHint.getStyleClass().add("muted");
        startHint.setWrapText(true);
        VBox systemCard = card("Sistema", new VBox(8, startWithWindows, startHint));

        Button save = new Button("Salvar");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> save());
        Button revert = new Button("Desfazer alterações");
        revert.getStyleClass().add("secondary-button");
        revert.setOnAction(e -> load());
        feedback.setWrapText(true);
        HBox actions = new HBox(12, save, revert, feedback);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(18, heading, jiraCard, alertsCard, timingCard, storageCard, systemCard, actions);
        page.getStyleClass().add("page");
        page.setMaxWidth(900);

        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.getStyleClass().add("page-scroll");
        load();
    }

    public Node getView() {
        return root;
    }

    /** Relê o .env, descartando o que foi digitado e não salvo. */
    public void load() {
        Map<String, String> values = handler.currentValues();
        fields.forEach((key, field) -> field.setText(value(values, key)));
        boolean available = handler.startWithWindowsAvailable();
        startWithWindows.setSelected(handler.startWithWindowsEnabled());
        startWithWindows.setDisable(!available);
        startHint.setText(available
                ? "O Chronos abre minimizado quando você entra no Windows."
                : "Disponível no Windows, abrindo o Chronos pelo executável instalado.");
        feedback.setText("");
        feedback.getStyleClass().removeAll("feedback-ok", "feedback-error");
        connection.setText("");
    }

    private static String value(Map<String, String> values, String key) {
        if (key.equals("JIRA_PROJECT_KEY")) {
            String keys = values.getOrDefault("JIRA_PROJECT_KEYS", "");
            return keys.isBlank() ? values.getOrDefault(key, "") : keys;
        }
        return values.getOrDefault(key, "");
    }

    private Map<String, String> typedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, field) -> values.put(key, field.getText() == null ? "" : field.getText().strip()));
        return values;
    }

    private void save() {
        feedback.getStyleClass().removeAll("feedback-ok", "feedback-error");
        try {
            feedback.setText(handler.save(typedValues(), startWithWindows.isSelected()));
            feedback.getStyleClass().add("feedback-ok");
        } catch (Exception e) {
            feedback.setText(e.getMessage());
            feedback.getStyleClass().add("feedback-error");
        }
    }

    private void testConnection(Button button) {
        button.setDisable(true);
        connection.setText("Testando...");
        handler.testConnection(typedValues()).whenComplete((message, error) -> Platform.runLater(() -> {
            button.setDisable(false);
            connection.setText(error == null ? message : "Falhou: " + rootMessage(error));
        }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void row(GridPane grid, int index, String key, String label, String prompt, TextInputControl field) {
        Label name = new Label(label);
        name.getStyleClass().add("settings-label");
        name.setWrapText(true);
        field.setPromptText(prompt);
        field.getStyleClass().add("settings-input");
        GridPane.setHgrow(field, Priority.ALWAYS);
        grid.add(name, 0, index);
        grid.add(field, 1, index);
        fields.put(key, field);
    }

    private static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        ColumnConstraints labels = new ColumnConstraints(240);
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, inputs);
        return grid;
    }

    private static VBox card(String title, Node... content) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        VBox card = new VBox(16, label);
        card.getChildren().addAll(content);
        card.getStyleClass().addAll("card", "list-card");
        return card;
    }
}

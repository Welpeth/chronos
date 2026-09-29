package com.chronos.tracker.ui;

import com.chronos.tracker.config.TokenExpiry;
import com.chronos.tracker.update.Updater;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Configurações do app: tudo que fica no {@code .env} (Jira, tempos, banco) e abrir ao entrar no Windows.
 */
public final class SettingsPage {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

        /** Atualização do Chronos e cópias do histórico. */
        Maintenance maintenance();
    }

    private final Handler handler;
    private final VBox root;
    private final TabPane tabs = BrowserTabs.create();
    private final Map<String, TextInputControl> fields = new LinkedHashMap<>();
    /** Opções liga/desliga gravadas como true/false; todas começam ligadas. */
    private final Map<String, CheckBox> flags = new LinkedHashMap<>();
    /** Marcadas quando o .env não diz nada; as ausentes daqui começam ligadas. */
    private final java.util.Set<String> flagsOffByDefault = new java.util.HashSet<>();
    private final CheckBox startWithWindows = new CheckBox("Abrir o Chronos quando eu entrar no Windows");
    private final Label startHint = new Label();
    private final Label feedback = new Label();
    private final Label connection = new Label();
    private final DatePicker tokenExpires = new DatePicker();
    private final Label tokenExpiresStatus = new Label();
    private final Label updateStatus = new Label();
    private final Label restoreStatus = new Label();

    public SettingsPage(Handler handler) {
        this.handler = handler;

        Label title = new Label("Configurações");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Gravadas em " + com.chronos.tracker.config.AppPaths.envFile().toAbsolutePath());
        subtitle.getStyleClass().add("muted");
        HBox heading = new HBox(12, title, subtitle);
        heading.setAlignment(Pos.BASELINE_LEFT);

        GridPane jira = form();
        row(jira, 0, "JIRA_BASE_URL", "Endereço do Jira", "https://empresa.atlassian.net", new TextField());
        row(jira, 1, "JIRA_EMAIL", "E-mail", "seu-email@empresa.com", new TextField());
        row(jira, 2, "JIRA_API_TOKEN", "API token", "Gerado em id.atlassian.com", new PasswordField());
        jira.add(tokenExpiryRow(), 1, 3);
        row(jira, 4, "JIRA_PROJECT_KEY", "Projetos", "Chaves separadas por vírgula, ex.: SCRUM", new TextField());
        row(jira, 5, "JIRA_JQL", "JQL (opcional)", "Substitui a busca padrão pelos projetos", new TextField());
        Button test = new Button("Testar conexão");
        test.getStyleClass().add("secondary-button");
        test.setOnAction(e -> testConnection(test));
        connection.getStyleClass().add("muted");
        connection.setWrapText(true);
        HBox testRow = new HBox(12, test, connection);
        testRow.setAlignment(Pos.CENTER_LEFT);
        VBox jiraCard = card("Conexão com o Jira", jira, testRow);

        GridPane columns = form();
        row(columns, 0, "JIRA_IN_PROGRESS_STATUSES", "Colunas que contam tempo",
                "Ex.: Test (escreva como aparece no quadro do Jira)", new TextField());
        columns.add(flag("JIRA_USE_DEFAULT_STATUSES",
                "Contar também as colunas padrão (Em andamento, Em progresso, In Progress)"), 1, 1);
        columns.add(flag("CHRONOS_AUTO_START",
                "Começar a contar sozinho quando a task entrar numa dessas colunas"), 1, 2);
        Label columnsHint = new Label("Sem começar sozinho, o tempo só conta depois do play. Nos dois casos, a task "
                + "pausa quando sai dessas colunas.");
        columnsHint.getStyleClass().add("muted");
        columnsHint.setWrapText(true);
        columns.add(columnsHint, 1, 3);
        columns.add(flag("JIRA_WATCH_WHOLE_COLUMNS",
                "Mostrar todas as tasks dessas colunas, de qualquer responsável", false), 1, 4);
        Label wholeHint = new Label("Ex.: quem testa vê tudo que está na coluna Test. As tasks de outras pessoas "
                + "aparecem na lista, mas só contam tempo com o play.");
        wholeHint.getStyleClass().add("muted");
        wholeHint.setWrapText(true);
        columns.add(wholeHint, 1, 5);
        columns.add(flag("CHRONOS_ONLY_WORKING_COLUMNS",
                "Só aceitar tempo nas tasks que estão nessas colunas", false), 1, 6);
        Label onlyHint = new Label("Ligada, as tasks do Jira fora dessas colunas ficam sem play e sem tempo manual. "
                + "Desligada, qualquer task aceita tempo.");
        onlyHint.getStyleClass().add("muted");
        onlyHint.setWrapText(true);
        columns.add(onlyHint, 1, 7);
        VBox columnsCard = card("Colunas do quadro", columns);

        GridPane categories = form();
        row(categories, 0, "CHRONOS_PLAY_LABELS", "Tags ao dar play",
                "Ex.: em-teste (separe várias por vírgula)", new TextField());
        row(categories, 1, "CHRONOS_DONE_LABELS", "Tags ao terminar",
                "Ex.: testado (separe várias por vírgula)", new TextField());
        Label categoriesHint = new Label("Quando o tempo de uma task começa, o Chronos põe as tags do play nela, "
                + "no campo Labels do Jira. Quando a task sai das colunas monitoradas ou é finalizada, ele tira as "
                + "do play e põe as de terminar. Espaços viram hífen. O campo Labels precisa estar nas tasks do "
                + "projeto.");
        categoriesHint.getStyleClass().add("muted");
        categoriesHint.setWrapText(true);
        VBox categoriesCard = card("Categorias da validação", categories, categoriesHint);

        GridPane timing = form();
        row(timing, 0, "POLLING_INTERVAL_SECONDS", "Consultar o Jira a cada (segundos)", "5", new TextField());
        row(timing, 1, "POSSIBLY_IDLE_SECONDS", "Possivelmente ausente após (segundos)", "120", new TextField());
        row(timing, 2, "IDLE_THRESHOLD_SECONDS", "Pausar por inatividade após (segundos)", "300", new TextField());
        VBox timingCard = card("Tempo e inatividade", timing);

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
        Button restore = new Button("Restaurar base histórica");
        restore.getStyleClass().add("secondary-button");
        restore.setOnAction(e -> restoreHistory());
        restoreStatus.getStyleClass().add("muted");
        restoreStatus.setWrapText(true);
        Label restoreHint = new Label("Antes de cada atualização, o histórico é copiado para a pasta backup com o "
                + "nome da versão (ex.: chronos-0.2.0.db). Restaurar troca o histórico atual pela cópia escolhida e "
                + "reinicia o Chronos; o atual também fica guardado em backup.");
        restoreHint.getStyleClass().add("muted");
        restoreHint.setWrapText(true);
        HBox restoreRow = new HBox(12, restore, restoreStatus);
        restoreRow.setAlignment(Pos.CENTER_LEFT);
        VBox storageCard = card("Histórico", storage, restoreHint, restoreRow);

        startHint.getStyleClass().add("muted");
        startHint.setWrapText(true);
        Label version = new Label("Versão " + handler.maintenance().version());
        version.getStyleClass().add("settings-label");
        Button update = new Button("Procurar atualização");
        update.getStyleClass().add("secondary-button");
        update.setOnAction(e -> checkForUpdate(update));
        updateStatus.getStyleClass().add("muted");
        updateStatus.setWrapText(true);
        HBox updateRow = new HBox(12, version, update, updateStatus);
        updateRow.setAlignment(Pos.CENTER_LEFT);
        VBox systemCard = card("Sistema", new VBox(8, startWithWindows, startHint),
                flag("CHRONOS_DARK_MODE", "Modo escuro", false), updateRow);

        tabs.getTabs().addAll(
                BrowserTabs.tab("Jira", jiraCard),
                BrowserTabs.tab("Colunas", columnsCard),
                BrowserTabs.tab("Categorias", categoriesCard),
                BrowserTabs.tab("Tempo", timingCard),
                BrowserTabs.tab("Avisos", alertsCard),
                BrowserTabs.tab("Histórico", storageCard),
                BrowserTabs.tab("Sistema", systemCard));

        Button save = new Button("Salvar");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> save());
        Button revert = new Button("Desfazer alterações");
        revert.getStyleClass().add("secondary-button");
        revert.setOnAction(e -> load());
        feedback.setWrapText(true);
        HBox actions = new HBox(12, save, revert, feedback);
        actions.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(14, heading, tabs, actions);
        root.getStyleClass().add("page");
        load();
    }

    public Node getView() {
        return root;
    }

    /** Relê o .env, descartando o que foi digitado e não salvo. */
    public void load() {
        Map<String, String> values = handler.currentValues();
        fields.forEach((key, field) -> field.setText(value(values, key)));
        flags.forEach((key, box) -> {
            String value = values.getOrDefault(key, "");
            box.setSelected(value.isBlank() ? !flagsOffByDefault.contains(key) : !isOff(value));
        });
        tokenExpires.setValue(TokenExpiry.from(values).orElse(null));
        showTokenExpiry();
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
        flags.forEach((key, box) -> values.put(key, Boolean.toString(box.isSelected())));
        tokenExpires.setValue(tokenExpires.getConverter().fromString(tokenExpires.getEditor().getText()));
        values.put(TokenExpiry.KEY, tokenExpires.getValue() == null ? "" : tokenExpires.getValue().toString());
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

    /** Embaixo do API token: "Data de validade:", o calendário e quanto falta para vencer. */
    private HBox tokenExpiryRow() {
        Label caption = new Label("Data de validade:");
        caption.getStyleClass().add("muted");
        tokenExpires.setPromptText("dd/mm/aaaa");
        tokenExpires.setPrefWidth(160);
        tokenExpires.getStyleClass().add("settings-input");
        tokenExpires.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : DATE.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.strip(), DATE);
                } catch (DateTimeParseException e) {
                    return tokenExpires.getValue();
                }
            }
        });
        tokenExpires.valueProperty().addListener((obs, before, now) -> showTokenExpiry());
        tokenExpiresStatus.setWrapText(true);
        HBox row = new HBox(10, caption, tokenExpires, tokenExpiresStatus);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void showTokenExpiry() {
        LocalDate expires = tokenExpires.getValue();
        tokenExpiresStatus.getStyleClass().removeAll("muted", "token-expiry-soon", "token-expiry-expired");
        if (expires == null) {
            tokenExpiresStatus.setText("Veja em id.atlassian.com, na lista de API tokens");
            tokenExpiresStatus.getStyleClass().add("muted");
            return;
        }
        LocalDate today = LocalDate.now();
        tokenExpiresStatus.setText(TokenExpiry.remaining(expires, today));
        tokenExpiresStatus.getStyleClass().add(switch (TokenExpiry.level(expires, today)) {
            case OK -> "muted";
            case SOON -> "token-expiry-soon";
            case EXPIRED -> "token-expiry-expired";
        });
    }

    private CheckBox flag(String key, String label) {
        return flag(key, label, true);
    }

    private void checkForUpdate(Button button) {
        Maintenance maintenance = handler.maintenance();
        button.setDisable(true);
        updateStatus.setText("Procurando...");
        maintenance.checkForUpdate().whenComplete((release, error) -> Platform.runLater(() -> {
            button.setDisable(false);
            if (error != null) {
                updateStatus.setText("Não deu para procurar: " + rootMessage(error));
                return;
            }
            if (release.isEmpty()) {
                updateStatus.setText("Você já está na versão mais nova.");
                return;
            }
            Updater.Release found = release.get();
            updateStatus.setText("Versão " + found.version() + " disponível.");
            if (!confirm("Atualizar o Chronos",
                    "A versão " + found.version() + " está disponível (você tem a " + maintenance.version() + ").",
                    "O histórico atual é copiado para a pasta backup, o instalador é baixado e o Chronos fecha "
                            + "para instalar. Atualizar agora?")) {
                return;
            }
            button.setDisable(true);
            updateStatus.setText("Copiando o histórico e baixando a versão " + found.version() + "...");
            maintenance.prepareUpdate(found).whenComplete((prepared, failure) -> Platform.runLater(() -> {
                button.setDisable(false);
                if (failure != null) {
                    updateStatus.setText("A atualização falhou: " + rootMessage(failure));
                    return;
                }
                try {
                    maintenance.installAndExit(prepared);
                } catch (IOException e) {
                    updateStatus.setText("Não deu para abrir o instalador: " + e.getMessage()
                            + ". Ele está em " + prepared.installer());
                }
            }));
        }));
    }

    private void restoreHistory() {
        Maintenance maintenance = handler.maintenance();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Restaurar base histórica");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Histórico do Chronos (*.db)", "*.db"));
        try {
            chooser.setInitialDirectory(maintenance.backupDir().toAbsolutePath().toFile());
        } catch (IOException e) {
            restoreStatus.setText("Não deu para abrir a pasta backup: " + e.getMessage());
        }
        java.io.File chosen = chooser.showOpenDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        if (!confirm("Restaurar base histórica", "Restaurar " + chosen.getName() + "?",
                "O histórico atual é guardado na pasta backup, a cópia escolhida passa a ser o histórico e o "
                        + "Chronos reinicia.")) {
            return;
        }
        try {
            if (!maintenance.restoreAndRestart(chosen.toPath())) {
                new Alert(Alert.AlertType.INFORMATION, "Abra o Chronos de novo para terminar a restauração.")
                        .showAndWait();
                maintenance.exit();
            }
        } catch (IOException e) {
            restoreStatus.setText("Não deu para restaurar: " + e.getMessage());
        }
    }

    private boolean confirm(String title, String header, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
        alert.setTitle(title);
        alert.setHeaderText(header);
        if (root.getScene() != null) {
            alert.initOwner(root.getScene().getWindow());
        }
        return alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
    }

    private CheckBox flag(String key, String label, boolean onByDefault) {
        if (!onByDefault) {
            flagsOffByDefault.add(key);
        }
        CheckBox box = new CheckBox(label);
        box.setWrapText(true);
        flags.put(key, box);
        return box;
    }

    private static boolean isOff(String value) {
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("false") || normalized.equals("0") || normalized.startsWith("n");
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

package com.chronos.tracker.ui;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.config.EnvFile;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraUser;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.system.WindowsStartup;
import com.chronos.tracker.tracking.TrackingEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Lê e grava o {@code .env} pela tela de configurações e aplica o que mudou sem reabrir o app. */
final class SettingsController implements SettingsPage.Handler {

    private static final List<String> JIRA_KEYS =
            List.of("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN", "JIRA_PROJECT_KEY", "JIRA_JQL");

    private final Path envFile;
    private final TrackingEngine engine;
    private final BiConsumer<AppConfig, AppConfig> onApplied;
    private AppConfig current;

    /** @param onApplied recebe a configuração anterior e a nova depois de salvar */
    SettingsController(Path envFile, AppConfig current, TrackingEngine engine,
                       BiConsumer<AppConfig, AppConfig> onApplied) {
        this.envFile = envFile;
        this.current = current;
        this.engine = engine;
        this.onApplied = onApplied;
    }

    @Override
    public Map<String, String> currentValues() {
        try {
            return EnvFile.read(envFile);
        } catch (IOException e) {
            return Map.of();
        }
    }

    @Override
    public String save(Map<String, String> typed, boolean startWithWindows) throws Exception {
        AppConfig config = validate(typed);

        Map<String, String> existing = currentValues();
        Map<String, String> updates = new LinkedHashMap<>();
        // Campo vazio que nem está no arquivo continua fora dele (vale o padrão).
        typed.forEach((key, value) -> {
            if (!value.isBlank() || existing.containsKey(key)) {
                updates.put(key, value);
            }
        });
        // "Projetos" é um campo só: grava em JIRA_PROJECT_KEY e esvazia JIRA_PROJECT_KEYS, que teria prioridade.
        if (existing.containsKey("JIRA_PROJECT_KEYS")) {
            updates.put("JIRA_PROJECT_KEYS", "");
        }
        try {
            EnvFile.write(envFile, updates);
        } catch (IOException e) {
            throw new IOException("Não foi possível gravar " + envFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }

        String startupNote = "";
        if (WindowsStartup.isAvailable() && startWithWindows != WindowsStartup.isEnabled()) {
            try {
                WindowsStartup.setEnabled(startWithWindows);
            } catch (IOException e) {
                startupNote = " Não deu para mudar a inicialização com o Windows: " + e.getMessage();
            }
        }

        boolean databaseChanged = !config.databasePath().equals(current.databasePath());
        apply(config);
        return "Configurações salvas." + startupNote
                + (databaseChanged ? " O novo arquivo do histórico vale ao reabrir o app." : "");
    }

    @Override
    public CompletableFuture<String> testConnection(Map<String, String> typed) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AppConfig config = validate(typed);
                if (!config.isJiraConfigured()) {
                    throw new IllegalArgumentException("Preencha endereço, e-mail, token e projetos (ou JQL).");
                }
                JiraService service = RestJiraService.from(config);
                String name = service.fetchCurrentUser().map(JiraUser::displayName).orElse("você");
                List<JiraIssue> issues = service.fetchMyIssues();
                return "Conectado como " + name + " · " + issues.size()
                        + (issues.size() == 1 ? " task encontrada" : " tasks encontradas");
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    @Override
    public boolean startWithWindowsAvailable() {
        return WindowsStartup.isAvailable();
    }

    @Override
    public boolean startWithWindowsEnabled() {
        return WindowsStartup.isEnabled();
    }

    /** Confere os valores digitados junto com o resto do .env; lança com a mensagem para a tela. */
    private AppConfig validate(Map<String, String> typed) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(EnvFile.read(envFile));
        merged.putAll(typed);
        merged.remove("JIRA_PROJECT_KEYS");
        AppConfig config;
        try {
            config = AppConfig.fromMap(merged);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        boolean anyJira = JIRA_KEYS.stream().anyMatch(key -> !typed.getOrDefault(key, "").isBlank());
        if (anyJira && !config.isJiraConfigured()) {
            throw new IllegalArgumentException(
                    "Para conectar ao Jira, preencha endereço, e-mail, API token e projetos (ou uma JQL).");
        }
        if (config.possiblyIdleAfter().compareTo(config.inactiveAfter()) >= 0) {
            throw new IllegalArgumentException("\"Possivelmente ausente\" precisa ser menor que o tempo para pausar.");
        }
        return config;
    }

    private void apply(AppConfig config) {
        if (config.jiraSite().equals(current.jiraSite())) {
            engine.setJiraService(RestJiraService.from(config));
        } else {
            engine.switchJira(RestJiraService.from(config), config.jiraSite());
        }
        engine.setWorkingStatuses(config.workingStatuses());
        engine.setAutoStart(config.autoStart());
        engine.setOnlyWorkingColumns(config.onlyWorkingColumns());
        engine.setValidationLabels(config.playLabels(), config.doneLabels());
        engine.setClassifier(new ActivityClassifier(config.possiblyIdleAfter(), config.inactiveAfter()));
        AppConfig previous = current;
        current = config;
        onApplied.accept(previous, config);
    }
}

package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.config.AppConfig;
import com.chronos.tracker.config.AppPaths;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.RestJiraService;
import com.chronos.tracker.tracking.CommentBook;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.IssueAlertMonitor;
import com.chronos.tracker.tracking.TaskComment;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine;
import com.chronos.tracker.tracking.WorklogBook;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Liga o motor de tracking à janela. O monitoramento e o Jira rodam em threads de fundo;
 * a UI só é tocada via {@link Platform#runLater}.
 */
public final class AppController {

    /** De quanto em quanto tempo procura tasks novas dos tipos com aviso. */
    private static final Duration ALERT_INTERVAL = Duration.ofSeconds(30);

    private final TrackingEngine engine;
    private final MainWindow window;
    private final HistoryStore store;
    private Duration pollingInterval;
    private volatile IssueAlertMonitor alerts;
    private volatile Consumer<List<JiraIssue>> alertListener = issues -> { };
    private final CommentBook comments;
    private volatile boolean commentsEnabled;
    private volatile boolean commentTemplateEnabled;
    /** Tasks em que o Jira recusou o template, com o erro; não tenta de novo a cada busca. */
    private final Map<String, String> templateFailed = new java.util.concurrent.ConcurrentHashMap<>();
    /** O que aconteceu na última vez que o template automático rodou. */
    private volatile String templateStatus = "";

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> polling;
    private volatile Consumer<Snapshot> snapshotListener = snapshot -> { };

    public AppController(TrackingEngine engine, AppConfig config, HistoryStore store, Path envFile) {
        this.engine = engine;
        this.store = store;
        this.pollingInterval = config.pollingInterval();
        this.alerts = alertMonitor(config, false);
        Maintenance maintenance = new Maintenance(store, AppPaths.dataDir(), Platform::exit);
        SettingsController settings = new SettingsController(envFile, config, engine, this::applyConfig, maintenance);
        WorklogBook book = new WorklogBook(store, engine::jiraService, Clock.systemDefaultZone());
        this.comments = new CommentBook(AppPaths.dataDir().resolve("comment-template.md"), store,
                engine::jiraService, Clock.systemDefaultZone());
        this.window = new MainWindow(this::toggle, this::addManual, store, worklogHandler(book), commentsHandler(),
                settings);
        useCommentSettings(config);
        window.render(engine.tick());
    }

    private WorklogPage.Handler worklogHandler(WorklogBook book) {
        return new WorklogPage.Handler() {
            @Override
            public List<WorklogBook.Item> items(List<TaskView> live) throws Exception {
                return book.items(live);
            }

            @Override
            public CompletableFuture<Duration> log(String issueKey) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Duration spent = book.log(issueKey);
                        engine.recordWorklog(issueKey, spent);
                        return spent;
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
            }

            @Override
            public CompletableFuture<Optional<Boolean>> timeTrackingAvailable(List<TaskView> live) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return book.timeTrackingAvailable(live);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
            }
        };
    }

    private CommentsPage.Handler commentsHandler() {
        return new CommentsPage.Handler() {
            @Override
            public List<CommentBook.Item> items(List<TaskView> live) throws Exception {
                return comments.items(live);
            }

            @Override
            public List<TaskComment> history() throws Exception {
                return comments.history();
            }

            @Override
            public String template() {
                return comments.template();
            }

            @Override
            public void saveTemplate(String markdown) throws Exception {
                comments.saveTemplate(markdown);
            }

            @Override
            public String templateStatus() {
                return templateStatus;
            }

            @Override
            public CompletableFuture<TaskComment> save(String issueKey, String summary, String markdown) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        TaskComment saved = comments.save(issueKey, summary, markdown);
                        engine.recordEvent(false, I18n.t("Comentário salvo no Jira"), issueKey);
                        return saved;
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
            }
        };
    }

    private void useCommentSettings(AppConfig config) {
        commentsEnabled = config.commentsEnabled();
        commentTemplateEnabled = config.commentTemplateEnabled();
        if (Platform.isFxApplicationThread()) {
            window.setCommentsEnabled(config.commentsEnabled());
        } else {
            Platform.runLater(() -> window.setCommentsEnabled(config.commentsEnabled()));
        }
    }

    /**
     * Põe o template nas tasks suas das colunas monitoradas que ainda não têm comentário do Chronos (com a opção
     * ligada), inclusive as que já estavam na coluna. Se o Jira recusar, não tenta de novo até reabrir. Anota em
     * {@link #templateStatus} o que aconteceu, para a aba Comentários > Template mostrar.
     */
    private void addTemplates() {
        if (!commentsEnabled) {
            return;
        }
        if (!commentTemplateEnabled) {
            templateStatus = I18n.t("O template automático está desligado. Ligue \"Habilitar template padrão\" em Configurações.");
            return;
        }
        List<JiraIssue> inColumns = engine.inWorkingColumns();
        List<JiraIssue> mine = inColumns.stream().filter(JiraIssue::mine).toList();
        if (mine.isEmpty()) {
            templateStatus = inColumns.isEmpty()
                    ? I18n.t("Nenhuma task nas colunas monitoradas agora.")
                    : I18n.t("As {0} tasks nas colunas monitoradas não são suas (outro responsável ou sem responsável); o template só vai para as suas.", inColumns.size());
            return;
        }
        String status = I18n.t("Todas as suas {0} tasks nas colunas monitoradas já têm comentário do Chronos.", mine.size());
        for (JiraIssue issue : mine) {
            if (templateFailed.containsKey(issue.key())) {
                status = I18n.t("O Jira recusou o template em {0}: {1}", issue.key(), templateFailed.get(issue.key()));
                continue;
            }
            try {
                if (comments.addTemplate(issue.key(), issue.summary()).isPresent()) {
                    engine.recordEvent(false, I18n.t("Template de comentário adicionado"), issue.key());
                    status = I18n.t("Template posto em {0} às {1}.", issue.key(), Formats.clock(java.time.Instant.now()));
                }
            } catch (Exception e) {
                String message = String.valueOf(e.getMessage());
                templateFailed.put(issue.key(), message);
                status = I18n.t("O Jira recusou o template em {0}: {1}", issue.key(), message);
                engine.recordEvent(true, I18n.t("Não deu para pôr o template em {0}", issue.key()), message);
            }
        }
        templateStatus = status;
    }

    public Parent getView() {
        return window.getView();
    }

    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chronos-worker");
            thread.setDaemon(true);
            return thread;
        });
        schedulePolling();
        scheduler.scheduleAtFixedRate(this::safeTick, 0, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::checkAlerts, 3, ALERT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Recebe as tasks novas dos tipos com aviso, na thread da UI. */
    public void setAlertListener(Consumer<List<JiraIssue>> listener) {
        alertListener = listener;
    }

    /** Recebe cada estado novo, na thread da UI (usado pelo ícone da bandeja). */
    public void setSnapshotListener(Consumer<Snapshot> listener) {
        snapshotListener = listener;
    }

    public void pauseTask(String issueKey) {
        engine.pause(issueKey);
        refresh();
    }

    public void pauseAllTasks() {
        engine.pauseAll();
        refresh();
    }

    /**
     * Pausa a task e a move para "Concluído" no Jira, fora da thread da UI. {@code onError} recebe a mensagem
     * se o Jira recusar.
     */
    public void finishTask(String issueKey, Consumer<String> onError) {
        Thread worker = new Thread(() -> {
            try {
                engine.finish(issueKey);
            } catch (Exception e) {
                onError.accept(I18n.t("Não foi possível finalizar {0}: {1}", issueKey, e.getMessage()));
            }
            refresh();
        }, "chronos-finish");
        worker.setDaemon(true);
        worker.start();
    }

    private void refresh() {
        Snapshot snapshot = engine.tick();
        Platform.runLater(() -> {
            window.render(snapshot);
            snapshotListener.accept(snapshot);
        });
    }

    /** Configurações salvas: novo intervalo do Jira, o tema e, se mudou algo, novos avisos. */
    private void applyConfig(AppConfig previous, AppConfig next) {
        setPollingInterval(next.pollingInterval());
        useCommentSettings(next);
        if (window.getView().getScene() != null) {
            Themes.apply(window.getView().getScene(), next.darkMode());
        }
        // Tipos novos: registra o que já existe sem avisar, para não chover notificação das tasks antigas.
        alerts = alertMonitor(next, !previous.alertIssueTypes().equals(next.alertIssueTypes()));
    }

    private IssueAlertMonitor alertMonitor(AppConfig config, boolean seedSilently) {
        return new IssueAlertMonitor(RestJiraService.from(config), store, config.alertIssueTypes(),
                Clock.systemDefaultZone(), seedSilently);
    }

    private void checkAlerts() {
        try {
            List<JiraIssue> fresh = alerts.check();
            if (fresh.isEmpty()) {
                return;
            }
            fresh.forEach(engine::recordAlert);
            Platform.runLater(() -> alertListener.accept(fresh));
        } catch (Exception e) {
            // Jira fora ou banco ocupado: tenta de novo na próxima rodada.
            System.err.println("Avisos de task: " + e.getMessage());
        }
    }

    /** Reagenda o Jira com o novo intervalo e consulta na hora (as configurações acabaram de mudar). */
    private synchronized void setPollingInterval(Duration interval) {
        pollingInterval = interval;
        if (scheduler != null) {
            schedulePolling();
        }
    }

    private synchronized void schedulePolling() {
        if (polling != null) {
            polling.cancel(false);
        }
        polling = scheduler.scheduleWithFixedDelay(this::safePollJira, 0, pollingInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        engine.shutdown();
    }

    private void toggle(TaskView task) {
        if (task.running()) {
            engine.pause(task.key());
        } else {
            engine.play(task.key());
        }
        window.render(engine.tick());
    }

    private void addManual() {
        Snapshot snapshot = engine.tick();
        String featured = snapshot.featuredTask().map(TaskView::key).orElse("");
        ManualEntryDialog.show(window.getView().getScene().getWindow(), snapshot.tasks(), featured,
                (key, date, duration, note) -> engine.addManual(key, date, duration, note));
        window.render(engine.tick());
    }

    private void safePollJira() {
        try {
            engine.pollJira();
        } catch (RuntimeException e) {
            // Uma falha inesperada não pode cancelar o agendamento.
            e.printStackTrace();
        }
        try {
            // À parte: um erro na busca não pode impedir o template das tasks que já se conhece.
            addTemplates();
        } catch (RuntimeException e) {
            engine.recordEvent(true, I18n.t("Falha ao pôr o template de comentário"), String.valueOf(e.getMessage()));
        }
    }

    private void safeTick() {
        try {
            Snapshot snapshot = engine.tick();
            Platform.runLater(() -> {
                window.render(snapshot);
                snapshotListener.accept(snapshot);
            });
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }
}

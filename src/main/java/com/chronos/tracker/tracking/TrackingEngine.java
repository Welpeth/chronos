package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityMonitor;
import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.config.I18n;
import com.chronos.tracker.jira.JiraAuthException;
import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraIssue;
import com.chronos.tracker.jira.JiraQueryException;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.JiraUser;
import com.chronos.tracker.jira.StatusCategory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Junta atividade, Jira e os cronômetros. Não depende de JavaFX, então pode rodar em qualquer thread.
 *
 * <p>Por padrão, toda issue "em andamento" no Jira conta tempo, e várias contam ao mesmo tempo.
 * O play/pause do usuário numa task vale até o status dela mudar no Jira; aí o app volta a seguir o Jira.
 * Quando o usuário fica inativo, todas as tasks pausam e retomam juntas quando ele volta.
 *
 * <p>{@link #tick()} roda a cada segundo e é barato; {@link #pollJira()} faz as chamadas de rede e roda
 * no intervalo de polling configurado.
 */
public final class TrackingEngine {

    static final int MAX_EVENTS = 200;
    /** De quanto em quanto tempo os intervalos ainda abertos são gravados, para não perder tempo numa queda. */
    static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(30);
    /** De quanto em quanto tempo confere de novo o quadro das tasks (uma task pode mudar de quadro). */
    static final Duration BOARDS_REFRESH = Duration.ofMinutes(5);
    /** Separa os quadros de uma task que está em mais de um, como ficam gravados. */
    static final String BOARD_SEPARATOR = "\n";
    /** Limite de um dia: tempo contado mais o inserido à mão não pode passar disto. */
    public static final Duration DAILY_LIMIT = Duration.ofHours(8);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private final MultiTaskTracker tracker;
    private final ActivityMonitor activityMonitor;
    private volatile ActivityClassifier classifier;
    private volatile JiraService jiraService;
    private final Clock clock;
    private final ZoneId zone;
    private final HistoryStore store;

    // Escritos pela thread de polling, lidos no tick.
    private volatile List<JiraIssue> issues = List.of();
    private volatile long issuesVersion;
    private volatile JiraSyncStatus jiraStatus;
    private volatile Optional<String> jiraError = Optional.empty();
    private volatile Optional<Instant> lastSync = Optional.empty();
    private volatile Optional<JiraUser> user = Optional.empty();
    private volatile Optional<String> projectLabel = Optional.empty();

    // Protegidos por "this".
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Map<String, String> lastStatus = new HashMap<>();
    private volatile Predicate<JiraIssue> working = JiraIssue::isInProgress;
    private volatile List<String> workingNames = List.of();
    /** Status de cada coluna dos quadros, lidos do Jira; vazio até a primeira leitura ou se ela falhar. */
    private volatile Map<String, Set<String>> columnStatuses = Map.of();
    /** Colunas de cada quadro, na ordem do quadro, para o kanban do painel. */
    private volatile Map<String, List<JiraService.KanbanColumn>> boardColumns = Map.of();
    private Instant columnsCheckedAt = Instant.MIN;
    /** Se o tempo começa sozinho quando a task entra numa coluna que conta; senão, só pelo play. */
    private volatile boolean autoStart = true;
    private volatile boolean onlyWorkingColumns;
    private final ValidationLabels labels = new ValidationLabels();
    /** Se cada issue estava numa coluna monitorada na última busca, para saber quando ela sai. */
    private final Map<String, Boolean> lastInColumn = new HashMap<>();
    private long seenIssuesVersion;
    private boolean inactivityPause;
    private Instant inactiveSince;
    private boolean justResumed;
    private LocalDate day;
    private Instant lastTick;
    private Duration activeToday = Duration.ZERO;
    private Duration inactiveToday = Duration.ZERO;
    private List<TimeEntry> storedToday = new ArrayList<>();
    private List<ManualEntry> manualToday = new ArrayList<>();
    private final Map<String, String> summaries = new HashMap<>();
    /** Quadro de cada task (com {@code JIRA_BOARDS}), guardado no histórico para os dias anteriores. */
    private volatile Map<String, String> boards = Map.of();
    private Instant boardsCheckedAt = Instant.MIN;
    /** Tasks do usuário que acabaram de entrar numa coluna monitorada, para o template de comentário. */
    private final java.util.Queue<JiraIssue> enteredColumns = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Tasks cujo quadro já foi procurado, mesmo as que não estão em nenhum dos quadros. */
    private final Set<String> boardsLookedUp = ConcurrentHashMap.newKeySet();
    private Instant lastCheckpoint;
    private boolean storageFailing;

    private final Deque<ActivityEvent> events = new ArrayDeque<>();

    public TrackingEngine(MultiTaskTracker tracker, ActivityMonitor activityMonitor, ActivityClassifier classifier,
                          JiraService jiraService, Clock clock) {
        this(tracker, activityMonitor, classifier, jiraService, clock, HistoryStore.NONE);
    }

    /**
     * Com um {@code store}, o tempo já gravado é carregado ao abrir: o total de cada task e o que foi feito hoje.
     */
    public TrackingEngine(MultiTaskTracker tracker, ActivityMonitor activityMonitor, ActivityClassifier classifier,
                          JiraService jiraService, Clock clock, HistoryStore store) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.activityMonitor = Objects.requireNonNull(activityMonitor, "activityMonitor");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.jiraService = Objects.requireNonNull(jiraService, "jiraService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = clock.getZone();
        this.jiraStatus = jiraService.isConfigured() ? JiraSyncStatus.SYNCING : JiraSyncStatus.NOT_CONFIGURED;
        this.store = Objects.requireNonNull(store, "store");
        restoreFromStore();
    }

    private void restoreFromStore() {
        Instant now = clock.instant();
        day = LocalDate.ofInstant(now, zone);
        lastTick = now;
        lastCheckpoint = now;
        try {
            tracker.preload(store.totalsByTask());
            for (HistoryStore.StoredEntry stored : store.entriesOn(day)) {
                storedToday.add(stored.entry());
                if (!stored.summary().isEmpty()) {
                    summaries.putIfAbsent(stored.entry().issueKey(), stored.summary());
                }
            }
            activeToday = Intervals.union(storedToday);
            boards = Map.copyOf(store.issueBoards());
            inactiveToday = store.idleOn(day);
            manualToday = new ArrayList<>(store.manualOn(day));
        } catch (HistoryStore.HistoryException e) {
            reportStorageFailure(e);
        }
    }

    /** Aplica um ciclo de monitoramento e devolve o estado atual para a UI. */
    public synchronized Snapshot tick() {
        Instant now = clock.instant();
        accountDailyTime(now);

        Duration idle = activityMonitor.getIdleTime();
        ActivityState activity = classifier.classify(idle);
        List<JiraIssue> currentIssues = issues;

        dropOverridesWhenJiraChanges(currentIssues);
        Set<String> wanted = wantedRunning(currentIssues);
        Set<String> effective = applyInactivity(activity, wanted, now);
        applyRunning(effective);
        if (!Duration.between(lastCheckpoint, now).minus(CHECKPOINT_INTERVAL).isNegative()) {
            checkpoint(now);
        }

        return snapshot(activity, idle, currentIssues);
    }

    /** Consulta o Jira. Bloqueia durante as chamadas de rede; nunca chamar na thread da UI. */
    public void pollJira() {
        JiraService service = jiraService;
        if (!service.isConfigured()) {
            jiraStatus = JiraSyncStatus.NOT_CONFIGURED;
            return;
        }
        sendLabels(service);
        JiraSyncStatus previous = jiraStatus;
        try {
            List<JiraIssue> fetched = service.fetchMyIssues();
            if (user.isEmpty()) {
                user = service.fetchCurrentUser();
            }
            if (projectLabel.isEmpty()) {
                projectLabel = service.fetchProjectLabel();
            }
            boolean changed = !fetched.equals(issues);
            synchronized (this) {
                fetched.forEach(issue -> summaries.put(issue.key(), issue.summary()));
            }
            issues = List.copyOf(fetched);
            issuesVersion++;
            updateBoards(service, fetched);
            updateColumns(service);
            jiraStatus = JiraSyncStatus.SYNCED;
            jiraError = Optional.empty();
            lastSync = Optional.of(clock.instant());
            if (changed || previous != JiraSyncStatus.SYNCED) {
                addEvent(ActivityEvent.Kind.SYNC, I18n.t("Sincronização com Jira concluída"), describeSync(fetched));
            }
        } catch (JiraException e) {
            // Mantém as últimas issues conhecidas: uma queda curta de rede não deve interromper o tracking.
            jiraStatus = e instanceof JiraAuthException ? JiraSyncStatus.AUTH_ERROR
                    : e instanceof JiraQueryException ? JiraSyncStatus.QUERY_ERROR
                    : JiraSyncStatus.ERROR;
            jiraError = Optional.ofNullable(e.getMessage());
            if (previous != jiraStatus) {
                addEvent(ActivityEvent.Kind.ERROR, I18n.t("Falha ao sincronizar com o Jira"), jiraError.orElse(""));
            }
        }
    }

    /**
     * Lê do Jira as colunas dos quadros, na primeira consulta e de tempos em tempos: servem para o kanban e para
     * as colunas digitadas valerem pelo nome da coluna no quadro.
     */
    private void updateColumns(JiraService service) {
        Instant now = clock.instant();
        if (Duration.between(columnsCheckedAt, now).compareTo(BOARDS_REFRESH) < 0) {
            return;
        }
        columnsCheckedAt = now;
        try {
            Map<String, List<JiraService.KanbanColumn>> found = service.fetchBoardColumns();
            if (!found.equals(boardColumns)) {
                boardColumns = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(found));
                columnStatuses = JiraService.columnStatuses(found);
                rebuildWorking();
            }
        } catch (JiraException e) {
            addEvent(ActivityEvent.Kind.ERROR, I18n.t("Falha ao ler as colunas dos quadros do Jira"), e.getMessage());
        }
    }

    /**
     * Descobre o quadro das tasks quando aparece uma task nova e, de tempos em tempos, para as que mudaram de
     * quadro. Falhar aqui não derruba a sincronização: as tasks só ficam sem quadro até a próxima vez.
     */
    private void updateBoards(JiraService service, List<JiraIssue> fetched) {
        if (!service.usesBoards()) {
            return;
        }
        Instant now = clock.instant();
        Map<String, String> known = boards;
        boolean unknown = fetched.stream().anyMatch(issue -> !boardsLookedUp.contains(issue.key()));
        if (!unknown && Duration.between(boardsCheckedAt, now).compareTo(BOARDS_REFRESH) < 0) {
            return;
        }
        boardsCheckedAt = now;
        try {
            List<String> keys = fetched.stream().map(JiraIssue::key).toList();
            Map<String, List<String>> found = service.fetchBoards(keys);
            boardsLookedUp.addAll(keys);
            Map<String, String> changed = new HashMap<>();
            // Uma task que saiu de todos os quadros fica sem quadro (""), em vez de manter o antigo.
            for (String key : keys) {
                String board = String.join(BOARD_SEPARATOR, found.getOrDefault(key, List.of()));
                if (!board.equals(known.getOrDefault(key, ""))) {
                    changed.put(key, board);
                }
            }
            if (changed.isEmpty()) {
                return;
            }
            Map<String, String> merged = new HashMap<>(known);
            merged.putAll(changed);
            boards = Map.copyOf(merged);
            store.saveIssueBoards(changed);
        } catch (JiraException e) {
            addEvent(ActivityEvent.Kind.ERROR, I18n.t("Falha ao ler os quadros do Jira"), e.getMessage());
        } catch (HistoryStore.HistoryException e) {
            reportStorageFailure(e);
        }
    }

    /**
     * Liga o tempo de {@code issueKey} na hora, sem mexer nas outras tasks. Não faz nada se a task está fora
     * das colunas monitoradas e o tempo está travado nelas.
     */
    public synchronized void play(String issueKey) {
        String key = normalize(issueKey);
        if (key.isEmpty() || isTimeLocked(key)) {
            return;
        }
        overrides.put(key, true);
        if (!inactivityPause && !tracker.isRunning(key)) {
            tracker.start(key);
            labelStarted(key);
            addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo iniciado"), I18n.t("Task: {0}", key));
        }
    }

    /** Pausa o tempo de {@code issueKey} na hora, sem mexer nas outras tasks. */
    public synchronized void pause(String issueKey) {
        String key = normalize(issueKey);
        if (key.isEmpty()) {
            return;
        }
        overrides.put(key, false);
        if (closeInterval(key)) {
            addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo pausado"), I18n.t("Task: {0}", key));
        }
    }

    /**
     * Soma tempo à mão numa task, num dia. A inserção é recusada se o dia, somando o tempo já contado e as
     * outras inserções manuais, passaria de {@link #DAILY_LIMIT}.
     */
    public synchronized ManualEntry addManual(String issueKey, LocalDate date, Duration duration, String note)
            throws InvalidManualEntryException, HistoryStore.HistoryException {
        String key = normalize(issueKey);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (key.isEmpty()) {
            throw new InvalidManualEntryException(I18n.t("Escolha a task."));
        }
        if (isTimeLocked(key)) {
            throw new InvalidManualEntryException(I18n.t(
                    "{0} está fora das colunas monitoradas. Para contar tempo nela, desligue a trava na aba Colunas das Configurações.",
                    key));
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidManualEntryException(I18n.t("Informe quanto tempo foi trabalhado."));
        }
        if (date == null || date.isAfter(today)) {
            throw new InvalidManualEntryException(I18n.t("Não dá para inserir tempo num dia que ainda não chegou."));
        }
        Duration dayTotal = workedOn(date, today);
        Duration after = dayTotal.plus(duration);
        if (after.compareTo(DAILY_LIMIT) > 0) {
            throw new InvalidManualEntryException(I18n.t(
                    "Tempo manual inválido: com esta inserção o dia {0} teria {1}, acima do limite de {2}h. Já contado no dia: {3}.",
                    DAY_MONTH.format(date), hoursMinutes(after), DAILY_LIMIT.toHours(), hoursMinutes(dayTotal)));
        }
        ManualEntry saved = store.saveManual(new ManualEntry(0, key, summaries.getOrDefault(key, ""), date,
                duration, note == null ? "" : note.strip(), now));
        tracker.preload(Map.of(key, duration));
        if (date.equals(today)) {
            manualToday.add(saved);
        }
        String added = key + " · " + hoursMinutes(duration);
        addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo manual adicionado"),
                date.equals(today) ? added : I18n.t("{0} em {1}", added, DAY_MONTH.format(date)));
        return saved;
    }

    /** Tempo já contado num dia: relógio com alguma task ligada mais as inserções manuais. */
    private Duration workedOn(LocalDate date, LocalDate today) throws HistoryStore.HistoryException {
        if (date.equals(today) && date.equals(day)) {
            return activeToday.plus(sum(manualToday));
        }
        List<TimeEntry> intervals = store.entriesOn(date).stream().map(HistoryStore.StoredEntry::entry).toList();
        return Intervals.union(intervals).plus(sum(store.manualOn(date)));
    }

    private static Duration sum(List<ManualEntry> entries) {
        return entries.stream().map(ManualEntry::duration).reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * Pausa a task e a move para "Concluído" no Jira. Bloqueia durante a chamada ao Jira; nunca chamar na
     * thread da UI.
     */
    public void finish(String issueKey) throws JiraException {
        String key = normalize(issueKey);
        pause(key);
        labels.finished(key);
        String status = jiraService.completeIssue(key);
        addEvent(ActivityEvent.Kind.TASK, I18n.t("Task finalizada"), I18n.t("{0} movida para {1}", key, status));
        pollJira();
    }

    /** Registra em "Atividade recente" que o tempo da task foi lançado no Jira. */
    public void recordWorklog(String issueKey, Duration spent) {
        long minutes = spent.toMinutes();
        String amount = minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
        addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo apontado no Jira"), issueKey + " · " + amount);
    }

    /** Registra em "Atividade recente" que chegou uma task de um tipo avisado. */
    public void recordAlert(JiraIssue issue) {
        String type = issue.issueType().isEmpty() ? I18n.t("Nova task") : issue.issueType();
        addEvent(ActivityEvent.Kind.ALERT, type + ": " + issue.key(), issue.summary());
    }

    /** Pausa todas as tasks que estão contando. */
    public synchronized void pauseAll() {
        for (String key : List.copyOf(tracker.runningKeys())) {
            pause(key);
        }
    }

    /** Encerra e grava todos os intervalos abertos, por exemplo ao fechar o aplicativo. */
    public synchronized List<TimeEntry> shutdown() {
        if (inactivityPause) {
            persistIdle(inactiveSince, clock.instant());
        }
        List<TimeEntry> closed = tracker.pauseAll();
        closed.forEach(this::persist);
        return closed;
    }

    public List<TimeEntry> completedEntries() {
        return tracker.getCompletedEntries();
    }

    /** Tempo ativo do dia conta o relógio, não a soma das tasks: 1h com 3 tasks ligadas é 1h. */
    private void accountDailyTime(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!today.equals(day)) {
            day = today;
            activeToday = Duration.ZERO;
            inactiveToday = Duration.ZERO;
            storedToday = new ArrayList<>();
            manualToday = new ArrayList<>();
            lastTick = now;
            return;
        }
        Duration delta = Duration.between(lastTick, now);
        lastTick = now;
        if (delta.isNegative()) {
            return;
        }
        if (!tracker.runningKeys().isEmpty()) {
            activeToday = activeToday.plus(delta);
        } else if (inactivityPause) {
            inactiveToday = inactiveToday.plus(delta);
        }
    }

    /**
     * Troca para outro Jira (outro endereço): fecha o que está contando, passa o histórico para o do novo Jira
     * e recomeça com os totais dele. As tasks do Jira anterior somem até a próxima consulta.
     */
    public synchronized void switchJira(JiraService service, String site) {
        tracker.pauseAll().forEach(this::persist);
        overrides.clear();
        lastStatus.clear();
        summaries.clear();
        boardsLookedUp.clear();
        boardsCheckedAt = Instant.MIN;
        storedToday = new ArrayList<>();
        manualToday = new ArrayList<>();
        issues = List.of();
        tracker.reset();
        store.useSite(site);
        setJiraService(service);
        jiraStatus = service.isConfigured() ? JiraSyncStatus.SYNCING : JiraSyncStatus.NOT_CONFIGURED;
        restoreFromStore();
        addEvent(ActivityEvent.Kind.SYNC, I18n.t("Jira trocado"), I18n.t("Histórico e totais agora são do novo Jira"));
    }

    /** Serviço do Jira em uso (muda quando as configurações são salvas). */
    public JiraService jiraService() {
        return jiraService;
    }

    /**
     * Troca a conexão com o Jira (por exemplo, depois de salvar as configurações). As tasks já conhecidas
     * continuam na tela até a próxima sincronização.
     */
    public void setJiraService(JiraService service) {
        jiraService = Objects.requireNonNull(service, "jiraService");
        // Os quadros podem ter mudado: procura de novo o quadro de cada task.
        boardsLookedUp.clear();
        boardsCheckedAt = Instant.MIN;
        columnsCheckedAt = Instant.MIN;
        user = Optional.empty();
        projectLabel = Optional.empty();
        jiraError = Optional.empty();
        jiraStatus = service.isConfigured() ? JiraSyncStatus.SYNCING : JiraSyncStatus.NOT_CONFIGURED;
    }

    /** Troca os limites de "possivelmente ausente" e "inativo". */
    public void setClassifier(ActivityClassifier newClassifier) {
        classifier = Objects.requireNonNull(newClassifier, "classifier");
    }

    /**
     * Quais status do Jira contam tempo, pelo nome da coluna (sem diferenciar maiúsculas). Com a lista vazia,
     * vale a categoria do status: tudo que o Jira considera "em andamento" conta.
     */
    public void setWorkingStatuses(List<String> statusNames) {
        workingNames = List.copyOf(statusNames);
        rebuildWorking();
    }

    /**
     * O nome digitado vale como nome de status e como nome de coluna do quadro: uma coluna "Test" que mostra o
     * status "Em teste" conta para as tasks em "Em teste".
     */
    private void rebuildWorking() {
        Map<String, Set<String>> byColumn = new HashMap<>();
        columnStatuses.forEach((column, statuses) -> byColumn.computeIfAbsent(columnKey(column), c -> new HashSet<>())
                .addAll(statuses.stream().map(TrackingEngine::columnKey).toList()));
        Set<String> names = new HashSet<>();
        workingNames.forEach(name -> {
            names.add(columnKey(name));
            names.addAll(byColumn.getOrDefault(columnKey(name), Set.of()));
        });
        working = workingNames.isEmpty()
                ? JiraIssue::isInProgress
                : issue -> names.contains(columnKey(issue.statusName()));
    }

    /** Compara nomes de coluna sem maiúsculas, acentos e espaços repetidos ("Em Análise" = "em analise"). */
    static String columnKey(String name) {
        String plain = java.text.Normalizer.normalize(name.strip(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Com {@code false}, entrar numa coluna que conta não liga o tempo: só o play liga. Sair dessas colunas
     * continua pausando.
     */
    public synchronized void setAutoStart(boolean enabled) {
        autoStart = enabled;
    }

    /**
     * Com {@code true}, tasks do Jira fora das colunas monitoradas não aceitam tempo: nem play nem inserção
     * manual. Tasks digitadas à mão, que não vêm do Jira, continuam livres.
     */
    public synchronized void setOnlyWorkingColumns(boolean enabled) {
        onlyWorkingColumns = enabled;
    }

    /**
     * Tasks do usuário que entraram numa coluna monitorada desde a última chamada (vinham de outra coluna do
     * Jira). Não inclui as que já estavam na coluna quando o Chronos abriu.
     */
    public List<JiraIssue> drainEnteredColumns() {
        List<JiraIssue> entered = new ArrayList<>();
        JiraIssue issue;
        while ((issue = enteredColumns.poll()) != null) {
            entered.add(issue);
        }
        return entered;
    }

    /** Anota na Atividade recente (os comentários usam para contar o que fizeram no Jira). */
    public void recordEvent(boolean error, String title, String detail) {
        addEvent(error ? ActivityEvent.Kind.ERROR : ActivityEvent.Kind.TASK, title, detail);
    }

    /** Tags da validação: {@code playLabels} ao começar o tempo, {@code doneLabels} no lugar delas ao terminar. */
    public void setValidationLabels(List<String> playLabels, List<String> doneLabels) {
        labels.configure(playLabels, doneLabels);
    }

    /** Só issues do Jira recebem tags: tasks digitadas à mão não existem lá. */
    private void labelStarted(String key) {
        if (issues.stream().anyMatch(issue -> issue.key().equals(key))) {
            labels.started(key);
        }
    }

    /** Envia ao Jira as tags pendentes. Bloqueia; nunca chamar na thread da UI. */
    private void sendLabels(JiraService service) {
        for (ValidationLabels.Change change : labels.drain()) {
            try {
                service.updateLabels(change.issueKey(), change.add(), change.remove());
                addEvent(ActivityEvent.Kind.TASK, I18n.t("Tags atualizadas"),
                        change.issueKey() + " · " + ValidationLabels.describe(change));
            } catch (JiraException e) {
                addEvent(ActivityEvent.Kind.ERROR, I18n.t("Não deu para mudar as tags de {0}", change.issueKey()),
                        String.valueOf(e.getMessage()));
            }
        }
    }

    private boolean isTimeLocked(String key) {
        return onlyWorkingColumns && issues.stream()
                .filter(issue -> issue.key().equals(key))
                .anyMatch(issue -> !working.test(issue));
    }

    /**
     * Quando uma issue muda de coluna no Jira (ou some da busca), o play/pause manual dela deixa de valer:
     * saiu de "em andamento", pausa; entrou, começa a contar. Sem o início automático, uma task ligada no
     * play continua contando enquanto passa de uma coluna que conta para outra.
     */
    private void dropOverridesWhenJiraChanges(List<JiraIssue> currentIssues) {
        long version = issuesVersion;
        if (version == seenIssuesVersion) {
            return;
        }
        seenIssuesVersion = version;
        Set<String> present = new HashSet<>();
        for (JiraIssue issue : currentIssues) {
            present.add(issue.key());
            boolean inColumn = working.test(issue);
            Boolean wasInColumn = lastInColumn.put(issue.key(), inColumn);
            String before = lastStatus.put(issue.key(), issue.statusName());
            if (before != null && !before.equals(issue.statusName())) {
                // Saiu da coluna no Jira (e não porque as colunas mudaram nas Configurações): validação acabou.
                if (Boolean.TRUE.equals(wasInColumn) && !inColumn) {
                    labels.finished(issue.key());
                }
                if (Boolean.FALSE.equals(wasInColumn) && inColumn && issue.mine()) {
                    enteredColumns.add(issue);
                }
                boolean keepPlaying = !autoStart && Boolean.TRUE.equals(overrides.get(issue.key()))
                        && working.test(issue);
                if (!keepPlaying) {
                    overrides.remove(issue.key());
                }
            }
        }
        lastStatus.keySet().removeIf(key -> {
            if (present.contains(key)) {
                return false;
            }
            overrides.remove(key);
            lastInColumn.remove(key);
            return true;
        });
    }

    private Set<String> wantedRunning(List<JiraIssue> currentIssues) {
        Set<String> wanted = new LinkedHashSet<>();
        for (JiraIssue issue : currentIssues) {
            if (overrides.getOrDefault(issue.key(), autoStart && issue.mine() && working.test(issue))) {
                wanted.add(issue.key());
            }
        }
        overrides.forEach((key, on) -> {
            if (on) {
                wanted.add(key);
            }
        });
        return wanted;
    }

    private Set<String> applyInactivity(ActivityState activity, Set<String> wanted, Instant now) {
        if (wanted.isEmpty()) {
            inactivityPause = false;
            return wanted;
        }
        if (activity == ActivityState.INACTIVE) {
            if (!inactivityPause) {
                inactivityPause = true;
                inactiveSince = now;
                addEvent(ActivityEvent.Kind.ACTIVITY, I18n.t("Inatividade detectada"), I18n.t("Tempo pausado em todas as tasks"));
            }
            return Set.of();
        }
        if (inactivityPause) {
            inactivityPause = false;
            justResumed = true;
            persistIdle(inactiveSince, now);
            addEvent(ActivityEvent.Kind.ACTIVITY, I18n.t("Você voltou a estar ativo"),
                    I18n.t("Após {0} de inatividade", minutes(Duration.between(inactiveSince, now))));
        }
        return wanted;
    }

    /** Liga e desliga os cronômetros. Pausas e retomadas por inatividade já têm o seu próprio evento. */
    private void applyRunning(Set<String> effective) {
        boolean quiet = inactivityPause || justResumed;
        justResumed = false;
        for (String key : tracker.runningKeys()) {
            if (!effective.contains(key)) {
                closeInterval(key);
                if (!quiet) {
                    addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo pausado"), I18n.t("Task: {0}", key));
                }
            }
        }
        for (String key : effective) {
            if (!tracker.isRunning(key)) {
                tracker.start(key);
                labelStarted(key);
                if (!quiet) {
                    addEvent(ActivityEvent.Kind.TASK, I18n.t("Tempo iniciado"), I18n.t("Task: {0}", key));
                }
            }
        }
    }

    private Snapshot snapshot(ActivityState activity, Duration idle, List<JiraIssue> currentIssues) {
        List<TaskView> tasks = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        for (JiraIssue issue : currentIssues) {
            boolean inColumn = working.test(issue);
            tasks.add(view(issue.key(), issue.summary(), issue.statusName(), issue.category(), issue.assignee(),
                    issue.mine(), inColumn, inColumn || !onlyWorkingColumns, issue.updated()));
            listed.add(issue.key());
        }
        // Tasks que contaram tempo mas não vêm mais do Jira (digitadas à mão ou fora da busca).
        tracker.trackedKeys().stream().filter(key -> !listed.contains(key)).sorted().forEach(key ->
                tasks.add(view(key, "", "", StatusCategory.IN_PROGRESS, "", true, false, true, Optional.empty())));

        return new Snapshot(
                activity,
                idle,
                featured(tasks),
                List.copyOf(tasks),
                inactivityPause,
                activeToday,
                inactiveToday,
                sum(manualToday),
                List.copyOf(manualToday),
                jiraStatus,
                jiraError,
                lastSync,
                user,
                projectLabel,
                recentEvents(),
                history(),
                boards,
                jiraService.usesBoards(),
                boardColumns);
    }

    private TaskView view(String key, String summary, String statusName, StatusCategory category, String assignee,
                          boolean mine, boolean inWorkingColumn, boolean timeAllowed, Optional<Instant> updated) {
        return new TaskView(key, summary, statusName, category, tracker.totalFor(key), tracker.isRunning(key),
                overrides.containsKey(key), tracker.runningSince(key), assignee, mine, inWorkingColumn, timeAllowed,
                updated);
    }

    /**
     * Task em destaque no painel: a que começou a contar por último; senão, a primeira do usuário em andamento.
     * Tasks que começaram no mesmo segundo desempatam pela ordem do Jira.
     */
    private static Optional<TaskView> featured(List<TaskView> tasks) {
        Optional<TaskView> latestRunning = tasks.stream()
                .filter(TaskView::running)
                .max(Comparator.comparing(task ->
                        task.runningSince().orElse(Instant.MIN).truncatedTo(ChronoUnit.SECONDS)));
        return latestRunning.or(() -> tasks.stream()
                .filter(task -> task.mine() && task.category() == StatusCategory.IN_PROGRESS)
                .findFirst());
    }

    /** Intervalos de hoje (gravados, encerrados e os que estão contando), do mais recente para o mais antigo. */
    private List<TimeEntry> history() {
        List<TimeEntry> entries = new ArrayList<>(storedToday);
        tracker.getCompletedEntries().stream()
                .filter(entry -> LocalDate.ofInstant(entry.startedAt(), zone).equals(day))
                .forEach(entries::add);
        Instant now = clock.instant();
        for (String key : tracker.runningKeys()) {
            tracker.runningSince(key).ifPresent(since ->
                    entries.add(new TimeEntry(key, since, now, Duration.between(since, now))));
        }
        entries.sort(Comparator.comparing(TimeEntry::startedAt).reversed());
        return List.copyOf(entries);
    }

    // ---- Gravação ------------------------------------------------------------------------------------

    /** Pausa a task e grava o intervalo encerrado. */
    private boolean closeInterval(String key) {
        Optional<TimeEntry> closed = tracker.pause(key);
        closed.ifPresent(this::persist);
        return closed.isPresent();
    }

    /** Grava o estado atual dos intervalos abertos; se o app cair, perde no máximo {@link #CHECKPOINT_INTERVAL}. */
    private void checkpoint(Instant now) {
        lastCheckpoint = now;
        for (String key : tracker.runningKeys()) {
            tracker.runningSince(key).ifPresent(since ->
                    persist(new TimeEntry(key, since, now, Duration.between(since, now))));
        }
        if (inactivityPause) {
            persistIdle(inactiveSince, now);
        }
    }

    private void persist(TimeEntry entry) {
        try {
            store.saveInterval(entry, summaries.getOrDefault(entry.issueKey(), ""));
            storageRecovered();
        } catch (HistoryStore.HistoryException e) {
            reportStorageFailure(e);
        }
    }

    private void persistIdle(Instant start, Instant end) {
        try {
            store.saveIdle(start, end);
            storageRecovered();
        } catch (HistoryStore.HistoryException e) {
            reportStorageFailure(e);
        }
    }

    private void reportStorageFailure(HistoryStore.HistoryException e) {
        if (!storageFailing) {
            storageFailing = true;
            addEvent(ActivityEvent.Kind.ERROR, I18n.t("Falha ao gravar o histórico"), String.valueOf(e.getMessage()));
        }
    }

    private void storageRecovered() {
        storageFailing = false;
    }

    private List<ActivityEvent> recentEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void addEvent(ActivityEvent.Kind kind, String title, String detail) {
        synchronized (events) {
            events.addFirst(new ActivityEvent(clock.instant(), kind, title, detail));
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    private String describeSync(List<JiraIssue> fetched) {
        long inProgress = fetched.stream().filter(working).count();
        return fetched.size() == 1
                ? I18n.t("{0} task, {1} em andamento", fetched.size(), inProgress)
                : I18n.t("{0} tasks, {1} em andamento", fetched.size(), inProgress);
    }

    private static String hoursMinutes(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private static String minutes(Duration duration) {
        long minutes = Math.max(1, duration.toMinutes());
        return minutes == 1 ? I18n.t("{0} minuto", minutes) : I18n.t("{0} minutos", minutes);
    }

    private static String normalize(String issueKey) {
        return issueKey == null ? "" : issueKey.strip().toUpperCase();
    }

    public record Snapshot(
            ActivityState activity,
            Duration idleTime,
            Optional<TaskView> featuredTask,
            List<TaskView> tasks,
            boolean pausedForInactivity,
            Duration activeToday,
            Duration inactiveToday,
            Duration manualToday,
            List<ManualEntry> manualEntries,
            JiraSyncStatus jiraStatus,
            Optional<String> jiraError,
            Optional<Instant> lastSync,
            Optional<JiraUser> user,
            Optional<String> projectLabel,
            List<ActivityEvent> recentEvents,
            List<TimeEntry> history,
            Map<String, String> boards,
            boolean byBoard,
            Map<String, List<JiraService.KanbanColumn>> boardColumns) {

        public Snapshot(ActivityState activity, Duration idleTime, Optional<TaskView> featuredTask, List<TaskView> tasks,
                        boolean pausedForInactivity, Duration activeToday, Duration inactiveToday, Duration manualToday,
                        List<ManualEntry> manualEntries, JiraSyncStatus jiraStatus, Optional<String> jiraError,
                        Optional<Instant> lastSync, Optional<JiraUser> user, Optional<String> projectLabel,
                        List<ActivityEvent> recentEvents, List<TimeEntry> history, Map<String, String> boards,
                        boolean byBoard) {
            this(activity, idleTime, featuredTask, tasks, pausedForInactivity, activeToday, inactiveToday, manualToday,
                    manualEntries, jiraStatus, jiraError, lastSync, user, projectLabel, recentEvents, history, boards,
                    byBoard, Map.of());
        }

        /**
         * Colunas do kanban para os quadros escolhidos (todos, sem escolha), na ordem dos quadros. Colunas com o
         * mesmo nome em quadros diferentes (sem contar maiúsculas e acentos) viram uma só, e a coluna que só um
         * quadro tem entra antes das colunas que vêm depois dela nesse quadro.
         */
        public List<JiraService.KanbanColumn> kanbanColumns(Set<String> selected) {
            List<String> order = new ArrayList<>();
            Map<String, String> names = new java.util.HashMap<>();
            Map<String, java.util.LinkedHashSet<String>> merged = new java.util.HashMap<>();
            boardColumns.forEach((board, columns) -> {
                if (selected.isEmpty() || !byBoard || selected.stream().anyMatch(board::equalsIgnoreCase)) {
                    List<String> keys = columns.stream().map(column -> columnKey(column.name())).toList();
                    for (int i = 0; i < columns.size(); i++) {
                        String key = keys.get(i);
                        if (!merged.containsKey(key)) {
                            order.add(insertAt(order, keys.subList(i + 1, keys.size())), key);
                            names.put(key, columns.get(i).name());
                        }
                        merged.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).addAll(columns.get(i).statuses());
                    }
                }
            });
            return order.stream()
                    .map(key -> new JiraService.KanbanColumn(names.get(key), List.copyOf(merged.get(key))))
                    .toList();
        }

        /** Posição de uma coluna nova: antes da primeira das colunas seguintes do mesmo quadro já na lista. */
        private static int insertAt(List<String> order, List<String> following) {
            return following.stream().mapToInt(order::indexOf).filter(index -> index >= 0).min().orElse(order.size());
        }

        /** Tempo do dia que conta para a meta: o relógio com tasks ligadas mais o inserido à mão. */
        public Duration workedToday() {
            return activeToday.plus(manualToday);
        }

        public long countByCategory(StatusCategory category) {
            return tasks.stream().filter(task -> task.category() == category).count();
        }

        public long runningCount() {
            return tasks.stream().filter(TaskView::running).count();
        }

        /**
         * Grupos em que a task aparece nos filtros: os quadros dela, com {@code JIRA_BOARDS}, ou o projeto da
         * chave. Vazio quando não se sabe (a task só aparece em "Todos").
         */
        public List<String> groupsOf(String issueKey) {
            if (!byBoard) {
                String project = Projects.of(issueKey);
                return project.isEmpty() ? List.of() : List.of(project);
            }
            String joined = boards.getOrDefault(issueKey, "");
            return joined.isEmpty() ? List.of() : List.of(joined.split(BOARD_SEPARATOR));
        }

        /** Grupos (quadros ou projetos) das tasks e dos registros de hoje, em ordem alfabética. */
        public List<String> projects() {
            java.util.TreeSet<String> groups = new java.util.TreeSet<>();
            tasks.forEach(task -> groups.addAll(groupsOf(task.key())));
            history.forEach(entry -> groups.addAll(groupsOf(entry.issueKey())));
            manualEntries.forEach(entry -> groups.addAll(groupsOf(entry.issueKey())));
            return List.copyOf(groups);
        }

        private boolean inGroup(String issueKey, Set<String> selected) {
            return Projects.matches(groupsOf(issueKey), selected);
        }

        /** O mesmo estado visto só de um projeto ou quadro; veja {@link #forGroups(Set)}. */
        public Snapshot forProject(String project) {
            return forGroups(project == null || project.isEmpty() ? Set.of() : Set.of(project));
        }

        /**
         * O mesmo estado visto só dos grupos escolhidos: tasks, registros e totais de hoje só deles. O tempo ocioso
         * é do PC e não muda. Sem grupos, devolve tudo.
         */
        public Snapshot forGroups(Set<String> selected) {
            if (selected == null || selected.isEmpty()) {
                return this;
            }
            List<TaskView> ownTasks = tasks.stream().filter(task -> inGroup(task.key(), selected)).toList();
            List<TimeEntry> ownHistory = history.stream().filter(entry -> inGroup(entry.issueKey(), selected)).toList();
            List<ManualEntry> ownManual = manualEntries.stream()
                    .filter(entry -> inGroup(entry.issueKey(), selected)).toList();
            return new Snapshot(activity, idleTime,
                    featuredTask.filter(task -> inGroup(task.key(), selected)).or(() -> featured(ownTasks)),
                    ownTasks, pausedForInactivity, Intervals.union(ownHistory), inactiveToday, sum(ownManual),
                    ownManual, jiraStatus, jiraError, lastSync, user,
                    Optional.of(String.join(", ", new java.util.TreeSet<>(selected))), recentEvents, ownHistory,
                    boards, byBoard, boardColumns);
        }
    }
}

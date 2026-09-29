package com.chronos.tracker.persistence;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.HistoryStore;
import com.chronos.tracker.tracking.ManualEntry;
import com.chronos.tracker.tracking.TaskComment;
import com.chronos.tracker.tracking.TimeEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Histórico gravado num arquivo SQLite local.
 *
 * <p>Cada intervalo é identificado pela task e pelo instante de início, e guarda também o dia (no fuso
 * do computador) em que começou, para buscas por data. Os instantes ficam em milissegundos desde 1970.
 */
public final class SqliteHistoryStore implements HistoryStore, AutoCloseable {

    private static final int SCHEMA_VERSION = 7;

    private final Connection connection;
    private final ZoneId zone;
    /** Jira cujo histórico este store lê e grava (o endereço configurado); vazio sem Jira. */
    private String site;

    public SqliteHistoryStore(Path file, ZoneId zone) throws HistoryException {
        this(file, zone, "");
    }

    /**
     * @param site endereço do Jira: o mesmo banco guarda o histórico de vários Jiras sem misturar as tasks
     *             (a SCRUM-1 de um não é a SCRUM-1 do outro)
     */
    public SqliteHistoryStore(Path file, ZoneId zone, String site) throws HistoryException {
        this.zone = zone;
        this.site = site == null ? "" : site;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            migrate();
        } catch (Exception e) {
            throw new HistoryException(I18n.t("Não foi possível abrir o histórico em {0}: {1}", file, e.getMessage()), e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version < 1) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS time_entry (
                            issue_key  TEXT    NOT NULL,
                            summary    TEXT    NOT NULL DEFAULT '',
                            started_at INTEGER NOT NULL,
                            ended_at   INTEGER NOT NULL,
                            seconds    INTEGER NOT NULL,
                            work_date  TEXT    NOT NULL,
                            PRIMARY KEY (issue_key, started_at)
                        )""");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS time_entry_date ON time_entry (work_date)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS idle_period (
                            started_at INTEGER PRIMARY KEY,
                            ended_at   INTEGER NOT NULL,
                            seconds    INTEGER NOT NULL,
                            work_date  TEXT    NOT NULL
                        )""");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idle_period_date ON idle_period (work_date)");
            }
            if (version < 2) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS manual_entry (
                            id         INTEGER PRIMARY KEY AUTOINCREMENT,
                            issue_key  TEXT    NOT NULL,
                            summary    TEXT    NOT NULL DEFAULT '',
                            work_date  TEXT    NOT NULL,
                            seconds    INTEGER NOT NULL,
                            note       TEXT    NOT NULL DEFAULT '',
                            created_at INTEGER NOT NULL
                        )""");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS manual_entry_date ON manual_entry (work_date)");
            }
            if (version < 3) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS alerted_issue (
                            issue_key  TEXT    PRIMARY KEY,
                            alerted_at INTEGER NOT NULL
                        )""");
            }
            if (version < 4) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS worklog (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            issue_key   TEXT    NOT NULL,
                            seconds     INTEGER NOT NULL,
                            logged_at   INTEGER NOT NULL,
                            worklog_id  TEXT    NOT NULL DEFAULT ''
                        )""");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS worklog_issue ON worklog (issue_key)");
            }
            if (version < 5) {
                // Até aqui não se sabia de qual Jira era cada registro: ficam com o site "" (sem Jira).
                for (String table : List.of("time_entry", "manual_entry", "worklog")) {
                    statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN jira_site TEXT NOT NULL DEFAULT ''");
                }
                statement.executeUpdate("""
                        CREATE TABLE alerted_issue_v5 (
                            jira_site  TEXT    NOT NULL DEFAULT '',
                            issue_key  TEXT    NOT NULL,
                            alerted_at INTEGER NOT NULL,
                            PRIMARY KEY (jira_site, issue_key)
                        )""");
                statement.executeUpdate("INSERT INTO alerted_issue_v5 (issue_key, alerted_at) "
                        + "SELECT issue_key, alerted_at FROM alerted_issue");
                statement.executeUpdate("DROP TABLE alerted_issue");
                statement.executeUpdate("ALTER TABLE alerted_issue_v5 RENAME TO alerted_issue");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS time_entry_site ON time_entry (jira_site, issue_key)");
            }
            if (version < 6) {
                // Quadro do Jira de cada task, para separar na tela os quadros do mesmo projeto.
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS issue_board (
                            jira_site  TEXT NOT NULL DEFAULT '',
                            issue_key  TEXT NOT NULL,
                            board      TEXT NOT NULL,
                            PRIMARY KEY (jira_site, issue_key)
                        )""");
            }
            if (version < 7) {
                // Comentário que o Chronos fez em cada task (o template ou o escrito pela pessoa).
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS task_comment (
                            jira_site   TEXT    NOT NULL DEFAULT '',
                            issue_key   TEXT    NOT NULL,
                            summary     TEXT    NOT NULL DEFAULT '',
                            comment_id  TEXT    NOT NULL DEFAULT '',
                            body        TEXT    NOT NULL,
                            kind        TEXT    NOT NULL,
                            updated_at  INTEGER NOT NULL,
                            PRIMARY KEY (jira_site, issue_key)
                        )""");
            }
            statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    @Override
    public synchronized void useSite(String site) {
        this.site = site == null ? "" : site;
    }

    @Override
    public synchronized void saveInterval(TimeEntry entry, String summary) throws HistoryException {
        String sql = """
                INSERT INTO time_entry (issue_key, summary, started_at, ended_at, seconds, work_date, jira_site)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (issue_key, started_at) DO UPDATE SET
                    ended_at = excluded.ended_at,
                    seconds = excluded.seconds,
                    summary = CASE WHEN excluded.summary = '' THEN time_entry.summary ELSE excluded.summary END""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.issueKey());
            statement.setString(2, summary == null ? "" : summary);
            statement.setLong(3, entry.startedAt().toEpochMilli());
            statement.setLong(4, entry.endedAt().toEpochMilli());
            statement.setLong(5, entry.activeTime().toSeconds());
            statement.setString(6, dayOf(entry.startedAt()));
            statement.setString(7, site);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar o intervalo de {0}", entry.issueKey()), e);
        }
    }

    @Override
    public synchronized void saveIdle(Instant start, Instant end) throws HistoryException {
        String sql = """
                INSERT INTO idle_period (started_at, ended_at, seconds, work_date) VALUES (?, ?, ?, ?)
                ON CONFLICT (started_at) DO UPDATE SET ended_at = excluded.ended_at, seconds = excluded.seconds""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, start.toEpochMilli());
            statement.setLong(2, end.toEpochMilli());
            statement.setLong(3, Duration.between(start, end).toSeconds());
            statement.setString(4, dayOf(start));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar o período ocioso"), e);
        }
    }

    @Override
    public synchronized Map<String, Duration> totalsByTask() throws HistoryException {
        Map<String, Duration> totals = new HashMap<>();
        String sql = """
                SELECT issue_key, SUM(millis) FROM (
                    SELECT issue_key, ended_at - started_at AS millis FROM time_entry WHERE jira_site = ?
                    UNION ALL
                    SELECT issue_key, seconds * 1000 FROM manual_entry WHERE jira_site = ?)
                GROUP BY issue_key""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                totals.put(rs.getString(1), Duration.ofMillis(rs.getLong(2)));
            }
            return totals;
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler os totais do histórico"), e);
        }
    }

    @Override
    public synchronized List<TaskTime> taskTimes() throws HistoryException {
        String sql = """
                SELECT w.issue_key,
                       COALESCE((SELECT t.summary FROM time_entry t
                                 WHERE t.jira_site = ?1 AND t.issue_key = w.issue_key AND t.summary <> ''
                                 ORDER BY t.started_at DESC LIMIT 1),
                                (SELECT m.summary FROM manual_entry m
                                 WHERE m.jira_site = ?1 AND m.issue_key = w.issue_key AND m.summary <> ''
                                 ORDER BY m.created_at DESC LIMIT 1), '') AS summary,
                       w.millis,
                       w.last_at,
                       COALESCE((SELECT SUM(l.seconds) FROM worklog l
                                 WHERE l.jira_site = ?1 AND l.issue_key = w.issue_key), 0) AS logged
                FROM (SELECT issue_key, SUM(millis) AS millis, MAX(last_at) AS last_at FROM (
                          SELECT issue_key, ended_at - started_at AS millis, ended_at AS last_at FROM time_entry
                          WHERE jira_site = ?1
                          UNION ALL
                          SELECT issue_key, seconds * 1000, created_at FROM manual_entry WHERE jira_site = ?1)
                      GROUP BY issue_key) w
                ORDER BY w.last_at DESC""";
        List<TaskTime> times = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                times.add(new TaskTime(
                        rs.getString(1),
                        rs.getString(2),
                        Duration.ofMillis(rs.getLong(3)),
                        Duration.ofSeconds(rs.getLong(5)),
                        Instant.ofEpochMilli(rs.getLong(4))));
            }
            return times;
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler o tempo das tasks"), e);
        }
    }

    @Override
    public synchronized void saveWorklog(String issueKey, Duration spent, Instant at, String worklogId)
            throws HistoryException {
        String sql = "INSERT INTO worklog (issue_key, seconds, logged_at, worklog_id, jira_site) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, issueKey);
            statement.setLong(2, spent.toSeconds());
            statement.setLong(3, at.toEpochMilli());
            statement.setString(4, worklogId == null ? "" : worklogId);
            statement.setString(5, site);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar o apontamento de {0}", issueKey), e);
        }
    }

    @Override
    public synchronized List<TaskComment> comments() throws HistoryException {
        List<TaskComment> comments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT issue_key, summary, comment_id, body, kind, updated_at FROM task_comment "
                        + "WHERE jira_site = ? ORDER BY updated_at DESC")) {
            statement.setString(1, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                comments.add(new TaskComment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        TaskComment.Kind.fromCode(rs.getString(5)), Instant.ofEpochMilli(rs.getLong(6))));
            }
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler os comentários"), e);
        }
        return comments;
    }

    @Override
    public synchronized void saveComment(TaskComment comment) throws HistoryException {
        String sql = "INSERT INTO task_comment (jira_site, issue_key, summary, comment_id, body, kind, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (jira_site, issue_key) DO UPDATE SET "
                + "summary = CASE WHEN excluded.summary <> '' THEN excluded.summary ELSE summary END, "
                + "comment_id = excluded.comment_id, body = excluded.body, kind = excluded.kind, "
                + "updated_at = excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, comment.issueKey());
            statement.setString(3, comment.summary() == null ? "" : comment.summary());
            statement.setString(4, comment.commentId() == null ? "" : comment.commentId());
            statement.setString(5, comment.body());
            statement.setString(6, comment.kind().code());
            statement.setLong(7, comment.updatedAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar o comentário de {0}", comment.issueKey()), e);
        }
    }

    @Override
    public synchronized Map<String, String> issueBoards() throws HistoryException {
        Map<String, String> boards = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT issue_key, board FROM issue_board WHERE jira_site = ?")) {
            statement.setString(1, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                boards.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler os quadros das tasks"), e);
        }
        return boards;
    }

    @Override
    public synchronized void saveIssueBoards(Map<String, String> boards) throws HistoryException {
        String sql = "INSERT INTO issue_board (jira_site, issue_key, board) VALUES (?, ?, ?) "
                + "ON CONFLICT (jira_site, issue_key) DO UPDATE SET board = excluded.board";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : boards.entrySet()) {
                statement.setString(1, site);
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar os quadros das tasks"), e);
        }
    }

    @Override
    public synchronized List<StoredEntry> entriesOn(LocalDate day) throws HistoryException {
        String sql = "SELECT issue_key, summary, started_at, ended_at FROM time_entry WHERE jira_site = ? AND work_date = ? "
                + "ORDER BY started_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, day.toString());
            return readEntries(statement);
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler o histórico de {0}", day), e);
        }
    }

    @Override
    public synchronized Duration idleOn(LocalDate day) throws HistoryException {
        String sql = "SELECT COALESCE(SUM(ended_at - started_at), 0) FROM idle_period WHERE work_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, day.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return Duration.ofMillis(rs.next() ? rs.getLong(1) : 0);
            }
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler o tempo ocioso de {0}", day), e);
        }
    }

    @Override
    public synchronized List<StoredEntry> search(String text, int limit) throws HistoryException {
        String sql = """
                SELECT e.issue_key, COALESCE(NULLIF(e.summary, ''), latest.summary, '') AS summary, e.started_at, e.ended_at
                FROM time_entry e
                LEFT JOIN (SELECT issue_key, summary FROM time_entry t
                           WHERE jira_site = ?1 AND summary <> ''
                             AND started_at = (SELECT MAX(started_at) FROM time_entry
                                               WHERE jira_site = ?1 AND issue_key = t.issue_key AND summary <> '')) latest
                       ON latest.issue_key = e.issue_key
                WHERE e.jira_site = ?1
                  AND (e.issue_key LIKE ?2 ESCAPE '\\'
                       OR COALESCE(NULLIF(e.summary, ''), latest.summary, '') LIKE ?2 ESCAPE '\\')
                ORDER BY e.started_at DESC
                LIMIT ?3""";
        String pattern = likePattern(text);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, pattern);
            statement.setInt(3, limit);
            return readEntries(statement);
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao buscar no histórico"), e);
        }
    }

    @Override
    public synchronized ManualEntry saveManual(ManualEntry entry) throws HistoryException {
        String sql = """
                INSERT INTO manual_entry (issue_key, summary, work_date, seconds, note, created_at, jira_site)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, entry.issueKey());
            statement.setString(2, entry.summary() == null ? "" : entry.summary());
            statement.setString(3, entry.day().toString());
            statement.setLong(4, entry.duration().toSeconds());
            statement.setString(5, entry.note() == null ? "" : entry.note());
            statement.setLong(6, entry.createdAt().toEpochMilli());
            statement.setString(7, site);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new ManualEntry(id, entry.issueKey(), entry.summary(), entry.day(), entry.duration(),
                        entry.note(), entry.createdAt());
            }
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar o tempo manual de {0}", entry.issueKey()), e);
        }
    }

    @Override
    public synchronized List<ManualEntry> manualOn(LocalDate day) throws HistoryException {
        String sql = "SELECT * FROM manual_entry WHERE jira_site = ? AND work_date = ? ORDER BY created_at, id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, day.toString());
            return readManual(statement);
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler o tempo manual de {0}", day), e);
        }
    }

    @Override
    public synchronized List<ManualEntry> searchManual(String text, int limit) throws HistoryException {
        String sql = """
                SELECT * FROM manual_entry
                WHERE jira_site = ?1
                  AND (issue_key LIKE ?2 ESCAPE '\\' OR summary LIKE ?2 ESCAPE '\\' OR note LIKE ?2 ESCAPE '\\')
                ORDER BY work_date DESC, created_at DESC
                LIMIT ?3""";
        String pattern = likePattern(text);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, site);
            statement.setString(2, pattern);
            statement.setInt(3, limit);
            return readManual(statement);
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao buscar no tempo manual"), e);
        }
    }

    private static List<ManualEntry> readManual(PreparedStatement statement) throws SQLException {
        List<ManualEntry> entries = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                entries.add(new ManualEntry(
                        rs.getLong("id"),
                        rs.getString("issue_key"),
                        rs.getString("summary"),
                        LocalDate.parse(rs.getString("work_date")),
                        Duration.ofSeconds(rs.getLong("seconds")),
                        rs.getString("note"),
                        Instant.ofEpochMilli(rs.getLong("created_at"))));
            }
        }
        return entries;
    }

    /** Texto para {@code LIKE '%texto%' ESCAPE '\'}, sem que % e _ digitados virem curingas. */
    private static String likePattern(String text) {
        return "%" + text.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    public synchronized Set<String> alertedKeys() throws HistoryException {
        Set<String> keys = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT issue_key FROM alerted_issue WHERE jira_site = ?")) {
            statement.setString(1, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
            return keys;
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler os avisos de task"), e);
        }
    }

    @Override
    public synchronized void markAlerted(Collection<String> issueKeys, Instant at) throws HistoryException {
        String sql = "INSERT OR IGNORE INTO alerted_issue (jira_site, issue_key, alerted_at) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String key : issueKeys) {
                statement.setString(1, site);
                statement.setString(2, key);
                statement.setLong(3, at.toEpochMilli());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao gravar os avisos de task"), e);
        }
    }

    @Override
    public synchronized Set<LocalDate> daysWithEntries() throws HistoryException {
        Set<LocalDate> days = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT work_date FROM time_entry WHERE jira_site = ?1 "
                        + "UNION SELECT work_date FROM manual_entry WHERE jira_site = ?1")) {
            statement.setString(1, site);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                days.add(LocalDate.parse(rs.getString(1)));
            }
            return days;
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Falha ao ler os dias do histórico"), e);
        }
    }

    private List<StoredEntry> readEntries(PreparedStatement statement) throws SQLException {
        List<StoredEntry> entries = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Instant start = Instant.ofEpochMilli(rs.getLong("started_at"));
                Instant end = Instant.ofEpochMilli(rs.getLong("ended_at"));
                entries.add(new StoredEntry(
                        new TimeEntry(rs.getString("issue_key"), start, end, Duration.between(start, end)),
                        rs.getString("summary")));
            }
        }
        return entries;
    }

    private String dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, zone).toString();
    }

    /** Cópia pelo próprio SQLite ({@code VACUUM INTO}): fica consistente mesmo com o app gravando. */
    @Override
    public synchronized void backupTo(Path target) throws HistoryException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new HistoryException(I18n.t("Não foi possível copiar o histórico para {0}", target), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Fechando o app: não há o que fazer.
        }
    }
}

package com.chronos.tracker.persistence;

import com.chronos.tracker.tracking.HistoryStore;
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

    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private final ZoneId zone;

    public SqliteHistoryStore(Path file, ZoneId zone) throws HistoryException {
        this.zone = zone;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            migrate();
        } catch (Exception e) {
            throw new HistoryException("Não foi possível abrir o histórico em " + file + ": " + e.getMessage(), e);
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
            statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    @Override
    public synchronized void saveInterval(TimeEntry entry, String summary) throws HistoryException {
        String sql = """
                INSERT INTO time_entry (issue_key, summary, started_at, ended_at, seconds, work_date)
                VALUES (?, ?, ?, ?, ?, ?)
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
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryException("Falha ao gravar o intervalo de " + entry.issueKey(), e);
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
            throw new HistoryException("Falha ao gravar o período ocioso", e);
        }
    }

    @Override
    public synchronized Map<String, Duration> totalsByTask() throws HistoryException {
        Map<String, Duration> totals = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT issue_key, SUM(ended_at - started_at) FROM time_entry GROUP BY issue_key")) {
            while (rs.next()) {
                totals.put(rs.getString(1), Duration.ofMillis(rs.getLong(2)));
            }
            return totals;
        } catch (SQLException e) {
            throw new HistoryException("Falha ao ler os totais do histórico", e);
        }
    }

    @Override
    public synchronized List<StoredEntry> entriesOn(LocalDate day) throws HistoryException {
        String sql = "SELECT issue_key, summary, started_at, ended_at FROM time_entry WHERE work_date = ? ORDER BY started_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, day.toString());
            return readEntries(statement);
        } catch (SQLException e) {
            throw new HistoryException("Falha ao ler o histórico de " + day, e);
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
            throw new HistoryException("Falha ao ler o tempo ocioso de " + day, e);
        }
    }

    @Override
    public synchronized List<StoredEntry> search(String text, int limit) throws HistoryException {
        String sql = """
                SELECT e.issue_key, COALESCE(NULLIF(e.summary, ''), latest.summary, '') AS summary, e.started_at, e.ended_at
                FROM time_entry e
                LEFT JOIN (SELECT issue_key, summary FROM time_entry t
                           WHERE summary <> '' AND started_at = (SELECT MAX(started_at) FROM time_entry
                                                                 WHERE issue_key = t.issue_key AND summary <> '')) latest
                       ON latest.issue_key = e.issue_key
                WHERE e.issue_key LIKE ? ESCAPE '\\' OR COALESCE(NULLIF(e.summary, ''), latest.summary, '') LIKE ? ESCAPE '\\'
                ORDER BY e.started_at DESC
                LIMIT ?""";
        String pattern = "%" + text.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setInt(3, limit);
            return readEntries(statement);
        } catch (SQLException e) {
            throw new HistoryException("Falha ao buscar no histórico", e);
        }
    }

    @Override
    public synchronized Set<LocalDate> daysWithEntries() throws HistoryException {
        Set<LocalDate> days = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DISTINCT work_date FROM time_entry")) {
            while (rs.next()) {
                days.add(LocalDate.parse(rs.getString(1)));
            }
            return days;
        } catch (SQLException e) {
            throw new HistoryException("Falha ao ler os dias do histórico", e);
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

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Fechando o app: não há o que fazer.
        }
    }
}

package com.investmentassistant.telegram;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TelegramSourceRepository {

    private static final String COLUMNS = """
            id, telegram_id, source_type, username, title, enabled,
            last_ingestion_at, created_at, updated_at
            """;
    private static final String SNAPSHOT_COLUMNS = """
            s.id, s.telegram_id, s.source_type, s.username, s.title, s.enabled,
            s.last_ingestion_at, s.created_at, s.updated_at,
            COUNT(m.id) AS message_count,
            MIN(m.published_at) AS first_message_at,
            MAX(m.published_at) AS last_message_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public TelegramSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TelegramSource add(String identifier) {
        String normalized = normalizeIdentifier(identifier);
        try {
            if (isNumeric(normalized)) {
                long telegramId = Long.parseLong(normalized);
                return jdbcTemplate.queryForObject("""
                        INSERT INTO telegram_source (telegram_id, source_type, title)
                        VALUES (?, 'CHANNEL', ?)
                        RETURNING %s
                        """.formatted(COLUMNS), this::map, telegramId, normalized);
            }
            return jdbcTemplate.queryForObject("""
                    INSERT INTO telegram_source (username, source_type, title)
                    VALUES (?, 'CHANNEL', ?)
                    RETURNING %s
                    """.formatted(COLUMNS), this::map, normalized, normalized);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("Telegram source is already configured: " + normalized, exception);
        }
    }

    public List<TelegramSource> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM telegram_source ORDER BY id", this::map);
    }

    public List<TelegramSource> findEnabled() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM telegram_source WHERE enabled = 1 ORDER BY id", this::map);
    }

    public Optional<TelegramSource> findById(long id) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM telegram_source WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<TelegramSource> findByTelegramId(long telegramId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM telegram_source WHERE telegram_id = ?", this::map, telegramId)
                .stream().findFirst();
    }

    public Optional<TelegramSource> findEnabledByTelegramId(long telegramId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM telegram_source WHERE telegram_id = ? AND enabled = 1",
                        this::map, telegramId)
                .stream().findFirst();
    }

    public boolean setEnabled(long id, boolean enabled) {
        return jdbcTemplate.update(
                "UPDATE telegram_source SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id) == 1;
    }

    public TelegramSource updateResolved(long id, ResolvedTelegramSource resolved) {
        return jdbcTemplate.queryForObject("""
                UPDATE telegram_source
                SET telegram_id = ?, source_type = ?, username = COALESCE(?, username), title = ?
                WHERE id = ?
                RETURNING %s
                """.formatted(COLUMNS), this::map,
                resolved.telegramId(), resolved.type().name(), resolved.username(), resolved.title(), id);
    }

    @Transactional
    public TelegramSource upsertResolved(ResolvedTelegramSource resolved) {
        Optional<TelegramSource> existing = findByTelegramId(resolved.telegramId());
        if (existing.isEmpty() && resolved.username() != null) {
            existing = findByUsername(resolved.username());
        }
        if (existing.isPresent()) {
            return jdbcTemplate.queryForObject("""
                    UPDATE telegram_source
                    SET telegram_id = ?, source_type = ?, username = ?, title = ?, enabled = 1
                    WHERE id = ?
                    RETURNING %s
                    """.formatted(COLUMNS), this::map,
                    resolved.telegramId(), resolved.type().name(), resolved.username(), resolved.title(),
                    existing.get().id());
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO telegram_source (telegram_id, source_type, username, title, enabled)
                VALUES (?, ?, ?, ?, 1)
                RETURNING %s
                """.formatted(COLUMNS), this::map,
                resolved.telegramId(), resolved.type().name(), resolved.username(), resolved.title());
    }

    public void markIngested(long id, Instant timestamp) {
        jdbcTemplate.update("UPDATE telegram_source SET last_ingestion_at = ? WHERE id = ?", timestamp.toString(), id);
    }

    public List<TelegramSourceSnapshot> findSnapshots(Boolean enabled) {
        String filter = enabled == null ? "" : " WHERE s.enabled = ?";
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM telegram_source s "
                + "LEFT JOIN telegram_message m ON m.source_id = s.id" + filter
                + " GROUP BY s.id ORDER BY s.id";
        return enabled == null
                ? jdbcTemplate.query(sql, this::mapSnapshot)
                : jdbcTemplate.query(sql, this::mapSnapshot, enabled ? 1 : 0);
    }

    public Optional<TelegramSourceSnapshot> findSnapshotById(long id) {
        return findSnapshot("s.id = ?", id);
    }

    public Optional<TelegramSourceSnapshot> findSnapshotByTelegramId(long telegramId) {
        return findSnapshot("s.telegram_id = ?", telegramId);
    }

    public int countAll() {
        return count("SELECT COUNT(*) FROM telegram_source");
    }

    public int countEnabled() {
        return count("SELECT COUNT(*) FROM telegram_source WHERE enabled = 1");
    }

    private Optional<TelegramSource> findByUsername(String username) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM telegram_source WHERE username = ? COLLATE NOCASE",
                        this::map, username)
                .stream().findFirst();
    }

    private Optional<TelegramSourceSnapshot> findSnapshot(String condition, Object value) {
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM telegram_source s "
                + "LEFT JOIN telegram_message m ON m.source_id = s.id WHERE " + condition + " GROUP BY s.id";
        return jdbcTemplate.query(sql, this::mapSnapshot, value).stream().findFirst();
    }

    private int count(String sql) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
        return result == null ? 0 : result;
    }

    private TelegramSourceSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TelegramSourceSnapshot(
                map(resultSet, rowNumber),
                resultSet.getLong("message_count"),
                parseInstant(resultSet.getString("first_message_at")),
                parseInstant(resultSet.getString("last_message_at")));
    }

    private TelegramSource map(ResultSet resultSet, int rowNumber) throws SQLException {
        long telegramId = resultSet.getLong("telegram_id");
        return new TelegramSource(
                resultSet.getLong("id"),
                resultSet.wasNull() ? null : telegramId,
                TelegramSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getString("username"),
                resultSet.getString("title"),
                resultSet.getInt("enabled") == 1,
                parseInstant(resultSet.getString("last_ingestion_at")),
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("updated_at")));
    }

    private Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        return Instant.parse(value.contains("T") ? value : value.replace(' ', 'T') + "Z");
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Telegram source identifier must not be blank");
        }
        String normalized = identifier.trim();
        normalized = normalized.startsWith("@") ? normalized.substring(1) : normalized;
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Telegram source identifier must not be blank");
        }
        return normalized;
    }

    private boolean isNumeric(String value) {
        return value.matches("-?\\d+");
    }
}

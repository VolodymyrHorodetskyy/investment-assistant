package com.investmentassistant.telegram;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramSourceRepository {

    private static final String COLUMNS = """
            id, telegram_id, username, title, enabled, created_at, updated_at
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
                        INSERT INTO telegram_source (telegram_id, title)
                        VALUES (?, ?)
                        RETURNING %s
                        """.formatted(COLUMNS), this::map, telegramId, normalized);
            }

            return jdbcTemplate.queryForObject("""
                    INSERT INTO telegram_source (username, title)
                    VALUES (?, ?)
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

    public Optional<TelegramSource> findEnabledByTelegramId(long telegramId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM telegram_source WHERE telegram_id = ? AND enabled = 1",
                        this::map,
                        telegramId)
                .stream()
                .findFirst();
    }

    public boolean setEnabled(long id, boolean enabled) {
        return jdbcTemplate.update(
                "UPDATE telegram_source SET enabled = ? WHERE id = ?",
                enabled ? 1 : 0,
                id) == 1;
    }

    public TelegramSource updateResolved(long id, ResolvedTelegramSource resolved) {
        return jdbcTemplate.queryForObject("""
                UPDATE telegram_source
                SET telegram_id = ?, username = COALESCE(?, username), title = ?
                WHERE id = ?
                RETURNING %s
                """.formatted(COLUMNS), this::map,
                resolved.telegramId(), resolved.username(), resolved.title(), id);
    }

    private TelegramSource map(ResultSet resultSet, int rowNumber) throws SQLException {
        long telegramId = resultSet.getLong("telegram_id");
        return new TelegramSource(
                resultSet.getLong("id"),
                resultSet.wasNull() ? null : telegramId,
                resultSet.getString("username"),
                resultSet.getString("title"),
                resultSet.getInt("enabled") == 1,
                Instant.parse(resultSet.getString("created_at").replace(' ', 'T') + "Z"),
                Instant.parse(resultSet.getString("updated_at").replace(' ', 'T') + "Z"));
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

package com.investmentassistant.telegram;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TelegramMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelegramMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean save(long sourceId, NormalizedTelegramMessage message) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO telegram_message (
                    source_id, telegram_message_id, published_at,
                    sender_telegram_id, sender_display_name, text, message_url
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, telegram_message_id) DO NOTHING
                """,
                sourceId, message.telegramMessageId(), message.publishedAt().toString(),
                message.senderTelegramId(), message.senderDisplayName(), message.text(), message.messageUrl());
        if (inserted == 0) {
            jdbcTemplate.update("""
                    UPDATE telegram_message
                    SET published_at = ?, sender_telegram_id = ?, sender_display_name = ?, text = ?, message_url = ?
                    WHERE source_id = ? AND telegram_message_id = ?
                    """,
                    message.publishedAt().toString(), message.senderTelegramId(), message.senderDisplayName(),
                    message.text(), message.messageUrl(), sourceId, message.telegramMessageId());
        }
        return inserted == 1;
    }

    public List<TelegramStoredMessage> findNewestBySource(long sourceId, int limit) {
        return jdbcTemplate.query("""
                SELECT telegram_message_id, published_at, sender_telegram_id,
                       sender_display_name, text, message_url
                FROM telegram_message
                WHERE source_id = ?
                ORDER BY published_at DESC, telegram_message_id DESC
                LIMIT ?
                """, this::map, sourceId, limit);
    }

    public int count() {
        Integer result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM telegram_message", Integer.class);
        return result == null ? 0 : result;
    }

    public int countPublishedSince(Instant since) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telegram_message WHERE published_at >= ?", Integer.class, since.toString());
        return result == null ? 0 : result;
    }

    public Instant findLastMessageAt() {
        String value = jdbcTemplate.queryForObject("SELECT MAX(published_at) FROM telegram_message", String.class);
        return value == null ? null : Instant.parse(value);
    }

    private TelegramStoredMessage map(ResultSet resultSet, int rowNumber) throws SQLException {
        long senderTelegramId = resultSet.getLong("sender_telegram_id");
        Long senderId = resultSet.wasNull() ? null : senderTelegramId;
        return new TelegramStoredMessage(
                resultSet.getLong("telegram_message_id"),
                Instant.parse(resultSet.getString("published_at")),
                senderId,
                resultSet.getString("sender_display_name"),
                resultSet.getString("text"),
                resultSet.getString("message_url"));
    }
}

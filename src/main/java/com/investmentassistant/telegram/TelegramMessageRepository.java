package com.investmentassistant.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelegramMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(long sourceId, NormalizedTelegramMessage message) {
        jdbcTemplate.update("""
                INSERT INTO telegram_message (
                    source_id, telegram_message_id, published_at, text, message_url
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (source_id, telegram_message_id) DO UPDATE SET
                    published_at = excluded.published_at,
                    text = excluded.text,
                    message_url = excluded.message_url
                """,
                sourceId,
                message.telegramMessageId(),
                message.publishedAt().toString(),
                message.text(),
                message.messageUrl());
    }

    public int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM telegram_message", Integer.class);
        return count == null ? 0 : count;
    }
}

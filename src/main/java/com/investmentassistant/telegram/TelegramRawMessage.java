package com.investmentassistant.telegram;

import java.time.Instant;

public record TelegramRawMessage(
        long telegramSourceId,
        long telegramMessageId,
        Instant publishedAt,
        String text,
        String caption,
        String messageUrl) {
}

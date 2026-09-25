package com.investmentassistant.telegram;

import java.time.Instant;

public record NormalizedTelegramMessage(
        long telegramSourceId,
        long telegramMessageId,
        Instant publishedAt,
        String text,
        String messageUrl) {
}

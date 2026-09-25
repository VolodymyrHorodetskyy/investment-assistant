package com.investmentassistant.telegram;

import java.time.Instant;

public record NormalizedTelegramMessage(
        long telegramSourceId,
        long telegramMessageId,
        Instant publishedAt,
        Long senderTelegramId,
        String senderDisplayName,
        String text,
        String messageUrl) {
}

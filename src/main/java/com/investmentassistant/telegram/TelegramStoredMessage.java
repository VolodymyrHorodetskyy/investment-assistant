package com.investmentassistant.telegram;

import java.time.Instant;

public record TelegramStoredMessage(
        long telegramMessageId,
        Instant publishedAt,
        Long senderTelegramId,
        String senderDisplayName,
        String text,
        String messageUrl) {
}

package com.investmentassistant.telegram;

import java.time.Instant;

public record TelegramSource(
        long id,
        Long telegramId,
        TelegramSourceType type,
        String username,
        String title,
        boolean enabled,
        Instant lastIngestionAt,
        Instant createdAt,
        Instant updatedAt) {
}

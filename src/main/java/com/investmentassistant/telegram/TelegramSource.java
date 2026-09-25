package com.investmentassistant.telegram;

import java.time.Instant;

public record TelegramSource(
        long id,
        Long telegramId,
        String username,
        String title,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}

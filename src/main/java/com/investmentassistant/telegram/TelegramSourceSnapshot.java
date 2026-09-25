package com.investmentassistant.telegram;

import java.time.Instant;

public record TelegramSourceSnapshot(
        TelegramSource source,
        long messageCount,
        Instant firstMessageAt,
        Instant lastMessageAt) {
}

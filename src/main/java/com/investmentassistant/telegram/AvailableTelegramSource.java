package com.investmentassistant.telegram;

public record AvailableTelegramSource(
        long telegramId,
        TelegramSourceType type,
        String title,
        String username) {
}

package com.investmentassistant.telegram;

import org.springframework.stereotype.Component;

@Component
public class TelegramMessageNormalizer {

    public NormalizedTelegramMessage normalize(TelegramRawMessage message) {
        String content = firstNonBlank(message.text(), message.caption());
        return new NormalizedTelegramMessage(
                message.telegramSourceId(),
                message.telegramMessageId(),
                message.publishedAt(),
                message.senderTelegramId(),
                blankToNull(message.senderDisplayName()),
                content,
                blankToNull(message.messageUrl()));
    }

    private String firstNonBlank(String primary, String fallback) {
        String value = blankToNull(primary);
        return value != null ? value : blankToNull(fallback);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

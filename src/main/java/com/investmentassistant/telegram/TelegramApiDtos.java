package com.investmentassistant.telegram;

import java.time.Instant;

public final class TelegramApiDtos {

    private TelegramApiDtos() {
    }

    public record SourceResponse(
            long id,
            Long telegramId,
            TelegramSourceType type,
            String title,
            String username,
            boolean enabled,
            long messageCount,
            Instant lastMessageAt) {

        public static SourceResponse from(TelegramSourceSnapshot snapshot) {
            TelegramSource source = snapshot.source();
            return new SourceResponse(
                    source.id(), source.telegramId(), source.type(), source.title(), source.username(),
                    source.enabled(), snapshot.messageCount(), snapshot.lastMessageAt());
        }
    }

    public record SourceDetailsResponse(
            long id,
            Long telegramId,
            TelegramSourceType type,
            String title,
            String username,
            boolean enabled,
            long messageCount,
            Instant firstMessageAt,
            Instant lastMessageAt,
            Instant lastIngestionAt,
            Instant createdAt) {

        public static SourceDetailsResponse from(TelegramSourceSnapshot snapshot) {
            TelegramSource source = snapshot.source();
            return new SourceDetailsResponse(
                    source.id(), source.telegramId(), source.type(), source.title(), source.username(),
                    source.enabled(), snapshot.messageCount(), snapshot.firstMessageAt(), snapshot.lastMessageAt(),
                    source.lastIngestionAt(), source.createdAt());
        }
    }

    public record AvailableSourceResponse(
            long telegramId,
            TelegramSourceType type,
            String title,
            String username,
            boolean monitored) {

        public static AvailableSourceResponse from(TelegramSourceService.AvailableSource available) {
            AvailableTelegramSource source = available.source();
            return new AvailableSourceResponse(
                    source.telegramId(), source.type(), source.title(), source.username(), available.monitored());
        }
    }

    public record AddSourceRequest(Long telegramId) {
    }

    public record AddSourceResponse(
            long id,
            long telegramId,
            TelegramSourceType type,
            String title,
            String username,
            boolean enabled,
            int historicalMessagesImported) {

        public static AddSourceResponse from(TelegramSourceService.MonitorResult result) {
            TelegramSource source = result.source();
            return new AddSourceResponse(
                    source.id(), source.telegramId(), source.type(), source.title(), source.username(),
                    source.enabled(), result.historicalMessagesImported());
        }
    }

    public record UpdateSourceRequest(Boolean enabled) {
    }

    public record MessageResponse(
            long telegramMessageId,
            Instant publishedAt,
            Long senderTelegramId,
            String senderDisplayName,
            String text,
            String messageUrl) {

        public static MessageResponse from(TelegramStoredMessage message) {
            return new MessageResponse(
                    message.telegramMessageId(), message.publishedAt(), message.senderTelegramId(),
                    message.senderDisplayName(), message.text(), message.messageUrl());
        }
    }

    public record StatsResponse(
            TelegramConnectionState telegramStatus,
            int availableSources,
            int monitoredSources,
            int enabledSources,
            int messagesStored,
            int messagesStoredToday,
            Instant lastMessageAt) {

        public static StatsResponse from(TelegramSourceService.TelegramIngestionStats stats) {
            return new StatsResponse(
                    stats.telegramStatus(), stats.availableSources(), stats.monitoredSources(), stats.enabledSources(),
                    stats.messagesStored(), stats.messagesStoredToday(), stats.lastMessageAt());
        }
    }

    public record ErrorResponse(String code, String message) {
    }
}

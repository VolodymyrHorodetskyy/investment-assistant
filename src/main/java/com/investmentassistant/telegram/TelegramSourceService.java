package com.investmentassistant.telegram;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.investmentassistant.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TelegramSourceService {

    public static final int DEFAULT_MESSAGE_LIMIT = 20;
    public static final int MAX_MESSAGE_LIMIT = 200;
    public static final int MAX_HISTORY_LIMIT = 1000;

    private final TelegramSourceRepository sourceRepository;
    private final TelegramMessageRepository messageRepository;
    private final TelegramMessageNormalizer normalizer;
    private final ObjectProvider<TelegramClientGateway> clientProvider;
    private final TelegramStatus telegramStatus;
    private final int defaultHistoryLimit;
    private final Clock clock;
    private final AtomicInteger availableSourceCount = new AtomicInteger();

    @Autowired
    public TelegramSourceService(
            TelegramSourceRepository sourceRepository,
            TelegramMessageRepository messageRepository,
            TelegramMessageNormalizer normalizer,
            ObjectProvider<TelegramClientGateway> clientProvider,
            TelegramStatus telegramStatus,
            AppProperties properties) {
        this(sourceRepository, messageRepository, normalizer, clientProvider, telegramStatus,
                properties.telegram().historyLimit(), Clock.systemUTC());
    }

    TelegramSourceService(
            TelegramSourceRepository sourceRepository,
            TelegramMessageRepository messageRepository,
            TelegramMessageNormalizer normalizer,
            ObjectProvider<TelegramClientGateway> clientProvider,
            TelegramStatus telegramStatus,
            int defaultHistoryLimit,
            Clock clock) {
        this.sourceRepository = sourceRepository;
        this.messageRepository = messageRepository;
        this.normalizer = normalizer;
        this.clientProvider = clientProvider;
        this.telegramStatus = telegramStatus;
        this.defaultHistoryLimit = defaultHistoryLimit;
        this.clock = clock;
    }

    public List<TelegramSourceSnapshot> listSources(Boolean enabled) {
        return sourceRepository.findSnapshots(enabled);
    }

    public TelegramSourceSnapshot getSource(long id) {
        return sourceRepository.findSnapshotById(id).orElseThrow(() -> sourceNotFound(id));
    }

    public List<AvailableSource> listAvailableSources() {
        TelegramClientGateway client = requireConnectedClient();
        List<AvailableTelegramSource> available = join(
                client.listAvailableSources(), "TELEGRAM_REQUEST_FAILED", "Could not load Telegram sources");
        availableSourceCount.set(available.size());
        Set<Long> monitored = sourceRepository.findAll().stream()
                .filter(source -> source.telegramId() != null)
                .map(TelegramSource::telegramId)
                .collect(java.util.stream.Collectors.toSet());
        return available.stream()
                .map(source -> new AvailableSource(source, monitored.contains(source.telegramId())))
                .toList();
    }

    public MonitorResult monitor(long telegramId, Integer requestedHistoryLimit) {
        int historyLimit = validateHistoryLimit(requestedHistoryLimit);
        TelegramClientGateway client = requireConnectedClient();
        ResolvedTelegramSource resolved = join(
                client.resolve(telegramId), "SOURCE_NOT_AVAILABLE", "Telegram source is not accessible");
        TelegramSource source = sourceRepository.upsertResolved(resolved);
        List<TelegramRawMessage> history = join(
                client.loadRecentMessages(resolved.telegramId(), historyLimit),
                "TELEGRAM_REQUEST_FAILED",
                "Could not load Telegram message history");
        int inserted = 0;
        for (TelegramRawMessage message : history) {
            if (messageRepository.save(source.id(), normalizer.normalize(message))) {
                inserted++;
            }
        }
        sourceRepository.markIngested(source.id(), clock.instant());
        TelegramSource refreshed = sourceRepository.findById(source.id()).orElseThrow();
        return new MonitorResult(refreshed, inserted);
    }

    public TelegramSourceSnapshot setEnabled(long id, Boolean enabled) {
        if (enabled == null) {
            throw invalidRequest("Field 'enabled' is required");
        }
        TelegramSource source = sourceRepository.findById(id).orElseThrow(() -> sourceNotFound(id));
        if (!enabled) {
            sourceRepository.setEnabled(id, false);
            return getSource(id);
        }
        if (source.telegramId() == null) {
            throw new TelegramApiException(
                    HttpStatus.CONFLICT, "SOURCE_NOT_RESOLVED", "Telegram source has not been resolved yet");
        }
        monitor(source.telegramId(), defaultHistoryLimit);
        return getSource(id);
    }

    public List<TelegramStoredMessage> getMessagesBySource(long sourceId, Integer requestedLimit) {
        int limit = validateMessageLimit(requestedLimit);
        sourceRepository.findById(sourceId).orElseThrow(() -> sourceNotFound(sourceId));
        return messageRepository.findNewestBySource(sourceId, limit);
    }

    public List<TelegramStoredMessage> getMessagesByTelegramId(long telegramId, Integer requestedLimit) {
        int limit = validateMessageLimit(requestedLimit);
        TelegramSource source = sourceRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new TelegramApiException(
                        HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "Telegram source is not monitored"));
        return messageRepository.findNewestBySource(source.id(), limit);
    }

    public TelegramIngestionStats stats() {
        Instant today = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new TelegramIngestionStats(
                telegramStatus.get(),
                availableSourceCount.get(),
                sourceRepository.countAll(),
                sourceRepository.countEnabled(),
                messageRepository.count(),
                messageRepository.countPublishedSince(today),
                messageRepository.findLastMessageAt());
    }

    private TelegramClientGateway requireConnectedClient() {
        TelegramClientGateway client = clientProvider.getIfAvailable();
        if (client == null || telegramStatus.get() != TelegramConnectionState.CONNECTED) {
            throw new TelegramApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TELEGRAM_NOT_CONNECTED",
                    "Telegram client is not currently connected");
        }
        return client;
    }

    private int validateHistoryLimit(Integer requested) {
        int limit = requested == null ? defaultHistoryLimit : requested;
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new TelegramApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "historyLimit must be between 1 and " + MAX_HISTORY_LIMIT);
        }
        return limit;
    }

    private int validateMessageLimit(Integer requested) {
        int limit = requested == null ? DEFAULT_MESSAGE_LIMIT : requested;
        if (limit < 1 || limit > MAX_MESSAGE_LIMIT) {
            throw new TelegramApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "limit must be between 1 and " + MAX_MESSAGE_LIMIT);
        }
        return limit;
    }

    private <T> T join(java.util.concurrent.CompletableFuture<T> future, String code, String message) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            throw new TelegramApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
        }
    }

    private TelegramApiException sourceNotFound(long id) {
        return new TelegramApiException(
                HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "Telegram source was not found: " + id);
    }

    private TelegramApiException invalidRequest(String message) {
        return new TelegramApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public record AvailableSource(AvailableTelegramSource source, boolean monitored) {
    }

    public record MonitorResult(TelegramSource source, int historicalMessagesImported) {
    }

    public record TelegramIngestionStats(
            TelegramConnectionState telegramStatus,
            int availableSources,
            int monitoredSources,
            int enabledSources,
            int messagesStored,
            int messagesStoredToday,
            Instant lastMessageAt) {
    }
}

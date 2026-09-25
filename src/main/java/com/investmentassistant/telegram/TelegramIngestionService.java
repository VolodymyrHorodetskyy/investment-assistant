package com.investmentassistant.telegram;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

public class TelegramIngestionService implements TelegramClientGateway.Listener, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TelegramIngestionService.class);

    private final TelegramClientGateway client;
    private final TelegramSourceRepository sourceRepository;
    private final TelegramMessageRepository messageRepository;
    private final TelegramMessageNormalizer normalizer;
    private final TelegramStatus status;
    private final int historyLimit;
    private final ExecutorService ingestionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telegram-ingestion");
        thread.setDaemon(true);
        return thread;
    });

    public TelegramIngestionService(
            TelegramClientGateway client,
            TelegramSourceRepository sourceRepository,
            TelegramMessageRepository messageRepository,
            TelegramMessageNormalizer normalizer,
            TelegramStatus status,
            int historyLimit) {
        this.client = client;
        this.sourceRepository = sourceRepository;
        this.messageRepository = messageRepository;
        this.normalizer = normalizer;
        this.status = status;
        this.historyLimit = historyLimit;
    }

    public void start() {
        status.set(TelegramConnectionState.CONNECTING);
        client.start(this);
    }

    @Override
    public void onStateChanged(TelegramConnectionState state) {
        status.set(state);
        if (state == TelegramConnectionState.CONNECTED) {
            log.info("Telegram authenticated");
            ingestionExecutor.execute(this::importEnabledSources);
        }
    }

    @Override
    public void onMessage(TelegramRawMessage message) {
        ingestionExecutor.execute(() -> persistIfEnabled(message));
    }

    private void importEnabledSources() {
        for (TelegramSource configuredSource : sourceRepository.findEnabled()) {
            try {
                ResolvedTelegramSource resolved = client.resolve(configuredSource).join();
                TelegramSource source = sourceRepository.updateResolved(configuredSource.id(), resolved);
                log.info("Telegram source loaded: id={}, title={}", source.id(), source.title());
                log.info("Historical import started: sourceId={}, limit={}", source.id(), historyLimit);
                List<TelegramRawMessage> messages = client
                        .loadRecentMessages(resolved.telegramId(), historyLimit)
                        .join();
                messages.forEach(message -> persist(source.id(), message));
                sourceRepository.markIngested(source.id(), java.time.Instant.now());
                log.info("Historical import completed: sourceId={}, messages={}", source.id(), messages.size());
            } catch (RuntimeException exception) {
                log.error("Telegram source import failed: sourceId={}, reason={}",
                        configuredSource.id(), rootMessage(exception));
            }
        }
    }

    private void persistIfEnabled(TelegramRawMessage message) {
        sourceRepository.findEnabledByTelegramId(message.telegramSourceId())
                .ifPresent(source -> persist(source.id(), message));
    }

    private void persist(long sourceId, TelegramRawMessage rawMessage) {
        NormalizedTelegramMessage normalized = normalizer.normalize(rawMessage);
        messageRepository.save(sourceId, normalized);
        sourceRepository.markIngested(sourceId, java.time.Instant.now());
        log.info("Telegram message persisted: sourceId={}, telegramMessageId={}",
                sourceId, normalized.telegramMessageId());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void destroy() throws Exception {
        ingestionExecutor.shutdownNow();
        client.close();
    }
}

package com.investmentassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import com.investmentassistant.telegram.NormalizedTelegramMessage;
import com.investmentassistant.telegram.ResolvedTelegramSource;
import com.investmentassistant.telegram.TelegramClientGateway;
import com.investmentassistant.telegram.TelegramConnectionState;
import com.investmentassistant.telegram.TelegramIngestionService;
import com.investmentassistant.telegram.TelegramMessageNormalizer;
import com.investmentassistant.telegram.TelegramMessageRepository;
import com.investmentassistant.telegram.TelegramRawMessage;
import com.investmentassistant.telegram.TelegramSource;
import com.investmentassistant.telegram.TelegramSourceRepository;
import com.investmentassistant.telegram.TelegramStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class TelegramStageTwoIntegrationTests {

    private static final String DATABASE_PATH = System.getProperty("java.io.tmpdir")
            + "/investment-assistant-telegram-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.database.path", () -> DATABASE_PATH);
        registry.add("app.telegram.enabled", () -> "false");
    }

    @Autowired
    private TelegramSourceRepository sourceRepository;

    @Autowired
    private TelegramMessageRepository messageRepository;

    @Autowired
    private TelegramMessageNormalizer normalizer;

    @Autowired
    private TelegramStatus status;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TelegramIngestionService ingestionService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM telegram_message");
        jdbcTemplate.update("DELETE FROM telegram_source");
        status.set(TelegramConnectionState.DISABLED);
    }

    @AfterEach
    void stopIngestionService() throws Exception {
        if (ingestionService != null) {
            ingestionService.destroy();
        }
    }

    @Test
    void sourceCanBeAddedDisabledAndEnabled() {
        TelegramSource source = sourceRepository.add("@market_news");

        assertThat(source.username()).isEqualTo("market_news");
        assertThat(source.enabled()).isTrue();
        assertThat(sourceRepository.setEnabled(source.id(), false)).isTrue();
        assertThat(sourceRepository.findEnabled()).isEmpty();
        assertThat(sourceRepository.setEnabled(source.id(), true)).isTrue();
        assertThat(sourceRepository.findEnabled()).extracting(TelegramSource::id).containsExactly(source.id());
    }

    @Test
    void duplicateMessageIsUpdatedWithoutCreatingAnotherRow() {
        TelegramSource source = sourceRepository.add("-1001234567890");
        Instant publishedAt = Instant.parse("2026-09-25T10:15:30Z");

        messageRepository.save(source.id(), new NormalizedTelegramMessage(
                source.telegramId(), 42, publishedAt, null, null, "Original", "https://t.me/example/42"));
        messageRepository.save(source.id(), new NormalizedTelegramMessage(
                source.telegramId(), 42, publishedAt, null, null, "Edited", "https://t.me/example/42"));

        assertThat(messageRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT text FROM telegram_message WHERE source_id = ? AND telegram_message_id = ?",
                String.class,
                source.id(),
                42L)).isEqualTo("Edited");
    }

    @Test
    void normalizerUsesTextThenCaptionAndAllowsEmptyMediaMessages() {
        Instant publishedAt = Instant.parse("2026-09-25T10:15:30Z");

        assertThat(normalizer.normalize(new TelegramRawMessage(
                1, 1, publishedAt, null, null, "text", "caption", null)).text())
                .isEqualTo("text");
        assertThat(normalizer.normalize(new TelegramRawMessage(
                1, 2, publishedAt, null, null, null, "caption", null)).text())
                .isEqualTo("caption");
        assertThat(normalizer.normalize(new TelegramRawMessage(
                1, 3, publishedAt, null, null, null, null, null)).text())
                .isNull();
    }

    @Test
    void historicalImportIsIdempotent() throws Exception {
        TelegramSource source = sourceRepository.add("@market_news");
        FakeTelegramClient client = new FakeTelegramClient();
        ingestionService = new TelegramIngestionService(
                client, sourceRepository, messageRepository, normalizer, status, 100);

        ingestionService.start();
        await(() -> messageRepository.count() == 2);
        client.connectAgain();
        await(() -> client.historyRequestCount == 2);
        client.emit(3, "real time");
        await(() -> messageRepository.count() == 3);

        assertThat(messageRepository.count()).isEqualTo(3);
        assertThat(status.get()).isEqualTo(TelegramConnectionState.CONNECTED);
        assertThat(sourceRepository.findEnabledByTelegramId(client.telegramId))
                .get()
                .extracting(TelegramSource::id)
                .isEqualTo(source.id());
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class FakeTelegramClient implements TelegramClientGateway {

        private final long telegramId = -1001234567890L;
        private Listener listener;
        private volatile int historyRequestCount;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
            listener.onStateChanged(TelegramConnectionState.CONNECTED);
        }

        void connectAgain() {
            listener.onStateChanged(TelegramConnectionState.CONNECTED);
        }

        void emit(long messageId, String text) {
            listener.onMessage(new TelegramRawMessage(
                    telegramId, messageId, Instant.parse("2026-09-25T10:20:30Z"),
                    123L, "Test Sender", text, null, null));
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(TelegramSource source) {
            return CompletableFuture.completedFuture(
                    new ResolvedTelegramSource(
                            telegramId, com.investmentassistant.telegram.TelegramSourceType.CHANNEL,
                            "market_news", "Market News"));
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(long telegramId) {
            return CompletableFuture.completedFuture(new ResolvedTelegramSource(
                    telegramId, com.investmentassistant.telegram.TelegramSourceType.CHANNEL,
                    "market_news", "Market News"));
        }

        @Override
        public CompletableFuture<List<com.investmentassistant.telegram.AvailableTelegramSource>>
                listAvailableSources() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit) {
            historyRequestCount++;
            Instant publishedAt = Instant.parse("2026-09-25T10:15:30Z");
            return CompletableFuture.completedFuture(List.of(
                    new TelegramRawMessage(
                            telegramSourceId, 1, publishedAt, 123L, "Test Sender", "one", null, null),
                    new TelegramRawMessage(
                            telegramSourceId, 2, publishedAt, 123L, "Test Sender", null, "two", null)));
        }

        @Override
        public void close() {
        }
    }
}

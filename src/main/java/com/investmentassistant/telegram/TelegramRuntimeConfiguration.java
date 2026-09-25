package com.investmentassistant.telegram;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.investmentassistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelegramRuntimeConfiguration.class);

    @Bean
    TelegramClientGateway telegramClientGateway(AppProperties properties) {
        if (!properties.telegram().hasRequiredCredentials()) {
            log.warn("Telegram integration is enabled but required credentials are incomplete; integration remains disabled");
            return new DisabledTelegramClientGateway();
        }
        return new TdLightTelegramClientGateway(properties.telegram());
    }

    @Bean
    TelegramIngestionService telegramIngestionService(
            TelegramClientGateway client,
            TelegramSourceRepository sourceRepository,
            TelegramMessageRepository messageRepository,
            TelegramMessageNormalizer normalizer,
            TelegramStatus status,
            AppProperties properties) {
        return new TelegramIngestionService(
                client,
                sourceRepository,
                messageRepository,
                normalizer,
                status,
                properties.telegram().historyLimit());
    }

    @Bean
    TelegramStartup telegramStartup(TelegramIngestionService ingestionService) {
        return new TelegramStartup(ingestionService);
    }

    private static final class DisabledTelegramClientGateway implements TelegramClientGateway {

        @Override
        public void start(Listener listener) {
            listener.onStateChanged(TelegramConnectionState.DISABLED);
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(TelegramSource source) {
            return CompletableFuture.failedFuture(new IllegalStateException("Telegram credentials are incomplete"));
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(long telegramId) {
            return CompletableFuture.failedFuture(new IllegalStateException("Telegram credentials are incomplete"));
        }

        @Override
        public CompletableFuture<List<AvailableTelegramSource>> listAvailableSources() {
            return CompletableFuture.failedFuture(new IllegalStateException("Telegram credentials are incomplete"));
        }

        @Override
        public CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit) {
            return CompletableFuture.failedFuture(new IllegalStateException("Telegram credentials are incomplete"));
        }

        @Override
        public void close() {
        }
    }
}

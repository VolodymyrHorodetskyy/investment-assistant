package com.investmentassistant.telegram;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TelegramClientGateway extends AutoCloseable {

    void start(Listener listener);

    CompletableFuture<ResolvedTelegramSource> resolve(TelegramSource source);

    CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit);

    interface Listener {

        void onStateChanged(TelegramConnectionState state);

        void onMessage(TelegramRawMessage message);
    }
}

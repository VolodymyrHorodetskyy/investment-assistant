package com.investmentassistant.telegram;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class TelegramStatus {

    private final AtomicReference<TelegramConnectionState> state =
            new AtomicReference<>(TelegramConnectionState.DISABLED);

    public TelegramConnectionState get() {
        return state.get();
    }

    public void set(TelegramConnectionState state) {
        this.state.set(state);
    }
}

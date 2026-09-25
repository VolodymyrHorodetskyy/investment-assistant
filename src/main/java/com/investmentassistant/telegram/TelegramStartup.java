package com.investmentassistant.telegram;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

public class TelegramStartup {

    private final TelegramIngestionService ingestionService;

    public TelegramStartup(TelegramIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ingestionService.start();
    }
}

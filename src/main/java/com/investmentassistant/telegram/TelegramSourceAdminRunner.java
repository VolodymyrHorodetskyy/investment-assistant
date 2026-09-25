package com.investmentassistant.telegram;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TelegramSourceAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramSourceAdminRunner.class);

    private final TelegramAdminProperties properties;
    private final TelegramSourceRepository repository;
    private final TelegramMessageRepository messageRepository;

    public TelegramSourceAdminRunner(
            TelegramAdminProperties properties,
            TelegramSourceRepository repository,
            TelegramMessageRepository messageRepository) {
        this.properties = properties;
        this.repository = repository;
        this.messageRepository = messageRepository;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.command() == null || properties.command().isBlank()) {
            return;
        }

        switch (properties.command().trim().toLowerCase(Locale.ROOT)) {
            case "list" -> repository.findAll().forEach(source -> log.info(
                    "Telegram source: id={}, telegramId={}, username={}, title={}, enabled={}",
                    source.id(), source.telegramId(), source.username(), source.title(), source.enabled()));
            case "add" -> {
                TelegramSource source = repository.add(requireSource());
                log.info("Telegram source added: id={}, username={}, enabled={}",
                        source.id(), source.username(), source.enabled());
            }
            case "enable" -> setEnabled(true);
            case "disable" -> setEnabled(false);
            case "message-count" -> log.info("Persisted Telegram messages: {}", messageRepository.count());
            default -> throw new IllegalArgumentException(
                    "Unknown Telegram admin command. Use list, add, enable, disable, or message-count");
        }
    }

    private String requireSource() {
        if (properties.source() == null || properties.source().isBlank()) {
            throw new IllegalArgumentException("app.telegram.admin.source is required for add");
        }
        return properties.source();
    }

    private void setEnabled(boolean enabled) {
        if (properties.sourceId() == null) {
            throw new IllegalArgumentException("app.telegram.admin.source-id is required");
        }
        if (!repository.setEnabled(properties.sourceId(), enabled)) {
            throw new IllegalArgumentException("Telegram source was not found: " + properties.sourceId());
        }
        log.info("Telegram source updated: id={}, enabled={}", properties.sourceId(), enabled);
    }
}

package com.investmentassistant.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.telegram.admin")
public record TelegramAdminProperties(String command, String source, Long sourceId) {
}

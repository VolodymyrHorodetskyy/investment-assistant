package com.investmentassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        Database database,
        Integration telegram,
        Integration openai,
        Integration ibkr,
        Integration marketData) {

    public record Database(String path) {
    }

    public record Integration(boolean enabled) {
    }
}

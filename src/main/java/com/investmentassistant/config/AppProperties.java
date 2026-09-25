package com.investmentassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        Database database,
        Telegram telegram,
        Integration openai,
        Integration ibkr,
        Integration marketData) {

    public record Database(String path) {
    }

    public record Telegram(
            boolean enabled,
            int apiId,
            String apiHash,
            String phone,
            String sessionPath,
            int historyLimit,
            AuthenticationMode authenticationMode) {

        public boolean hasRequiredCredentials() {
            return apiId > 0 && hasText(apiHash) && hasText(phone);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        @Override
        public String toString() {
            return "Telegram[enabled=" + enabled
                    + ", credentialsConfigured=" + hasRequiredCredentials()
                    + ", sessionPath=" + sessionPath
                    + ", historyLimit=" + historyLimit
                    + ", authenticationMode=" + authenticationMode + "]";
        }
    }

    public enum AuthenticationMode {
        RUNTIME,
        CONSOLE
    }

    public record Integration(boolean enabled) {
    }
}

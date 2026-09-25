package com.investmentassistant.telegram;

import org.springframework.http.HttpStatus;

public class TelegramApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public TelegramApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

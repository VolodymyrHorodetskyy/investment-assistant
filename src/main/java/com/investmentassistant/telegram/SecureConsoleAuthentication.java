package com.investmentassistant.telegram;

import java.io.Console;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.ParameterInfoNotifyLink;

final class SecureConsoleAuthentication implements ClientInteraction {

    private final Console console;

    SecureConsoleAuthentication() {
        this.console = System.console();
    }

    @Override
    public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo parameterInfo) {
        if (console == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Interactive Telegram authentication requires a real TTY"));
        }
        return CompletableFuture.supplyAsync(() -> read(parameter, parameterInfo));
    }

    private String read(InputParameter parameter, ParameterInfo parameterInfo) {
        return switch (parameter) {
            case ASK_CODE -> console.readLine("Telegram verification code: ").trim();
            case ASK_PASSWORD -> readPassword();
            case ASK_EMAIL_ADDRESS -> console.readLine("Telegram login email address: ").trim();
            case ASK_EMAIL_CODE -> console.readLine("Telegram email verification code: ").trim();
            case ASK_FIRST_NAME -> console.readLine("Telegram first name: ").trim();
            case ASK_LAST_NAME -> console.readLine("Telegram last name: ").trim();
            case TERMS_OF_SERVICE -> {
                console.readLine("Review Telegram terms in an official client, then press Enter to accept: ");
                yield "";
            }
            case NOTIFY_LINK -> {
                String link = ((ParameterInfoNotifyLink) parameterInfo).getLink();
                console.printf("Confirm this Telegram login link in an official client: %s%n", link);
                yield "";
            }
        };
    }

    private String readPassword() {
        char[] password = console.readPassword("Telegram 2FA password: ");
        if (password == null) {
            throw new IllegalStateException("Telegram 2FA password input was cancelled");
        }
        try {
            return new String(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}

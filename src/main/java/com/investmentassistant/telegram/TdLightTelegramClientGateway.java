package com.investmentassistant.telegram;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.investmentassistant.config.AppProperties;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdLightTelegramClientGateway implements TelegramClientGateway {

    private static final Logger log = LoggerFactory.getLogger(TdLightTelegramClientGateway.class);

    private final AppProperties.Telegram properties;
    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;
    private Listener listener;

    public TdLightTelegramClientGateway(AppProperties.Telegram properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void start(Listener listener) {
        if (client != null) {
            return;
        }
        this.listener = listener;
        listener.onStateChanged(TelegramConnectionState.CONNECTING);

        try {
            Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());
            APIToken apiToken = new APIToken(properties.apiId(), properties.apiHash());
            TDLibSettings settings = TDLibSettings.create(apiToken);
            Path sessionPath = Path.of(properties.sessionPath()).toAbsolutePath().normalize();
            settings.setDatabaseDirectoryPath(sessionPath.resolve("database"));
            settings.setDownloadedFilesDirectoryPath(sessionPath.resolve("files"));
            settings.setFileDatabaseEnabled(true);
            settings.setChatInfoDatabaseEnabled(true);
            settings.setMessageDatabaseEnabled(true);
            settings.setApplicationVersion("investment-assistant-stage-2");

            clientFactory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = clientFactory.builder(settings);
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationState);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> publish(update.message));
            builder.addUpdateHandler(TdApi.UpdateMessageContent.class, this::onEditedMessage);
            builder.addUpdateExceptionHandler(this::onError);
            builder.addDefaultExceptionHandler(this::onError);

            builder.setClientInteraction(properties.authenticationMode() == AppProperties.AuthenticationMode.CONSOLE
                    ? new SecureConsoleAuthentication()
                    : nonInteractiveAuthentication());

            client = builder.build(AuthenticationSupplier.user(properties.phone()));
        } catch (RuntimeException exception) {
            listener.onStateChanged(TelegramConnectionState.ERROR);
            throw exception;
        }
    }

    @Override
    public CompletableFuture<ResolvedTelegramSource> resolve(TelegramSource source) {
        CompletableFuture<TdApi.Chat> chatFuture = source.telegramId() != null
                ? client.send(new TdApi.GetChat(source.telegramId()))
                : client.send(new TdApi.SearchPublicChat(source.username()));

        return chatFuture.thenCompose(chat -> {
            if (!(chat.type instanceof TdApi.ChatTypeSupergroup supergroupType) || !supergroupType.isChannel) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Configured Telegram source is not a channel"));
            }
            return client.send(new TdApi.GetSupergroup(supergroupType.supergroupId))
                    .thenApply(supergroup -> new ResolvedTelegramSource(
                            chat.id,
                            primaryUsername(supergroup.usernames),
                            chat.title));
        });
    }

    @Override
    public CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return client.send(new TdApi.GetChatHistory(telegramSourceId, 0, 0, safeLimit, false))
                .thenCompose(messages -> {
                    List<CompletableFuture<TelegramRawMessage>> futures = Arrays.stream(messages.messages)
                            .filter(Objects::nonNull)
                            .map(this::toRawMessage)
                            .toList();
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
                });
    }

    private void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        TelegramConnectionState state;
        if (update.authorizationState instanceof TdApi.AuthorizationStateReady) {
            state = TelegramConnectionState.CONNECTED;
        } else if (update.authorizationState instanceof TdApi.AuthorizationStateWaitCode
                || update.authorizationState instanceof TdApi.AuthorizationStateWaitPassword
                || update.authorizationState instanceof TdApi.AuthorizationStateWaitEmailAddress
                || update.authorizationState instanceof TdApi.AuthorizationStateWaitEmailCode
                || update.authorizationState instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation
                || update.authorizationState instanceof TdApi.AuthorizationStateWaitRegistration) {
            state = TelegramConnectionState.AUTH_REQUIRED;
        } else if (update.authorizationState instanceof TdApi.AuthorizationStateClosed) {
            state = TelegramConnectionState.DISABLED;
        } else {
            state = TelegramConnectionState.CONNECTING;
        }
        listener.onStateChanged(state);
    }

    private ClientInteraction nonInteractiveAuthentication() {
        return (parameter, parameterInfo) -> {
            listener.onStateChanged(TelegramConnectionState.AUTH_REQUIRED);
            return new CompletableFuture<>();
        };
    }

    private void onEditedMessage(TdApi.UpdateMessageContent update) {
        client.send(new TdApi.GetMessage(update.chatId, update.messageId))
                .thenAccept(this::publish)
                .exceptionally(exception -> {
                    log.warn("Could not load edited Telegram message {} from chat {}: {}",
                            update.messageId, update.chatId, rootCause(exception).getMessage());
                    return null;
                });
    }

    private void publish(TdApi.Message message) {
        toRawMessage(message).thenAccept(listener::onMessage).exceptionally(exception -> {
            log.warn("Could not process Telegram message {} from chat {}: {}",
                    message.id, message.chatId, rootCause(exception).getMessage());
            return null;
        });
    }

    private CompletableFuture<TelegramRawMessage> toRawMessage(TdApi.Message message) {
        return client.send(new TdApi.GetMessageLink(message.chatId, message.id, 0, 0, "", false, false))
                .thenApply(link -> toRawMessage(message, link.link))
                .exceptionally(exception -> toRawMessage(message, null));
    }

    private TelegramRawMessage toRawMessage(TdApi.Message message, String messageUrl) {
        return new TelegramRawMessage(
                message.chatId,
                message.id,
                Instant.ofEpochSecond(message.date),
                text(message.content),
                caption(message.content),
                messageUrl);
    }

    private String text(TdApi.MessageContent content) {
        return content instanceof TdApi.MessageText messageText && messageText.text != null
                ? messageText.text.text
                : null;
    }

    private String caption(TdApi.MessageContent content) {
        TdApi.FormattedText caption = switch (content) {
            case TdApi.MessagePhoto value -> value.caption;
            case TdApi.MessageVideo value -> value.caption;
            case TdApi.MessageDocument value -> value.caption;
            case TdApi.MessageAnimation value -> value.caption;
            case TdApi.MessageAudio value -> value.caption;
            case TdApi.MessageVoiceNote value -> value.caption;
            default -> null;
        };
        return caption == null ? null : caption.text;
    }

    private String primaryUsername(TdApi.Usernames usernames) {
        if (usernames == null || usernames.activeUsernames == null || usernames.activeUsernames.length == 0) {
            return null;
        }
        return usernames.activeUsernames[0];
    }

    private void onError(Throwable throwable) {
        listener.onStateChanged(TelegramConnectionState.ERROR);
        log.error("Telegram client error: {}", rootCause(throwable).getMessage());
    }

    private Throwable rootCause(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }

    @Override
    public synchronized void close() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
        if (clientFactory != null) {
            clientFactory.close();
            clientFactory = null;
        }
    }
}

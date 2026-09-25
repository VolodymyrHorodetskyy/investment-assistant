package com.investmentassistant.telegram;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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
    private static final int EMPTY_HISTORY_RETRIES = 3;

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

        return chatFuture.thenCompose(this::resolveChat);
    }

    @Override
    public CompletableFuture<ResolvedTelegramSource> resolve(long telegramId) {
        return client.send(new TdApi.GetChat(telegramId)).thenCompose(this::resolveChat);
    }

    @Override
    public CompletableFuture<List<AvailableTelegramSource>> listAvailableSources() {
        CompletableFuture<List<TdApi.Chat>> main = loadChats(new TdApi.ChatListMain());
        CompletableFuture<List<TdApi.Chat>> archive = loadChats(new TdApi.ChatListArchive());
        return main.thenCombine(archive, (mainChats, archivedChats) -> {
                    Map<Long, TdApi.Chat> unique = new LinkedHashMap<>();
                    mainChats.forEach(chat -> unique.put(chat.id, chat));
                    archivedChats.forEach(chat -> unique.put(chat.id, chat));
                    return unique.values().stream().toList();
                })
                .thenCompose(chats -> {
                    List<CompletableFuture<AvailableTelegramSource>> futures = chats.stream()
                            .filter(this::isSupportedSource)
                            .map(this::toAvailableSource)
                            .toList();
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> futures.stream()
                                    .map(CompletableFuture::join)
                                    .sorted((left, right) -> left.title().compareToIgnoreCase(right.title()))
                                    .toList());
                });
    }

    private CompletableFuture<ResolvedTelegramSource> resolveChat(TdApi.Chat chat) {
        if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
            return CompletableFuture.completedFuture(new ResolvedTelegramSource(
                    chat.id, TelegramSourceType.GROUP, null, chat.title));
        }
        if (!(chat.type instanceof TdApi.ChatTypeSupergroup supergroupType)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Telegram chat is not a channel or group"));
        }

        return client.send(new TdApi.GetSupergroup(supergroupType.supergroupId))
                .thenApply(supergroup -> new ResolvedTelegramSource(
                        chat.id,
                        supergroupType.isChannel ? TelegramSourceType.CHANNEL : TelegramSourceType.SUPERGROUP,
                        primaryUsername(supergroup.usernames),
                        chat.title));
    }

    @Override
    public CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return loadHistoryPage(
                telegramSourceId, 0, safeLimit, new ArrayList<>(), new HashSet<>(), EMPTY_HISTORY_RETRIES);
    }

    private CompletableFuture<List<TelegramRawMessage>> loadHistoryPage(
            long chatId,
            long fromMessageId,
            int remaining,
            List<TelegramRawMessage> collected,
            Set<Long> seenMessageIds,
            int emptyRetriesRemaining) {
        int pageSize = Math.min(remaining + (fromMessageId == 0 ? 0 : 1), 100);
        return client.send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, pageSize, false))
                .thenCompose(messages -> {
                    List<TdApi.Message> page = Arrays.stream(messages.messages)
                            .filter(Objects::nonNull)
                            .filter(message -> seenMessageIds.add(message.id))
                            .toList();
                    if (page.isEmpty()) {
                        if (emptyRetriesRemaining > 0) {
                            return CompletableFuture.supplyAsync(
                                            () -> null,
                                            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS))
                                    .thenCompose(ignored -> loadHistoryPage(
                                            chatId,
                                            fromMessageId,
                                            remaining,
                                            collected,
                                            seenMessageIds,
                                            emptyRetriesRemaining - 1));
                        }
                        return CompletableFuture.completedFuture(List.copyOf(collected));
                    }
                    List<CompletableFuture<TelegramRawMessage>> futures = page.stream()
                            .map(this::toRawMessage)
                            .toList();
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenCompose(ignored -> {
                                futures.forEach(future -> collected.add(future.join()));
                                int nextRemaining = remaining - page.size();
                                if (nextRemaining <= 0) {
                                    return CompletableFuture.completedFuture(List.copyOf(collected));
                                }
                                return loadHistoryPage(
                                        chatId,
                                        page.getLast().id,
                                        nextRemaining,
                                        collected,
                                        seenMessageIds,
                                        EMPTY_HISTORY_RETRIES);
                            });
                });
    }

    private CompletableFuture<List<TdApi.Chat>> loadChats(TdApi.ChatList chatList) {
        return client.send(new TdApi.LoadChats(chatList, 1000))
                .handle((ignored, exception) -> null)
                .thenCompose(ignored -> client.send(new TdApi.GetChats(chatList, 1000)))
                .thenCompose(chats -> {
                    List<CompletableFuture<TdApi.Chat>> futures = Arrays.stream(chats.chatIds)
                            .mapToObj(chatId -> client.send(new TdApi.GetChat(chatId)))
                            .toList();
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
                });
    }

    private boolean isSupportedSource(TdApi.Chat chat) {
        return chat.type instanceof TdApi.ChatTypeBasicGroup
                || chat.type instanceof TdApi.ChatTypeSupergroup;
    }

    private CompletableFuture<AvailableTelegramSource> toAvailableSource(TdApi.Chat chat) {
        return resolveChat(chat).thenApply(resolved -> new AvailableTelegramSource(
                resolved.telegramId(), resolved.type(), resolved.title(), resolved.username()));
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
        CompletableFuture<String> link = client
                .send(new TdApi.GetMessageLink(message.chatId, message.id, 0, 0, "", false, false))
                .thenApply(result -> result.link)
                .exceptionally(exception -> null);
        CompletableFuture<SenderMetadata> sender = senderMetadata(message.senderId)
                .exceptionally(exception -> SenderMetadata.EMPTY);
        return link.thenCombine(sender, (messageUrl, senderMetadata) ->
                toRawMessage(message, messageUrl, senderMetadata));
    }

    private TelegramRawMessage toRawMessage(
            TdApi.Message message, String messageUrl, SenderMetadata senderMetadata) {
        return new TelegramRawMessage(
                message.chatId,
                message.id,
                Instant.ofEpochSecond(message.date),
                senderMetadata.telegramId(),
                senderMetadata.displayName(),
                text(message.content),
                caption(message.content),
                messageUrl);
    }

    private CompletableFuture<SenderMetadata> senderMetadata(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser userSender) {
            return client.send(new TdApi.GetUser(userSender.userId))
                    .thenApply(user -> new SenderMetadata(user.id, displayName(user.firstName, user.lastName)));
        }
        if (sender instanceof TdApi.MessageSenderChat chatSender) {
            return client.send(new TdApi.GetChat(chatSender.chatId))
                    .thenApply(chat -> new SenderMetadata(chat.id, chat.title));
        }
        return CompletableFuture.completedFuture(SenderMetadata.EMPTY);
    }

    private String displayName(String firstName, String lastName) {
        String combined = ((firstName == null ? "" : firstName) + " "
                + (lastName == null ? "" : lastName)).trim();
        return combined.isBlank() ? null : combined;
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

    private record SenderMetadata(Long telegramId, String displayName) {

        private static final SenderMetadata EMPTY = new SenderMetadata(null, null);
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

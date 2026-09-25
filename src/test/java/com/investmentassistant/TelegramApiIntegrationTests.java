package com.investmentassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.investmentassistant.telegram.AvailableTelegramSource;
import com.investmentassistant.telegram.NormalizedTelegramMessage;
import com.investmentassistant.telegram.ResolvedTelegramSource;
import com.investmentassistant.telegram.TelegramClientGateway;
import com.investmentassistant.telegram.TelegramConnectionState;
import com.investmentassistant.telegram.TelegramMessageRepository;
import com.investmentassistant.telegram.TelegramRawMessage;
import com.investmentassistant.telegram.TelegramSource;
import com.investmentassistant.telegram.TelegramSourceRepository;
import com.investmentassistant.telegram.TelegramSourceType;
import com.investmentassistant.telegram.TelegramStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TelegramApiIntegrationTests.FakeTelegramConfiguration.class)
class TelegramApiIntegrationTests {

    private static final String DATABASE_PATH = System.getProperty("java.io.tmpdir")
            + "/investment-assistant-api-" + UUID.randomUUID() + ".db";
    private static final long TELEGRAM_ID = -1001234567890L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.database.path", () -> DATABASE_PATH);
        registry.add("app.telegram.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TelegramSourceRepository sourceRepository;

    @Autowired
    private TelegramMessageRepository messageRepository;

    @Autowired
    private TelegramStatus status;

    @Autowired
    private FakeTelegramClient client;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM telegram_message");
        jdbcTemplate.update("DELETE FROM telegram_source");
        client.reset();
        status.set(TelegramConnectionState.CONNECTED);
    }

    @Test
    void listsSourcesWithFilterCountsAndDetails() throws Exception {
        TelegramSource source = monitoredSource();
        save(source, 1, "2026-09-25T10:00:00Z", "older");
        save(source, 2, "2026-09-25T11:00:00Z", "newer");

        JsonNode list = json(get("/api/telegram/sources?enabled=true"));
        JsonNode details = json(get("/api/telegram/sources/" + source.id()));
        JsonNode stats = json(get("/api/telegram/stats"));

        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("type").asText()).isEqualTo("CHANNEL");
        assertThat(list.get(0).get("messageCount").asLong()).isEqualTo(2);
        assertThat(details.get("firstMessageAt").asText()).isEqualTo("2026-09-25T10:00:00Z");
        assertThat(details.get("lastMessageAt").asText()).isEqualTo("2026-09-25T11:00:00Z");
        assertThat(stats.get("monitoredSources").asInt()).isEqualTo(1);
        assertThat(stats.get("messagesStored").asInt()).isEqualTo(2);
    }

    @Test
    void discoversAvailableSourcesAndMarksMonitoredOnes() throws Exception {
        monitoredSource();

        HttpResponse<String> response = get("/api/telegram/sources/available");
        JsonNode body = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.size()).isEqualTo(2);
        assertThat(List.of(body.get(0).get("title").asText(), body.get(1).get("title").asText()))
                .containsExactly("Market News", "Investors Group");
        assertThat(body.get(0).get("monitored").asBoolean()).isTrue();
        assertThat(body.get(1).get("monitored").asBoolean()).isFalse();
    }

    @Test
    void postIsIdempotentAndImportsHistoryOnce() throws Exception {
        String request = "{\"telegramId\":" + TELEGRAM_ID + "}";

        JsonNode first = json(send("POST", "/api/telegram/sources?historyLimit=100", request));
        JsonNode second = json(send("POST", "/api/telegram/sources?historyLimit=100", request));

        assertThat(first.get("historicalMessagesImported").asInt()).isEqualTo(2);
        assertThat(second.get("historicalMessagesImported").asInt()).isZero();
        assertThat(sourceRepository.countAll()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(client.historyRequests).isEqualTo(2);
    }

    @Test
    void capsHistoricalImportAtRequestedLimit() throws Exception {
        JsonNode response = json(send(
                "POST",
                "/api/telegram/sources?historyLimit=1",
                "{\"telegramId\":" + TELEGRAM_ID + "}"));

        assertThat(response.get("historicalMessagesImported").asInt()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void disablesAndReenablesWithoutDuplicatingHistory() throws Exception {
        TelegramSource source = monitoredSource();

        JsonNode disabled = json(send("PATCH", "/api/telegram/sources/" + source.id(), "{\"enabled\":false}"));
        JsonNode enabled = json(send("PATCH", "/api/telegram/sources/" + source.id(), "{\"enabled\":true}"));

        assertThat(disabled.get("enabled").asBoolean()).isFalse();
        assertThat(enabled.get("enabled").asBoolean()).isTrue();
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    void returnsMessagesNewestFirstByLocalAndTelegramId() throws Exception {
        TelegramSource source = monitoredSource();
        save(source, 1, "2026-09-25T10:00:00Z", "older");
        save(source, 2, "2026-09-25T11:00:00Z", "newer");

        JsonNode byLocalId = json(get("/api/telegram/sources/" + source.id() + "/messages?limit=2"));
        JsonNode byTelegramId = json(get("/api/telegram/messages?telegramId=" + TELEGRAM_ID + "&limit=1"));

        assertThat(byLocalId.get(0).get("telegramMessageId").asLong()).isEqualTo(2);
        assertThat(byLocalId.get(0).get("senderDisplayName").asText()).isEqualTo("Test Sender");
        assertThat(byTelegramId.size()).isEqualTo(1);
        assertThat(byTelegramId.get(0).get("text").asText()).isEqualTo("newer");
    }

    @Test
    void validatesLimitsAndReturnsStructuredNotFoundErrors() throws Exception {
        HttpResponse<String> invalid = get("/api/telegram/messages?telegramId=" + TELEGRAM_ID + "&limit=201");
        HttpResponse<String> missing = get("/api/telegram/sources/999999");
        HttpResponse<String> invalidHistory = send(
                "POST",
                "/api/telegram/sources?historyLimit=1001",
                "{\"telegramId\":" + TELEGRAM_ID + "}");

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json(invalid).get("code").asText()).isEqualTo("INVALID_LIMIT");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("code").asText()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(invalidHistory.statusCode()).isEqualTo(400);
        assertThat(json(invalidHistory).get("code").asText()).isEqualTo("INVALID_LIMIT");

        TelegramSource source = monitoredSource();
        HttpResponse<String> badLimit = get("/api/telegram/sources/" + source.id() + "/messages?limit=201");
        assertThat(badLimit.statusCode()).isEqualTo(400);
        assertThat(json(badLimit).get("code").asText()).isEqualTo("INVALID_LIMIT");
    }

    @Test
    void returnsServiceUnavailableWhenTelegramIsDisconnected() throws Exception {
        status.set(TelegramConnectionState.ERROR);

        HttpResponse<String> available = get("/api/telegram/sources/available");
        HttpResponse<String> add = send("POST", "/api/telegram/sources", "{\"telegramId\":" + TELEGRAM_ID + "}");

        assertThat(available.statusCode()).isEqualTo(503);
        assertThat(json(available).get("code").asText()).isEqualTo("TELEGRAM_NOT_CONNECTED");
        assertThat(add.statusCode()).isEqualTo(503);
    }

    private TelegramSource monitoredSource() {
        return sourceRepository.upsertResolved(new ResolvedTelegramSource(
                TELEGRAM_ID, TelegramSourceType.CHANNEL, "market_news", "Market News"));
    }

    private void save(TelegramSource source, long messageId, String timestamp, String text) {
        messageRepository.save(source.id(), new NormalizedTelegramMessage(
                TELEGRAM_ID, messageId, Instant.parse(timestamp), 123L, "Test Sender", text,
                "https://t.me/market_news/" + messageId));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeTelegramConfiguration {

        @Bean
        FakeTelegramClient fakeTelegramClient() {
            return new FakeTelegramClient();
        }
    }

    static final class FakeTelegramClient implements TelegramClientGateway {

        private int historyRequests;

        void reset() {
            historyRequests = 0;
        }

        @Override
        public void start(Listener listener) {
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(TelegramSource source) {
            return resolve(source.telegramId());
        }

        @Override
        public CompletableFuture<ResolvedTelegramSource> resolve(long telegramId) {
            return CompletableFuture.completedFuture(new ResolvedTelegramSource(
                    telegramId, TelegramSourceType.CHANNEL, "market_news", "Market News"));
        }

        @Override
        public CompletableFuture<List<AvailableTelegramSource>> listAvailableSources() {
            return CompletableFuture.completedFuture(List.of(
                    new AvailableTelegramSource(TELEGRAM_ID, TelegramSourceType.CHANNEL,
                            "Market News", "market_news"),
                    new AvailableTelegramSource(-100555555555L, TelegramSourceType.SUPERGROUP,
                            "Investors Group", null)));
        }

        @Override
        public CompletableFuture<List<TelegramRawMessage>> loadRecentMessages(long telegramSourceId, int limit) {
            historyRequests++;
            return CompletableFuture.completedFuture(List.of(
                    new TelegramRawMessage(telegramSourceId, 10, Instant.parse("2026-09-25T12:00:00Z"),
                            123L, "Test Sender", "first", null, "https://t.me/market_news/10"),
                    new TelegramRawMessage(telegramSourceId, 11, Instant.parse("2026-09-25T12:01:00Z"),
                            123L, "Test Sender", "second", null, "https://t.me/market_news/11")));
        }

        @Override
        public void close() {
        }
    }
}

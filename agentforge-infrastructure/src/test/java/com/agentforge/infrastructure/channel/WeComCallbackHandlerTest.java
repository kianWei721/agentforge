package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelInboundStatus;
import com.agentforge.channel.wecom.SessionTurnGate;
import com.agentforge.channel.wecom.StreamBuffers;
import com.agentforge.channel.wecom.WeComChannelService;
import com.agentforge.channel.wecom.WeComChannelSettings;
import com.agentforge.channel.wecom.WeComCrypto;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.runtime.AgentStreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

/**
 * End to end through the callback handler, with a stand-in for WeCom that encrypts and signs
 * callbacks and checks the signature of every reply. No real WeCom tenant is involved.
 */
class WeComCallbackHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-token";
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String BOT = "bot-1";
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    private final ExecutorService turns = Executors.newSingleThreadExecutor();
    private final WeComCrypto wecom = new WeComCrypto(TOKEN, AES_KEY, BOT);
    private JdbcChannelInboundRepository inbound;
    private WeComChannelService service;
    private WeComCallbackHandler handler;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wecom_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS af_channel_inbound");
        jdbc.execute(
                """
                CREATE TABLE af_channel_inbound (
                    id VARCHAR(64) PRIMARY KEY, channel VARCHAR(32) NOT NULL,
                    bot_id VARCHAR(128) NOT NULL, msg_id VARCHAR(128) NOT NULL,
                    chat_type VARCHAR(16) NOT NULL, chat_id VARCHAR(128),
                    sender_userid VARCHAR(128) NOT NULL, agent_id VARCHAR(64), user_id VARCHAR(128),
                    session_key VARCHAR(128), execution_id VARCHAR(64), status VARCHAR(16) NOT NULL,
                    error_code VARCHAR(64), created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL, UNIQUE (channel, bot_id, msg_id))
                """);
        inbound = new JdbcChannelInboundRepository(jdbc);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service =
                new WeComChannelService(
                        new WeComChannelSettings(
                                BOT,
                                "wecom-assistant",
                                Set.of("search_knowledge"),
                                Map.of("zhangsan", "dev-staff"),
                                Map.of(),
                                false,
                                Duration.ofSeconds(5)),
                        inbound,
                        userId ->
                                "dev-staff".equals(userId)
                                        ? Optional.of(AccessPrincipal.staff("dev-staff"))
                                        : Optional.empty(),
                        new SingleAgent(),
                        (agentId, request) ->
                                Flux.just(
                                                AgentStreamChunk.message("小规模纳税人", "test"),
                                                AgentStreamChunk.message("按季度申报。", "test"),
                                                AgentStreamChunk.done("test"))
                                        .delayElements(Duration.ofMillis(30)),
                        new SessionTurnGate(turns, 64, 3),
                        new StreamBuffers(Duration.ofMinutes(10), clock),
                        clock);
        handler =
                new WeComCallbackHandler(
                        new WeComCrypto(TOKEN, AES_KEY, BOT),
                        new WeComMessageCodec(JSON),
                        service,
                        Duration.ofMinutes(5),
                        clock);
    }

    @AfterEach
    void tearDown() {
        turns.shutdownNow();
    }

    @Test
    void verifiesTheCallbackUrlWithTheOfficialSample() {
        Clock sampleTime = Clock.fixed(Instant.ofEpochSecond(1409659589L), ZoneOffset.UTC);
        WeComCallbackHandler sampleHandler =
                new WeComCallbackHandler(
                        new WeComCrypto(
                                "QDG6eK",
                                "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C",
                                "wx5823bf96d3bd56c7"),
                        new WeComMessageCodec(JSON),
                        null,
                        Duration.ofMinutes(5),
                        sampleTime);

        assertEquals(
                "1616140317555161061",
                sampleHandler.verifyUrl(
                        "5c45ff5e21c57e6ad56bac8758b79b1d9ac89fd3",
                        "1409659589",
                        "263014780",
                        "P9nAzCzyDtyTWESHep1vC5X9xho/qYX3Zpb4yKa9SKld1DsH3Iyt3tP3zNdtp+4RPcs8TgAE7OaBO+FZXvnaqQ=="));
    }

    @Test
    void answersFirstThenStreamsTheAnswerThroughRefreshCallbacks() throws Exception {
        JsonNode first = send(textMessage("msg-1", "小规模纳税人怎么申报"));
        assertEquals("stream", first.path("msgtype").asText());
        assertFalse(first.path("stream").path("finish").asBoolean());
        String streamId = first.path("stream").path("id").asText();

        JsonNode latest = first;
        for (int i = 0; i < 100 && !latest.path("stream").path("finish").asBoolean(); i++) {
            Thread.sleep(20);
            latest = send(refresh(streamId));
        }

        assertTrue(latest.path("stream").path("finish").asBoolean());
        assertEquals("小规模纳税人按季度申报。", latest.path("stream").path("content").asText());
        assertEquals(
                ChannelInboundStatus.DONE,
                inbound.find("wecom", BOT, "msg-1").orElseThrow().status());
    }

    @Test
    void aRedeliveredCallbackGetsTheSameStream() throws Exception {
        String firstStream = send(textMessage("msg-1", "问题")).path("stream").path("id").asText();
        String againStream = send(textMessage("msg-1", "问题")).path("stream").path("id").asText();

        assertEquals(firstStream, againStream);
    }

    @Test
    void eventsGetNoReply() {
        Callback callback = callback("{\"msgid\":\"e1\",\"aibotid\":\"bot-1\",\"msgtype\":\"event\"}");

        assertEquals("", handler.handle(callback.signature, callback.timestamp, callback.nonce, callback.body));
    }

    @Test
    void forgedStaleOrUnreadableCallbacksAreRejected() {
        Callback valid = callback(textMessage("msg-1", "问题"));

        assertThrows(
                WeComCallbackRejectedException.class,
                () -> handler.handle("0".repeat(40), valid.timestamp, valid.nonce, valid.body));

        Callback stale = callback(textMessage("msg-2", "问题"), NOW.minus(Duration.ofMinutes(10)));
        assertThrows(
                WeComCallbackRejectedException.class,
                () -> handler.handle(stale.signature, stale.timestamp, stale.nonce, stale.body));

        assertThrows(
                WeComCallbackRejectedException.class,
                () -> handler.handle(valid.signature, valid.timestamp, valid.nonce, "{}"));

        // Signed correctly, but encrypted for another bot.
        WeComCrypto otherBot = new WeComCrypto(TOKEN, AES_KEY, "other-bot");
        String foreign = otherBot.encrypt(textMessage("msg-3", "问题"));
        String timestamp = Long.toString(NOW.getEpochSecond());
        assertThrows(
                WeComCallbackRejectedException.class,
                () ->
                        handler.handle(
                                wecom.sign(timestamp, "7", foreign),
                                timestamp,
                                "7",
                                "{\"encrypt\":\"" + foreign + "\"}"));
    }

    @Test
    void withoutAReceiveIdAnotherBotsMessageDecryptsButIsRefusedByBotId() throws Exception {
        // The default configuration leaves receive-id blank: decryption cannot tell bots apart,
        // the aibotid check does, and answers with a refusal instead of a 403.
        WeComCallbackHandler lenient =
                new WeComCallbackHandler(
                        new WeComCrypto(TOKEN, AES_KEY, null),
                        new WeComMessageCodec(JSON),
                        service,
                        Duration.ofMinutes(5),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        WeComCrypto otherBot = new WeComCrypto(TOKEN, AES_KEY, "other-bot");
        String encrypted =
                otherBot.encrypt(textMessage("msg-9", "问题").replace("\"" + BOT + "\"", "\"other-bot\""));
        String timestamp = Long.toString(NOW.getEpochSecond());

        JsonNode envelope =
                JSON.readTree(
                        lenient.handle(
                                otherBot.sign(timestamp, "7", encrypted),
                                timestamp,
                                "7",
                                "{\"encrypt\":\"" + encrypted + "\"}"));
        JsonNode reply =
                JSON.readTree(
                        new WeComCrypto(TOKEN, AES_KEY, null).decrypt(envelope.path("encrypt").asText()));

        assertTrue(reply.path("stream").path("finish").asBoolean());
        assertEquals("BOT_MISMATCH", inbound.find("wecom", BOT, "msg-9").orElseThrow().errorCode());
    }

    @Test
    void aMissingChatTypeIsRefused() throws Exception {
        JsonNode reply =
                send(
                        "{\"msgid\":\"msg-5\",\"aibotid\":\"" + BOT + "\","
                                + "\"from\":{\"userid\":\"zhangsan\"},\"msgtype\":\"text\","
                                + "\"text\":{\"content\":\"问题\"}}");

        assertTrue(reply.path("stream").path("finish").asBoolean());
        assertEquals("UNSUPPORTED_CHAT", inbound.find("wecom", BOT, "msg-5").orElseThrow().errorCode());
    }

    /** Sends a callback the way WeCom would and returns the reply, after checking its signature. */
    private JsonNode send(String plaintext) throws Exception {
        Callback callback = callback(plaintext);
        JsonNode envelope =
                JSON.readTree(
                        handler.handle(
                                callback.signature, callback.timestamp, callback.nonce, callback.body));
        String encrypted = envelope.path("encrypt").asText();
        assertTrue(
                wecom.verify(
                        envelope.path("msgsignature").asText(),
                        envelope.path("timestamp").asText(),
                        envelope.path("nonce").asText(),
                        encrypted),
                "reply signature");
        return JSON.readTree(wecom.decrypt(encrypted));
    }

    private Callback callback(String plaintext) {
        return callback(plaintext, NOW);
    }

    private Callback callback(String plaintext, Instant sentAt) {
        String encrypted = wecom.encrypt(plaintext);
        String timestamp = Long.toString(sentAt.getEpochSecond());
        String nonce = "1372623149";
        return new Callback(
                wecom.sign(timestamp, nonce, encrypted),
                timestamp,
                nonce,
                "{\"encrypt\":\"" + encrypted + "\"}");
    }

    private static String textMessage(String msgId, String text) {
        return "{\"msgid\":\"" + msgId + "\",\"aibotid\":\"" + BOT + "\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"zhangsan\"},\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + text + "\"}}";
    }

    private static String refresh(String streamId) {
        return "{\"msgid\":\"r-" + System.nanoTime() + "\",\"aibotid\":\"" + BOT + "\","
                + "\"chattype\":\"single\",\"from\":{\"userid\":\"zhangsan\"},"
                + "\"msgtype\":\"stream\",\"stream\":{\"id\":\"" + streamId + "\"}}";
    }

    private record Callback(String signature, String timestamp, String nonce, String body) {}

    private static final class SingleAgent implements AgentDefinitionRepository {
        @Override
        public Optional<AgentDefinition> findById(String id) {
            return Optional.of(
                    new AgentDefinition(
                            id, "WeCom", null, "prompt", "mock", "mock",
                            List.of("search_knowledge"), true, 1, null, null));
        }

        @Override
        public List<AgentDefinition> findAll() {
            return List.of();
        }

        @Override
        public AgentDefinition save(AgentDefinition definition) {
            return definition;
        }

        @Override
        public boolean existsById(String id) {
            return true;
        }

        @Override
        public void deleteById(String id) {}
    }
}

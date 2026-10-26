package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.handoff.HandoffNotificationSender.Result;
import com.agentforge.infrastructure.config.WeComChannelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WeComWebhookSenderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aTextMessageWithMentionsIsSentAndOnlyErrcodeZeroCounts() throws Exception {
        URI webhook = answer(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");

        Result result = sender(webhook, List.of("kefu-1")).send("【转人工】待认领 1 条");

        assertEquals(Result.SENT, result);
        JsonNode body = JSON.readTree(received.get());
        assertEquals("text", body.path("msgtype").asText());
        assertEquals("【转人工】待认领 1 条", body.path("text").path("content").asText());
        assertEquals("kefu-1", body.path("text").path("mentioned_list").get(0).asText());
    }

    @Test
    void weComFailuresArriveAsHttp200WithAnErrcode() throws Exception {
        assertEquals(
                Result.rateLimited("errcode=45009"),
                sender(answer(200, "{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}"), List.of()).send("x"));
        stop();
        assertEquals(
                Result.failed("errcode=93000"),
                sender(answer(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}"), List.of()).send("x"));
        stop();
        assertEquals(Result.failed("http=502"), sender(answer(502, "bad gateway"), List.of()).send("x"));
        stop();
        assertEquals(Result.failed("unreadable answer"), sender(answer(200, "<html>"), List.of()).send("x"));
    }

    @Test
    void anUnreachableWebhookNamesTheFailureNotTheAddress() {
        URI webhook = URI.create("http://127.0.0.1:1/cgi-bin/webhook/send?key=secret-key");

        Result result = sender(webhook, List.of()).send("x");

        assertFalse(result.sent());
        assertFalse(result.error().contains("secret-key"), result.error());
    }

    @Test
    void onlyWeComsOwnHostOverHttpsIsAccepted() {
        WeComChannelProperties.Handoff handoff = new WeComChannelProperties.Handoff();

        handoff.setWebhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k");
        assertEquals("qyapi.weixin.qq.com", handoff.webhook().getHost());
        assertTrue(handoff.notifies());

        for (String elsewhere : List.of(
                "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k",
                "https://qyapi.weixin.qq.com.evil.example/send?key=k",
                "https://example.com/cgi-bin/webhook/send?key=k")) {
            handoff.setWebhookUrl(elsewhere);
            assertThrows(IllegalStateException.class, handoff::webhook, elsewhere);
        }
    }

    @Test
    void aNoticeIntervalThatCouldPassTheRateLimitIsRefused() {
        WeComChannelProperties.Handoff handoff = new WeComChannelProperties.Handoff();
        handoff.setWebhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k");
        handoff.validate();

        handoff.setNotifyInterval(Duration.ofSeconds(2));
        IllegalStateException refused = assertThrows(IllegalStateException.class, handoff::validate);
        assertTrue(refused.getMessage().contains("notify-interval"), refused.getMessage());

        // Without a webhook nothing is sent, so the interval does not matter.
        handoff.setWebhookUrl("");
        assertDoesNotThrow(handoff::validate);
    }

    private URI answer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/cgi-bin/webhook/send",
                exchange -> {
                    received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cgi-bin/webhook/send?key=k");
    }

    private static WeComWebhookSender sender(URI webhook, List<String> mentioned) {
        return new WeComWebhookSender(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                webhook,
                mentioned,
                Duration.ofSeconds(2),
                JSON);
    }
}

package com.agentforge.infrastructure.channel;

import com.agentforge.channel.handoff.HandoffNotificationSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Posts a text message to a WeCom group robot. WeCom answers HTTP 200 for most failures, with the
 * reason in {@code errcode}: only errcode 0 counts as sent. The address carries the robot's key,
 * so it never appears in a result or a log line.
 */
public class WeComWebhookSender implements HandoffNotificationSender {

    /** Too many messages for this robot; the official limit is 20 a minute. */
    static final int RATE_LIMITED = 45009;

    private final HttpClient http;
    private final URI webhook;
    private final List<String> mentioned;
    private final Duration timeout;
    private final ObjectMapper json;

    /** @param webhook checked by the configuration: https on qyapi.weixin.qq.com */
    public WeComWebhookSender(
            HttpClient http, URI webhook, List<String> mentioned, Duration timeout, ObjectMapper json) {
        this.http = http;
        this.webhook = webhook;
        this.mentioned = List.copyOf(mentioned);
        this.timeout = timeout;
        this.json = json;
    }

    @Override
    public Result send(String text) {
        ObjectNode body = json.createObjectNode().put("msgtype", "text");
        ObjectNode content = body.putObject("text").put("content", text);
        if (!mentioned.isEmpty()) {
            mentioned.forEach(content.putArray("mentioned_list")::add);
        }
        HttpResponse<String> response;
        try {
            response =
                    http.send(
                            HttpRequest.newBuilder(webhook)
                                    .timeout(timeout)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted");
        } catch (Exception exception) {
            // The exception's message may quote the address; its type is enough.
            return Result.failed(exception.getClass().getSimpleName());
        }
        if (response.statusCode() != 200) {
            return Result.failed("http=" + response.statusCode());
        }
        int errcode;
        try {
            JsonNode answer = json.readTree(response.body());
            errcode = answer.path("errcode").asInt(-1);
        } catch (Exception unreadable) {
            return Result.failed("unreadable answer");
        }
        if (errcode == 0) {
            return Result.SENT;
        }
        return errcode == RATE_LIMITED
                ? Result.rateLimited("errcode=" + errcode)
                : Result.failed("errcode=" + errcode);
    }
}

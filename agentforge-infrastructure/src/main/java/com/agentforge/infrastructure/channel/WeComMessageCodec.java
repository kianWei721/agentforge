package com.agentforge.infrastructure.channel;

import com.agentforge.channel.wecom.WeComInbound;
import com.agentforge.channel.wecom.WeComStreamReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/** JSON shapes of WeCom smart-bot callbacks and stream replies. */
public class WeComMessageCodec {

    private final ObjectMapper json;

    public WeComMessageCodec(ObjectMapper json) {
        this.json = json;
    }

    /** The {@code encrypt} field of a callback body. */
    public String encryptedPayload(String body) {
        String encrypted = read(body).path("encrypt").asText("");
        if (encrypted.isBlank()) {
            throw new WeComCallbackRejectedException("callback body has no encrypt field");
        }
        return encrypted;
    }

    public WeComInbound parse(String plaintext) {
        JsonNode root = read(plaintext);
        WeComInbound.ChatType chatType =
                switch (root.path("chattype").asText("")) {
                    case "single" -> WeComInbound.ChatType.SINGLE;
                    case "group" -> WeComInbound.ChatType.GROUP;
                    default -> WeComInbound.ChatType.UNKNOWN;
                };
        return new WeComInbound(
                root.path("msgid").asText(""),
                root.path("aibotid").asText(""),
                chatType,
                text(root.path("chatid")),
                root.path("from").path("userid").asText(""),
                root.path("msgtype").asText(""),
                text(root.path("text").path("content")),
                text(root.path("stream").path("id")));
    }

    public String reply(WeComStreamReply reply) {
        ObjectNode root = json.createObjectNode();
        root.put("msgtype", "stream");
        ObjectNode stream = root.putObject("stream");
        stream.put("id", reply.streamId());
        stream.put("finish", reply.finished());
        stream.put("content", reply.content());
        return write(root);
    }

    /** The encrypted, signed response envelope. */
    public String envelope(String encrypted, String signature, long timestamp, String nonce) {
        ObjectNode root = json.createObjectNode();
        root.put("encrypt", encrypted);
        root.put("msgsignature", signature);
        root.put("timestamp", timestamp);
        root.put("nonce", nonce);
        return write(root);
    }

    private JsonNode read(String body) {
        try {
            JsonNode root = json.readTree(body == null ? "" : body);
            if (root == null || !root.isObject()) {
                throw new WeComCallbackRejectedException("callback payload is not a JSON object");
            }
            return root;
        } catch (IOException exception) {
            throw new WeComCallbackRejectedException("callback payload is not JSON");
        }
    }

    private String write(ObjectNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}

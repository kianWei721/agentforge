package com.agentforge.channel.wecom;

import com.agentforge.runtime.ChannelSessions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Server-derived keys. Nothing in a message's text can change which session it lands in.
 *
 * <p>Keys are hashed, not built from raw ids: WeCom ids are case-sensitive, while {@code
 * af_session.session_key} uses a case-insensitive collation, so two chats differing only in case
 * would otherwise share one session.
 */
public final class WeComSessionKeys {

    /** Session keys with this prefix belong to the WeCom channel; the HTTP API may not write them. */
    public static final String PREFIX = ChannelSessions.WECOM_PREFIX;

    private WeComSessionKeys() {}

    public static String direct(String botId, String userId) {
        return PREFIX + sha256Hex("dm", botId, userId);
    }

    public static String group(String botId, String chatId) {
        return PREFIX + sha256Hex("group", botId, chatId);
    }

    /** One stream per inbound message, so a redelivered message gets the same reply stream. */
    public static String streamId(String botId, String msgId) {
        return "s" + sha256Hex("stream", botId, msgId).substring(0, 32);
    }

    public static boolean isChannelSession(String sessionKey) {
        return ChannelSessions.isChannelSession(sessionKey);
    }

    /** Length-prefixed, so no choice of ids can make two different tuples hash the same input. */
    private static String sha256Hex(String... parts) {
        StringBuilder input = new StringBuilder();
        for (String part : parts) {
            input.append(part.length()).append(':').append(part);
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

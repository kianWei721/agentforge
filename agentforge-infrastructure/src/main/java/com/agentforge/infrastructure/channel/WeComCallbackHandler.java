package com.agentforge.infrastructure.channel;

import com.agentforge.channel.wecom.WeComChannelService;
import com.agentforge.channel.wecom.WeComCrypto;
import com.agentforge.channel.wecom.WeComCryptoException;
import com.agentforge.channel.wecom.WeComInbound;
import com.agentforge.channel.wecom.WeComStreamReply;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/**
 * Authenticates and decrypts WeCom smart-bot callbacks, and encrypts the reply.
 *
 * <p>The callback path sits outside {@code /api/v1}: it has no user identity header, and the
 * signature is its authentication. A timestamp outside the tolerance window is refused, so a
 * captured callback cannot be replayed later.
 */
public class WeComCallbackHandler {

    private final WeComCrypto crypto;
    private final WeComMessageCodec codec;
    private final WeComChannelService service;
    private final Duration timestampTolerance;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WeComCallbackHandler(
            WeComCrypto crypto,
            WeComMessageCodec codec,
            WeComChannelService service,
            Duration timestampTolerance,
            Clock clock) {
        this.crypto = crypto;
        this.codec = codec;
        this.service = service;
        this.timestampTolerance = timestampTolerance;
        this.clock = clock;
    }

    /** URL verification when the callback is configured: return the decrypted echostr. */
    public String verifyUrl(String signature, String timestamp, String nonce, String echoStr) {
        authenticate(signature, timestamp, nonce, echoStr);
        return decrypt(echoStr);
    }

    /** @return the encrypted reply envelope, or an empty body when nothing is to be replied */
    public String handle(String signature, String timestamp, String nonce, String body) {
        String encrypted = codec.encryptedPayload(body);
        authenticate(signature, timestamp, nonce, encrypted);
        WeComInbound message = codec.parse(decrypt(encrypted));
        if ("event".equals(message.msgType())) {
            return "";
        }
        WeComStreamReply reply = service.handle(message);
        String replyEncrypted = crypto.encrypt(codec.reply(reply));
        long replyTimestamp = clock.instant().getEpochSecond();
        String replyNonce = Long.toString(random.nextLong() & Long.MAX_VALUE);
        return codec.envelope(
                replyEncrypted,
                crypto.sign(Long.toString(replyTimestamp), replyNonce, replyEncrypted),
                replyTimestamp,
                replyNonce);
    }

    private void authenticate(String signature, String timestamp, String nonce, String encrypted) {
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException | NullPointerException exception) {
            throw new WeComCallbackRejectedException("callback timestamp is not a number");
        }
        if (Math.abs(clock.instant().getEpochSecond() - sentAt) > timestampTolerance.toSeconds()) {
            throw new WeComCallbackRejectedException("callback timestamp outside tolerance");
        }
        if (!crypto.verify(signature, timestamp, nonce, encrypted)) {
            throw new WeComCallbackRejectedException("callback signature mismatch");
        }
    }

    private String decrypt(String encrypted) {
        try {
            return crypto.decrypt(encrypted);
        } catch (WeComCryptoException exception) {
            throw new WeComCallbackRejectedException("callback payload rejected", exception);
        }
    }
}

package com.agentforge.channel.wecom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * WeCom callback signing and encryption.
 *
 * <ul>
 *   <li>signature = sha1 of token, timestamp, nonce and the encrypted text, sorted and joined
 *   <li>AES key = Base64(EncodingAESKey + "="), 32 bytes; AES-256-CBC with the key's first 16
 *       bytes as IV
 *   <li>plaintext = 16 random bytes + 4-byte big-endian length + message + receive id, PKCS#7
 *       padded to a multiple of <b>32</b> bytes
 * </ul>
 *
 * <p>The 32-byte padding is why this does not use the JDK's {@code PKCS5Padding}, which only
 * accepts pads of up to 16 bytes and rejects WeCom's ciphertexts.
 */
public final class WeComCrypto {

    private static final int PAD_BLOCK = 32;
    private static final int RANDOM_BYTES = 16;

    private final String token;
    private final byte[] aesKey;
    private final String receiveId;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param receiveId the receive id the plaintext must end with; null or blank skips the check
     *     (the value WeCom uses for smart bots is not documented where we could verify it)
     */
    public WeComCrypto(String token, String encodingAesKey, String receiveId) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("WeCom token must not be blank");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("WeCom EncodingAESKey must be 43 characters");
        }
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        this.receiveId = receiveId == null || receiveId.isBlank() ? null : receiveId;
    }

    public String sign(String timestamp, String nonce, String encrypted) {
        String[] parts = {token, timestamp, nonce, encrypted};
        Arrays.sort(parts);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Constant-time comparison, so the signature cannot be guessed byte by byte. */
    public boolean verify(String signature, String timestamp, String nonce, String encrypted) {
        if (signature == null || timestamp == null || nonce == null || encrypted == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    public String decrypt(String encrypted) {
        byte[] plain;
        try {
            plain = cipher(Cipher.DECRYPT_MODE).doFinal(Base64.getDecoder().decode(encrypted));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new WeComCryptoException("cannot decrypt WeCom payload", exception);
        }
        int pad = plain.length == 0 ? 0 : plain[plain.length - 1] & 0xff;
        if (pad < 1 || pad > PAD_BLOCK || pad > plain.length) {
            throw new WeComCryptoException("invalid WeCom padding");
        }
        int contentEnd = plain.length - pad;
        if (contentEnd < RANDOM_BYTES + 4) {
            throw new WeComCryptoException("WeCom payload too short");
        }
        int messageLength = ByteBuffer.wrap(plain, RANDOM_BYTES, 4).getInt();
        int messageStart = RANDOM_BYTES + 4;
        if (messageLength < 0 || messageLength > contentEnd - messageStart) {
            throw new WeComCryptoException("invalid WeCom message length");
        }
        String message =
                new String(plain, messageStart, messageLength, StandardCharsets.UTF_8);
        String actualReceiveId =
                new String(
                        plain,
                        messageStart + messageLength,
                        contentEnd - messageStart - messageLength,
                        StandardCharsets.UTF_8);
        if (receiveId != null && !receiveId.equals(actualReceiveId)) {
            throw new WeComCryptoException("WeCom receive id mismatch");
        }
        return message;
    }

    public String encrypt(String message) {
        byte[] randomPrefix = new byte[RANDOM_BYTES];
        random.nextBytes(randomPrefix);
        return encrypt(message, randomPrefix);
    }

    String encrypt(String message, byte[] randomPrefix) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        byte[] receive = (receiveId == null ? "" : receiveId).getBytes(StandardCharsets.UTF_8);
        int length = RANDOM_BYTES + 4 + body.length + receive.length;
        int pad = PAD_BLOCK - length % PAD_BLOCK;
        ByteBuffer plain = ByteBuffer.allocate(length + pad);
        plain.put(randomPrefix, 0, RANDOM_BYTES).putInt(body.length).put(body).put(receive);
        for (int i = 0; i < pad; i++) {
            plain.put((byte) pad);
        }
        try {
            return Base64.getEncoder()
                    .encodeToString(cipher(Cipher.ENCRYPT_MODE).doFinal(plain.array()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Cipher cipher(int mode) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                mode,
                new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16)));
        return cipher;
    }
}

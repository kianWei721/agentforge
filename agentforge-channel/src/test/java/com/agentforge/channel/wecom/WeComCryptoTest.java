package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WeComCryptoTest {

    // Sample parameters from WeCom's official WXBizMsgCrypt sample code (URL verification).
    private static final String SAMPLE_TOKEN = "QDG6eK";
    // Published in WeCom's documentation, not a secret.
    private static final String SAMPLE_AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C"; // gitleaks:allow
    private static final String SAMPLE_CORP_ID = "wx5823bf96d3bd56c7";

    private static final String KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @Test
    void verifiesAndDecryptsTheOfficialSample() {
        WeComCrypto crypto = new WeComCrypto(SAMPLE_TOKEN, SAMPLE_AES_KEY, SAMPLE_CORP_ID);
        String echoStr =
                "P9nAzCzyDtyTWESHep1vC5X9xho/qYX3Zpb4yKa9SKld1DsH3Iyt3tP3zNdtp+4RPcs8TgAE7OaBO+FZXvnaqQ==";

        assertTrue(
                crypto.verify(
                        "5c45ff5e21c57e6ad56bac8758b79b1d9ac89fd3",
                        "1409659589",
                        "263014780",
                        echoStr));
        assertEquals("1616140317555161061", crypto.decrypt(echoStr));
    }

    @Test
    void roundTripsChineseAtEveryPaddingLength() {
        WeComCrypto crypto = new WeComCrypto("token", KEY, "bot-1");
        StringBuilder message = new StringBuilder("{\"text\":\"季度申报\"}");
        for (int i = 0; i < 40; i++) {
            message.append('x');
            assertEquals(message.toString(), crypto.decrypt(crypto.encrypt(message.toString())));
        }
    }

    @Test
    void padsAFullBlockOfThirtyTwoThatTheJdkPaddingCannotRead() throws Exception {
        WeComCrypto crypto = new WeComCrypto("token", KEY, "");
        // 16 random + 4 length + 12 message = 32 bytes, so the padding is a whole 32-byte block.
        String encrypted = crypto.encrypt("123456789012", new byte[16]);
        byte[] cipherText = Base64.getDecoder().decode(encrypted);
        assertEquals(64, cipherText.length);

        byte[] key = Base64.getDecoder().decode(KEY + "=");
        Cipher jdkPadding = Cipher.getInstance("AES/CBC/PKCS5Padding");
        jdkPadding.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
        assertThrows(BadPaddingException.class, () -> jdkPadding.doFinal(cipherText));

        assertEquals("123456789012", crypto.decrypt(encrypted));
    }

    @Test
    void rejectsAForeignReceiveId() {
        String encrypted = new WeComCrypto("token", KEY, "other-corp").encrypt("hello");

        assertThrows(
                WeComCryptoException.class,
                () -> new WeComCrypto("token", KEY, "my-corp").decrypt(encrypted));
        // Without a configured receive id the check is skipped.
        assertEquals("hello", new WeComCrypto("token", KEY, null).decrypt(encrypted));
    }

    @Test
    void rejectsMalformedCipherText() {
        WeComCrypto crypto = new WeComCrypto("token", KEY, null);

        assertThrows(WeComCryptoException.class, () -> crypto.decrypt("not base64 at all!"));
        assertThrows(
                WeComCryptoException.class,
                () ->
                        crypto.decrypt(
                                Base64.getEncoder()
                                        .encodeToString("short".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void signatureCoversEveryInput() {
        WeComCrypto crypto = new WeComCrypto("token", KEY, null);
        String signature = crypto.sign("1700000000", "42", "payload");

        assertTrue(crypto.verify(signature, "1700000000", "42", "payload"));
        assertFalse(crypto.verify(signature, "1700000001", "42", "payload"));
        assertFalse(crypto.verify(signature, "1700000000", "43", "payload"));
        assertFalse(crypto.verify(signature, "1700000000", "42", "payload2"));
        assertFalse(crypto.verify(null, "1700000000", "42", "payload"));
        assertFalse(new WeComCrypto("other", KEY, null).verify(signature, "1700000000", "42", "payload"));
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new WeComCrypto("token", "short", null));
    }
}

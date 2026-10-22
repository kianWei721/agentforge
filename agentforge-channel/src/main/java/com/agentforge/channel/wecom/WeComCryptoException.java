package com.agentforge.channel.wecom;

/** A WeCom payload that cannot be trusted: bad padding, length, or receive id. */
public class WeComCryptoException extends RuntimeException {

    public WeComCryptoException(String message) {
        super(message);
    }

    public WeComCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}

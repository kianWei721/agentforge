package com.agentforge.infrastructure.channel;

/** A callback that did not prove it came from WeCom, or that we cannot read. Answered with 403. */
public class WeComCallbackRejectedException extends RuntimeException {

    public WeComCallbackRejectedException(String message) {
        super(message);
    }

    public WeComCallbackRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}

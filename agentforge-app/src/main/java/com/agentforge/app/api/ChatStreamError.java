package com.agentforge.app.api;

import com.agentforge.core.model.ModelCallFailedException;

/** Payload of the chat stream's {@code error} event, so the client can show why and whether to retry. */
public record ChatStreamError(String code, boolean retryable, String gatewayRequestId, String message) {
    public static ChatStreamError of(ModelCallFailedException failure) {
        return new ChatStreamError("MODEL_" + failure.errorCode(), failure.retryable(), failure.gatewayRequestId(), failure.getMessage());
    }
}

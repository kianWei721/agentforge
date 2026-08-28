package com.agentforge.app.api;

import java.time.Instant;

public record ApiError(String code, String message, String timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now().toString());
    }
}

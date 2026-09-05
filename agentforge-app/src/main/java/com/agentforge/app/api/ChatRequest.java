package com.agentforge.app.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Chat input. The caller's identity is taken from the access token, never from the
 * body: an earlier {@code userId} field here made every ownership check bypassable.
 */
public record ChatRequest(
        String sessionId, @NotBlank(message = "message must not be blank") String message) {}

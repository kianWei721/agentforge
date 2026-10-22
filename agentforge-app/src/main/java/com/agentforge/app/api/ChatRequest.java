package com.agentforge.app.api;

import jakarta.validation.constraints.NotBlank;

/** The caller's user id is not part of the body; it comes from the trusted identity header. */
public record ChatRequest(String sessionId, @NotBlank(message = "message must not be blank") String message) {}

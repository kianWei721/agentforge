package com.agentforge.app.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(String userId, String sessionId, @NotBlank(message = "message must not be blank") String message) {}

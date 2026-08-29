package com.agentforge.app.api;

import jakarta.validation.constraints.NotBlank;

public record ToolConfirmationActionRequest(
        @NotBlank(message = "userId must not be blank") String userId) {}

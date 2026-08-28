package com.agentforge.app.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AgentCreateRequest(
        @NotBlank(message = "name must not be blank") String name,
        String description,
        String systemPrompt,
        String modelProvider,
        String modelName,
        List<String> toolNames,
        Boolean enabled) {
}

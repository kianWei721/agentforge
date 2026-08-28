package com.agentforge.agent;

import java.util.List;

public record AgentUpdateCommand(
        String name,
        String description,
        String systemPrompt,
        String modelProvider,
        String modelName,
        List<String> toolNames,
        Boolean enabled) {
}

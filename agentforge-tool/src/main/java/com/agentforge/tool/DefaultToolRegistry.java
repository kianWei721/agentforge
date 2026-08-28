package com.agentforge.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    @Override
    public synchronized void register(AgentTool tool) {
        AgentTool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalStateException("Duplicate tool name: " + tool.name());
        }
    }

    @Override
    public synchronized Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public synchronized AgentTool getRequired(String name) {
        return find(name)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown tool '" + name + "'. Available tools: " + tools.keySet()));
    }

    @Override
    public synchronized List<AgentTool> resolve(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<AgentTool> resolved = new ArrayList<>();
        for (String name : names) {
            resolved.add(getRequired(name));
        }
        return List.copyOf(resolved);
    }

    @Override
    public synchronized List<ToolDescriptor> descriptors() {
        return tools.values().stream().map(ToolDescriptor::from).toList();
    }
}

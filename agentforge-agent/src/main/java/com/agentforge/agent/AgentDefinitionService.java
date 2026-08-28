package com.agentforge.agent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AgentDefinitionService {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 AgentForge 企业智能体平台中的助手。回答准确、简洁；需要外部事实时优先使用已绑定工具。";
    private static final String DEFAULT_MODEL_PROVIDER = "dashscope";
    private static final String DEFAULT_MODEL_NAME = "qwen3.8-flash";

    private final AgentDefinitionRepository repository;

    public AgentDefinitionService(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    public AgentDefinition create(AgentCreateCommand command) {
        String name = required(command.name(), "name");
        Instant now = Instant.now();
        AgentDefinition definition =
                new AgentDefinition(
                        UUID.randomUUID().toString(),
                        name,
                        trimToEmpty(command.description()),
                        defaultIfBlank(command.systemPrompt(), DEFAULT_SYSTEM_PROMPT),
                        defaultIfBlank(command.modelProvider(), DEFAULT_MODEL_PROVIDER)
                                .toLowerCase(Locale.ROOT),
                        defaultIfBlank(command.modelName(), DEFAULT_MODEL_NAME),
                        normalizeTools(command.toolNames()),
                        command.enabled() == null || command.enabled(),
                        1L,
                        now,
                        now);
        return repository.save(definition);
    }

    public AgentDefinition update(String id, AgentUpdateCommand command) {
        AgentDefinition current = get(id);
        Instant now = Instant.now();
        AgentDefinition updated =
                new AgentDefinition(
                        current.id(),
                        required(command.name(), "name"),
                        trimToEmpty(command.description()),
                        defaultIfBlank(command.systemPrompt(), DEFAULT_SYSTEM_PROMPT),
                        defaultIfBlank(command.modelProvider(), DEFAULT_MODEL_PROVIDER)
                                .toLowerCase(Locale.ROOT),
                        defaultIfBlank(command.modelName(), DEFAULT_MODEL_NAME),
                        normalizeTools(command.toolNames()),
                        command.enabled() == null ? current.enabled() : command.enabled(),
                        current.version() + 1,
                        current.createdAt(),
                        now);
        return repository.save(updated);
    }

    public AgentDefinition get(String id) {
        return repository.findById(id).orElseThrow(() -> new AgentNotFoundException(id));
    }

    public List<AgentDefinition> list() {
        return repository.findAll();
    }

    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new AgentNotFoundException(id);
        }
        repository.deleteById(id);
    }

    private static List<String> normalizeTools(List<String> toolNames) {
        if (toolNames == null) {
            return List.of();
        }
        return toolNames.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

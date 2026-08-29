package com.agentforge.runtime;

import com.agentforge.tool.AgentTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

public final class AgentScopeHarnessRuntime implements AgentRuntime, AutoCloseable {

    private static final String SOURCE = "agentscope-harness";

    private final HarnessAgent agent;

    public AgentScopeHarnessRuntime(
            String agentId,
            String name,
            String systemPrompt,
            Model model,
            List<AgentTool> businessTools) {
        Objects.requireNonNull(model, "model must not be null");

        Toolkit toolkit = new Toolkit();
        if (businessTools != null) {
            businessTools.forEach(tool -> toolkit.registerTool(new AgentScopeToolAdapter(agentId, tool)));
        }

        this.agent =
                HarnessAgent.builder()
                        .name(name)
                        .sysPrompt(systemPrompt)
                        .model(model)
                        .toolkit(toolkit)
                        .generateOptions(GenerateOptions.builder().build())
                        .stateStore(new InMemoryAgentStateStore())
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .disableDynamicSubagents()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableSubagents()
                        .disableToolsConfig()
                        .build();
    }

    @Override
    public Flux<AgentStreamChunk> stream(AgentRuntimeRequest request) {
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId(normalizeId(request.userId(), "anonymous"))
                        .sessionId(normalizeId(request.sessionId(), "default"))
                        .build();

        Flux<AgentStreamChunk> events =
                agent.streamEvents(request.message(), context)
                        .handle(
                                (event, sink) -> {
                                    AgentStreamChunk chunk = mapEvent(event);
                                    if (chunk != null) {
                                        sink.next(chunk);
                                    }
                                });

        return events.concatWithValues(AgentStreamChunk.done(SOURCE));
    }

    private AgentStreamChunk mapEvent(AgentEvent event) {
        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            String delta = ((TextBlockDeltaEvent) event).getDelta();
            return delta == null || delta.isEmpty()
                    ? null
                    : AgentStreamChunk.message(delta, SOURCE);
        }

        if (event instanceof ToolCallStartEvent toolEvent) {
            return AgentStreamChunk.trace(
                    "tool_call_start",
                    "",
                    SOURCE,
                    toolMetadata(
                            toolEvent.getReplyId(),
                            toolEvent.getToolCallId(),
                            toolEvent.getToolCallName()));
        }
        if (event instanceof ToolCallDeltaEvent toolEvent) {
            return AgentStreamChunk.trace(
                    "tool_call_delta",
                    toolEvent.getDelta(),
                    SOURCE,
                    toolMetadata(
                            toolEvent.getReplyId(),
                            toolEvent.getToolCallId(),
                            toolEvent.getToolCallName()));
        }
        if (event instanceof ToolCallEndEvent toolEvent) {
            return AgentStreamChunk.trace(
                    "tool_call_end",
                    "",
                    SOURCE,
                    toolMetadata(
                            toolEvent.getReplyId(),
                            toolEvent.getToolCallId(),
                            toolEvent.getToolCallName()));
        }
        if (event instanceof ToolResultStartEvent toolEvent) {
            return AgentStreamChunk.trace(
                    "tool_result_start",
                    "",
                    SOURCE,
                    toolMetadata(
                            toolEvent.getReplyId(),
                            toolEvent.getToolCallId(),
                            toolEvent.getToolCallName()));
        }
        if (event instanceof ToolResultTextDeltaEvent toolEvent) {
            return AgentStreamChunk.trace(
                    "tool_result_delta",
                    toolEvent.getDelta(),
                    SOURCE,
                    toolMetadata(
                            toolEvent.getReplyId(),
                            toolEvent.getToolCallId(),
                            toolEvent.getToolCallName()));
        }
        if (event instanceof ToolResultEndEvent toolEvent) {
            Map<String, Object> metadata =
                    new LinkedHashMap<>(
                            toolMetadata(
                                    toolEvent.getReplyId(),
                                    toolEvent.getToolCallId(),
                                    toolEvent.getToolCallName()));
            metadata.put(
                    "state",
                    toolEvent.getState() == null ? "unknown" : toolEvent.getState().getValue());
            return AgentStreamChunk.trace("tool_result_end", "", SOURCE, metadata);
        }
        if (event instanceof ModelCallStartEvent modelEvent) {
            return AgentStreamChunk.trace(
                    "model_call_start",
                    "",
                    SOURCE,
                    Map.of("replyId", modelEvent.getReplyId()));
        }
        if (event instanceof ModelCallEndEvent modelEvent) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("replyId", modelEvent.getReplyId());
            if (modelEvent.getUsage() != null) {
                metadata.put("inputTokens", modelEvent.getUsage().getInputTokens());
                metadata.put("outputTokens", modelEvent.getUsage().getOutputTokens());
                metadata.put("cachedTokens", modelEvent.getUsage().getCachedTokens());
                metadata.put(
                        "modelTimeMs",
                        Math.round(modelEvent.getUsage().getTime() * 1000.0d));
            }
            return AgentStreamChunk.trace("model_call_end", "", SOURCE, metadata);
        }
        return null;
    }

    private static Map<String, Object> toolMetadata(
            String replyId, String toolCallId, String toolName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("replyId", replyId);
        metadata.put("toolCallId", toolCallId);
        metadata.put("toolName", toolName);
        return metadata;
    }

    @Override
    public void close() {
        agent.close();
    }

    private static String normalizeId(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

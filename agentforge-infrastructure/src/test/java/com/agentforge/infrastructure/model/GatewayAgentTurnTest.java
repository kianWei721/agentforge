package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.AgentStreamChunk;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.agentforge.runtime.RetrievalBudgetOptions;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A whole agent turn on the gateway model: the real HarnessAgent, a fake gateway scripted to ask
 * for a tool and then answer, and a tool. Nothing reaches a vendor.
 */
class GatewayAgentTurnTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;

    @Test
    void aToolTurnRunsThroughTheGatewayUnderOneCorrelationId() throws Exception {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.then(
                            200,
                            """
                            {"id":"c1","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                              "content":null,"tool_calls":[{"id":"call_1","type":"function",
                              "function":{"name":"lookup_order","arguments":"{\\"order\\":\\"A1\\"}"}}]}}],
                             "usage":{"prompt_tokens":200,"completion_tokens":20}}""")
                    .respond(200, FakeGateway.completion("订单 A1 已经发货，预计明天到。"), Map.of());

            AgentScopeHarnessRuntime runtime =
                    new AgentScopeHarnessRuntime(
                            "orders",
                            "订单助手",
                            "你是订单助手，查订单要调用 lookup_order。",
                            new GatewayChatModel(gateway.client(), "agent", 1024, Duration.ofSeconds(10), JSON),
                            List.of(orderTool(toolCalls)),
                            new InMemoryAgentStateStore(),
                            ToolGovernanceOptions.defaults(),
                            new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                            null,
                            RetrievalBudgetOptions.defaults(),
                            new HarnessMemoryOptions(workspace, 50, 12000, 20, 800));

            List<AgentStreamChunk> chunks =
                    runtime.stream(new AgentRuntimeRequest("user-1", "session-1", "订单 A1 到哪了", "exec-1", "api"))
                            .collectList()
                            .block(Duration.ofSeconds(30));
            runtime.close();

            assertEquals(List.of(Map.of("order", "A1")), toolCalls, "the tool ran with the model's arguments");
            List<String> types = chunks.stream().map(AgentStreamChunk::type).toList();
            assertTrue(types.contains("tool_call_start"), types.toString());
            String answer =
                    chunks.stream().filter(c -> "message".equals(c.type())).map(AgentStreamChunk::content).reduce("", String::concat);
            assertEquals("订单 A1 已经发货，预计明天到。", answer, "a non-streamed answer still reaches the stream");
            assertEquals("done", types.get(types.size() - 1));

            assertEquals(2, gateway.requests().size());
            for (FakeGateway.Recorded request : gateway.requests()) {
                assertEquals(
                        "ag-exec-1",
                        request.header(ModelGatewayClient.CORRELATION_HEADER),
                        "every model call of the turn is found by one id in the gateway ledger");
            }
            JsonNode second = JSON.readTree(gateway.requests().get(1).body()).path("messages");
            JsonNode toolResult = null;
            for (JsonNode message : second) {
                if ("tool".equals(message.path("role").asText())) {
                    toolResult = message;
                }
            }
            assertNotNull(toolResult, "the second call carries the tool result");
            assertEquals("call_1", toolResult.path("tool_call_id").asText());
            assertTrue(toolResult.path("content").asText().contains("已发货"));
        }
    }

    /**
     * AgentScope 2.0.0's compaction middleware treats a failed model call as a failed compaction
     * and runs the reasoning step again. A call whose outcome is unknown may have been billed: the
     * model answers the repeat with the same failure instead of sending it.
     */
    @Test
    void aFailedCallIsNotSentAgainWhenAgentScopeRepeatsTheReasoningStep() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(504, FakeGateway.error("UPSTREAM_OUTCOME_UNKNOWN"), Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-x"));
            java.util.concurrent.atomic.AtomicInteger streamCalls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger subscriptions = new java.util.concurrent.atomic.AtomicInteger();
            GatewayChatModel inner = new GatewayChatModel(gateway.client(), "agent", 1024, Duration.ofSeconds(10), JSON);
            io.agentscope.core.model.Model counting = new io.agentscope.core.model.Model() {
                @Override
                public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                        List<io.agentscope.core.message.Msg> m, List<io.agentscope.core.model.ToolSchema> t,
                        io.agentscope.core.model.GenerateOptions o) {
                    streamCalls.incrementAndGet();
                    return inner.stream(m, t, o).doOnSubscribe(x -> subscriptions.incrementAndGet());
                }
                @Override
                public String getModelName() {
                    return inner.getModelName();
                }
            };
            AgentScopeHarnessRuntime runtime =
                    new AgentScopeHarnessRuntime(
                            "orders", "订单助手", "你是订单助手。",
                            counting,
                            List.of(orderTool(new ArrayList<>())),
                            new InMemoryAgentStateStore(),
                            ToolGovernanceOptions.defaults(),
                            new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                            null,
                            RetrievalBudgetOptions.defaults(),
                            new HarnessMemoryOptions(workspace, 50, 12000, 20, 800));

            Throwable failure = null;
            try {
                runtime.stream(new AgentRuntimeRequest("user-1", "session-1", "订单 A1 到哪了", "exec-2", "api"))
                        .collectList()
                        .block(Duration.ofSeconds(30));
            } catch (RuntimeException e) {
                failure = e;
            }
            runtime.close();

            assertNotNull(failure, "the turn fails");
            assertEquals(2, streamCalls.get(), "AgentScope asks the model twice");
            assertEquals(1, gateway.requests().size(), "but the possibly billed request is sent once");
        }
    }

    private static AgentTool orderTool(List<Map<String, Object>> calls) {
        return new AgentTool() {
            @Override
            public String name() {
                return "lookup_order";
            }

            @Override
            public String description() {
                return "按订单号查物流状态";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("order", Map.of("type", "string", "description", "订单号")),
                        "required", List.of("order"));
            }

            @Override
            public ToolResult execute(ToolInvocationContext context, Map<String, Object> arguments) {
                calls.add(arguments);
                return ToolResult.success("订单 " + arguments.get("order") + " 已发货", Map.of());
            }
        };
    }
}

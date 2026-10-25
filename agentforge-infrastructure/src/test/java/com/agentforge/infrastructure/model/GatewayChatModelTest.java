package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.runtime.ModelCallContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

class GatewayChatModelTest {

    /**
     * The model gateway's {@code RequestFields.STANDARD}: anything else is refused there with
     * UNSUPPORTED_FIELD. Keep in step with the gateway.
     */
    private static final Set<String> GATEWAY_FIELDS =
            Set.of("model", "messages", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop",
                    "stream", "stream_options", "tools", "tool_choice", "parallel_tool_calls", "response_format",
                    "seed", "presence_penalty", "frequency_penalty", "logprobs", "top_logprobs", "logit_bias",
                    "user", "n");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolSchema SEARCH =
            ToolSchema.builder()
                    .name("search_knowledge")
                    .description("查知识库")
                    .parameters(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))
                    .build();

    private FakeGateway gateway;
    private GatewayChatModel model;

    @BeforeEach
    void setUp() throws Exception {
        gateway = FakeGateway.start();
        model = new GatewayChatModel(gateway.client(), "agent", 2048, Duration.ofSeconds(10), JSON);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
    }

    @Test
    void aToolTurnIsSentInOpenAiShapeWithOnlyFieldsTheGatewayTakes() {
        List<Msg> history =
                List.of(
                        msg(MsgRole.SYSTEM, text("你是助手")),
                        msg(MsgRole.USER, text("发票怎么开")),
                        msg(MsgRole.ASSISTANT,
                                ThinkingBlock.builder().thinking("想一想").build(),
                                new ToolUseBlock("call_1", "search_knowledge", Map.of("query", "开票"), "{\"query\":\"开票\"}", Map.of())),
                        msg(MsgRole.TOOL, ToolResultBlock.of("call_1", "search_knowledge", text("开票流程……"))),
                        msg(MsgRole.ASSISTANT, text("按流程开票即可。")));

        ObjectNode body =
                model.body(history, List.of(SEARCH), GenerateOptions.builder().temperature(0.2).toolChoice(new ToolChoice.Auto()).build());

        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertTrue(GATEWAY_FIELDS.containsAll(fields), "the gateway refuses anything else: " + fields);
        assertEquals("agent", body.path("model").asText());
        assertEquals(2048, body.path("max_tokens").asInt(), "always sent: the gateway reserves budget by it");
        assertFalse(body.path("parallel_tool_calls").asBoolean(true), "one call at a time, as before");
        assertEquals("auto", body.path("tool_choice").asText());
        assertEquals("search_knowledge", body.path("tools").path(0).path("function").path("name").asText());

        JsonNode messages = body.path("messages");
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"), roles(messages));
        JsonNode call = messages.path(2);
        assertTrue(call.path("content").isNull(), "the thinking is dropped, so there is no text");
        assertEquals("call_1", call.path("tool_calls").path(0).path("id").asText());
        assertEquals("{\"query\":\"开票\"}", call.path("tool_calls").path(0).path("function").path("arguments").asText());
        assertEquals("call_1", messages.path(3).path("tool_call_id").asText());
        assertEquals("开票流程……", messages.path(3).path("content").asText());
        assertFalse(body.toString().contains("想一想"), "thinking never leaves");
    }

    @Test
    void toolCallsAndResultsThatLostTheirOtherHalfAreLeftOut() {
        List<Msg> history =
                List.of(
                        // Compaction kept the result but cut the call.
                        msg(MsgRole.TOOL, ToolResultBlock.of("call_0", "search_knowledge", text("旧结果"))),
                        msg(MsgRole.USER, text("再查一下")),
                        // Two calls; only one was answered, the other waits for confirmation.
                        msg(MsgRole.ASSISTANT,
                                new ToolUseBlock("call_1", "search_knowledge", Map.of("query", "a")),
                                new ToolUseBlock("call_2", "set_lure_lamp_state", Map.of("on", true))),
                        msg(MsgRole.TOOL,
                                ToolResultBlock.of("call_1", "search_knowledge", text("结果 a")),
                                ToolResultBlock.suspended(new ToolUseBlock("call_2", "set_lure_lamp_state", Map.of("on", true)))));

        JsonNode messages = model.body(history, List.of(SEARCH), null).path("messages");

        assertEquals(List.of("user", "assistant", "tool"), roles(messages));
        assertEquals(1, messages.path(1).path("tool_calls").size());
        assertEquals("call_1", messages.path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("call_1", messages.path(2).path("tool_call_id").asText());
    }

    @Test
    void mediaIsRefusedRatherThanSentAsSomethingElse() {
        List<Msg> history = List.of(msg(MsgRole.USER, ImageBlock.builder().source(new io.agentscope.core.message.URLSource("https://example.com/invoice.png")).build()));

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> model.body(history, List.of(), null));
        assertTrue(refused.getMessage().startsWith("UNSUPPORTED_CONTENT"));
    }

    @Test
    void theAnswerBecomesTextAndToolCallsWithItsUsageAndTheContextsCorrelationId() {
        gateway.respond(
                200,
                """
                {"id":"chatcmpl-9","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                  "content":"我查一下。","tool_calls":[
                    {"id":"call_a","type":"function","function":{"name":"search_knowledge","arguments":"{\\"query\\":\\"发票\\"}"}},
                    {"id":"call_b","type":"function","function":{"name":"search_knowledge","arguments":"not json"}}]}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":64}}}""",
                Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-7", "X-Gateway-Served-Model", "qwen3.8-flash"));

        ChatResponse response =
                model.stream(List.of(msg(MsgRole.USER, text("发票"))), List.of(SEARCH), null)
                        .contextWrite(Context.of(ModelCallContext.CORRELATION_ID, "ag-exec-1"))
                        .blockLast();

        assertEquals("ag-exec-1", gateway.lastRequest().header(ModelGatewayClient.CORRELATION_HEADER));
        assertNotNull(gateway.lastRequest().header(ModelGatewayClient.DEADLINE_HEADER));
        List<ContentBlock> content = response.getContent();
        assertEquals("我查一下。", ((TextBlock) content.get(0)).getText());
        ToolUseBlock first = (ToolUseBlock) content.get(1);
        assertEquals("call_a", first.getId());
        assertEquals(Map.of("query", "发票"), first.getInput());
        ToolUseBlock broken = (ToolUseBlock) content.get(2);
        assertEquals(Map.of(), broken.getInput(), "not JSON: the tool reports bad arguments, the turn goes on");
        assertEquals("not json", broken.getContent());
        assertEquals(120, response.getUsage().getInputTokens());
        assertEquals(30, response.getUsage().getOutputTokens());
        assertEquals(64, response.getUsage().getCachedTokens());
        assertEquals("tool_calls", response.getFinishReason());
        assertEquals("gw-7", response.getMetadata().get("gatewayRequestId"));
        assertEquals("gateway:agent", model.getModelName());
    }

    @Test
    void aRateLimitIsRetriedOnceAndAnUnknownOutcomeNever() {
        gateway.then(429, FakeGateway.error("RATE_LIMITED")).respond(200, FakeGateway.completion("好的"), Map.of());

        ChatResponse answered = model.stream(List.of(msg(MsgRole.USER, text("hi"))), List.of(), null).blockLast();
        assertEquals("好的", ((TextBlock) answered.getContent().get(0)).getText());
        assertEquals(2, gateway.requests().size());

        gateway.requests().clear();
        gateway.respond(504, FakeGateway.error("UPSTREAM_OUTCOME_UNKNOWN"), Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-8"));
        ModelCallFailedException failed =
                assertThrows(
                        ModelCallFailedException.class,
                        () -> model.stream(List.of(msg(MsgRole.USER, text("hi"))), List.of(), null).blockLast());
        assertEquals("UPSTREAM_OUTCOME_UNKNOWN", failed.errorCode());
        assertEquals("gw-8", failed.gatewayRequestId());
        assertEquals(1, gateway.requests().size(), "possibly billed already: never sent twice");
    }

    @Test
    void theSameRequestInTheSameTurnIsNotSentAfterItFailed() {
        gateway.respond(504, FakeGateway.error("UPSTREAM_OUTCOME_UNKNOWN"), Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-9"));
        List<Msg> history = List.of(msg(MsgRole.USER, text("hi")));

        for (int i = 0; i < 2; i++) {
            ModelCallFailedException failed =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> model.stream(history, List.of(), null)
                                    .contextWrite(Context.of(ModelCallContext.CORRELATION_ID, "ag-exec-3"))
                                    .blockLast());
            assertEquals("gw-9", failed.gatewayRequestId(), "the same failure, with its request id");
        }
        assertEquals(1, gateway.requests().size());

        // Another turn, or another request in the same turn, is sent.
        assertThrows(ModelCallFailedException.class,
                () -> model.stream(history, List.of(), null)
                        .contextWrite(Context.of(ModelCallContext.CORRELATION_ID, "ag-exec-4")).blockLast());
        assertThrows(ModelCallFailedException.class,
                () -> model.stream(List.of(msg(MsgRole.USER, text("hello"))), List.of(), null)
                        .contextWrite(Context.of(ModelCallContext.CORRELATION_ID, "ag-exec-3")).blockLast());
        assertEquals(3, gateway.requests().size());
    }

    private static Msg msg(MsgRole role, ContentBlock... blocks) {
        return Msg.builder().role(role).content(List.of(blocks)).build();
    }

    private static TextBlock text(String text) {
        return TextBlock.builder().text(text).build();
    }

    private static List<String> roles(JsonNode messages) {
        List<String> roles = new ArrayList<>();
        for (Iterator<JsonNode> it = messages.elements(); it.hasNext(); ) {
            roles.add(it.next().path("role").asText());
        }
        return roles;
    }
}

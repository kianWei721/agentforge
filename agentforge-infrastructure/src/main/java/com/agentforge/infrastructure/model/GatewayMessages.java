package com.agentforge.infrastructure.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentScope messages to the OpenAI chat format the model gateway takes, and the answer back.
 *
 * <p>Only standard fields are sent: the gateway refuses vendor switches. Thinking is dropped: the
 * gateway configures it per model. Media is refused rather than sent as something it is not.
 */
final class GatewayMessages {

    private static final System.Logger LOG = System.getLogger(GatewayMessages.class.getName());
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private GatewayMessages() {}

    /** The messages array. Tool calls and results that lost their other half are left out. */
    static ArrayNode messages(List<Msg> messages, ObjectMapper json) {
        List<ObjectNode> out = new ArrayList<>();
        for (Msg msg : messages) {
            List<ContentBlock> blocks = msg.getContent() == null ? List.of() : msg.getContent();
            switch (msg.getRole()) {
                case SYSTEM -> addText(out, json, "system", blocks);
                case ASSISTANT -> addAssistant(out, json, blocks);
                case USER, TOOL -> {
                    // Results answer the calls just before them, so they come first.
                    addToolResults(out, json, blocks);
                    addText(out, json, msg.getRole() == MsgRole.USER ? "user" : "tool", blocks);
                }
            }
        }
        ArrayNode array = json.createArrayNode();
        pair(out).forEach(array::add);
        return array;
    }

    private static void addText(List<ObjectNode> out, ObjectMapper json, String role, List<ContentBlock> blocks) {
        String text = text(blocks);
        if (text.isEmpty() || "tool".equals(role)) {
            return;
        }
        out.add(json.createObjectNode().put("role", role).put("content", text));
    }

    private static void addAssistant(List<ObjectNode> out, ObjectMapper json, List<ContentBlock> blocks) {
        String text = text(blocks);
        ArrayNode calls = json.createArrayNode();
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock call) {
                ObjectNode function = json.createObjectNode().put("name", call.getName());
                function.put("arguments", arguments(call, json));
                calls.addObject().put("id", call.getId()).put("type", "function").set("function", function);
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            return;
        }
        ObjectNode message = json.createObjectNode().put("role", "assistant");
        if (text.isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", text);
        }
        if (!calls.isEmpty()) {
            message.set("tool_calls", calls);
        }
        out.add(message);
    }

    private static void addToolResults(List<ObjectNode> out, ObjectMapper json, List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            // A suspended result is a call still waiting for confirmation: no answer yet.
            if (block instanceof ToolResultBlock result && !result.isSuspended()) {
                out.add(json.createObjectNode()
                        .put("role", "tool")
                        .put("tool_call_id", result.getId())
                        .put("content", toolOutput(result.getOutput())));
            }
        }
    }

    /**
     * Keeps only calls that have a result and results that answer a call. Compaction can cut a
     * call from its result and a pending confirmation leaves a call without one; an
     * OpenAI-compatible upstream refuses either, which would fail the whole turn.
     */
    private static List<ObjectNode> pair(List<ObjectNode> messages) {
        Set<String> answered = new HashSet<>();
        for (ObjectNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                answered.add(message.path("tool_call_id").asText());
            }
        }
        List<ObjectNode> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = 0;
        for (ObjectNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                if (!seen.contains(message.path("tool_call_id").asText())) {
                    dropped++;
                    continue;
                }
            } else if (message.has("tool_calls")) {
                ArrayNode calls = (ArrayNode) message.get("tool_calls");
                for (Iterator<JsonNode> it = calls.iterator(); it.hasNext(); ) {
                    String id = it.next().path("id").asText();
                    if (answered.contains(id)) {
                        seen.add(id);
                    } else {
                        it.remove();
                        dropped++;
                    }
                }
                if (calls.isEmpty()) {
                    message.remove("tool_calls");
                    if (message.path("content").isNull()) {
                        continue;
                    }
                }
            }
            kept.add(message);
        }
        if (dropped > 0) {
            LOG.log(System.Logger.Level.WARNING,
                    "Left out " + dropped + " unpaired tool call(s) or result(s) from a gateway model request");
        }
        return kept;
    }

    static ArrayNode tools(List<ToolSchema> tools, ObjectMapper json) {
        ArrayNode array = json.createArrayNode();
        for (ToolSchema tool : tools) {
            ObjectNode function = json.createObjectNode().put("name", tool.getName());
            if (tool.getDescription() != null) {
                function.put("description", tool.getDescription());
            }
            function.set(
                    "parameters",
                    tool.getParameters() == null
                            ? json.createObjectNode().put("type", "object")
                            : json.valueToTree(tool.getParameters()));
            array.addObject().put("type", "function").set("function", function);
        }
        return array;
    }

    static JsonNode toolChoice(ToolChoice choice, ObjectMapper json) {
        if (choice instanceof ToolChoice.Specific specific) {
            ObjectNode node = json.createObjectNode().put("type", "function");
            node.putObject("function").put("name", specific.toolName());
            return node;
        }
        if (choice instanceof ToolChoice.None) {
            return json.getNodeFactory().textNode("none");
        }
        if (choice instanceof ToolChoice.Required) {
            return json.getNodeFactory().textNode("required");
        }
        return json.getNodeFactory().textNode("auto");
    }

    /** {@code choices[0]} of a gateway answer as one complete response. */
    static ChatResponse response(
            JsonNode body, String gatewayRequestId, String servedModel, double seconds, ObjectMapper json) {
        JsonNode choice = body.path("choices").path(0);
        JsonNode message = choice.path("message");
        List<ContentBlock> content = new ArrayList<>();
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (!text.isEmpty()) {
            content.add(TextBlock.builder().text(text).build());
        }
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String raw = function.path("arguments").asText("");
            content.add(
                    new ToolUseBlock(
                            call.path("id").asText(),
                            function.path("name").asText(),
                            input(raw, json),
                            raw,
                            Map.of()));
        }
        JsonNode usage = body.path("usage");
        ChatUsage chatUsage =
                ChatUsage.builder()
                        .inputTokens(usage.path("prompt_tokens").asInt(0))
                        .outputTokens(usage.path("completion_tokens").asInt(0))
                        .cachedTokens(usage.path("prompt_tokens_details").path("cached_tokens").asInt(0))
                        .time(seconds)
                        .build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (gatewayRequestId != null) {
            metadata.put("gatewayRequestId", gatewayRequestId);
        }
        if (servedModel != null) {
            metadata.put("servedModel", servedModel);
        }
        return new ChatResponse(
                body.path("id").asText(null),
                content,
                chatUsage,
                metadata,
                choice.path("finish_reason").asText(null));
    }

    /**
     * The arguments as an object. A model can emit arguments that are not JSON; then the input is
     * empty and the raw text stays on the block, so the tool reports bad arguments instead of the
     * turn failing here.
     */
    private static Map<String, Object> input(String raw, ObjectMapper json) {
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException notJson) {
            return Map.of();
        }
    }

    private static String arguments(ToolUseBlock call, ObjectMapper json) {
        if (call.getContent() != null && !call.getContent().isBlank()) {
            return call.getContent();
        }
        try {
            return json.writeValueAsString(call.getInput() == null ? Map.of() : call.getInput());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool call arguments are not serializable", exception);
        }
    }

    /** Text and hints; tool blocks are carried separately, thinking is dropped, media is refused. */
    private static String text(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            String part = null;
            if (block instanceof TextBlock textBlock) {
                part = textBlock.getText();
            } else if (block instanceof HintBlock hint) {
                part = hint.getHint();
            } else if (isMedia(block)) {
                throw new IllegalArgumentException(
                        "UNSUPPORTED_CONTENT: the model gateway path takes text only, got "
                                + block.getClass().getSimpleName());
            } else if (!(block instanceof ThinkingBlock
                    || block instanceof ToolUseBlock
                    || block instanceof ToolResultBlock)) {
                throw new IllegalArgumentException(
                        "UNSUPPORTED_CONTENT: " + block.getClass().getSimpleName());
            }
            if (part != null && !part.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part);
            }
        }
        return text.toString();
    }

    /** Tool output is text for AgentForge's tools; anything else is named, not sent. */
    private static String toolOutput(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : output) {
            String part =
                    block instanceof TextBlock textBlock
                            ? textBlock.getText()
                            : "[" + block.getClass().getSimpleName() + " omitted]";
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(part == null ? "" : part);
        }
        return text.toString();
    }

    private static boolean isMedia(ContentBlock block) {
        return block instanceof ImageBlock
                || block instanceof AudioBlock
                || block instanceof VideoBlock
                || block instanceof DataBlock;
    }
}

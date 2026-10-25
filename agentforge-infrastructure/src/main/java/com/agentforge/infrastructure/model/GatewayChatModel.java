package com.agentforge.infrastructure.model;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.runtime.ModelCallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * An AgentScope model whose every call goes through the model gateway, never to a vendor directly.
 *
 * <p>The gateway does not stream yet, so each call returns the whole answer as one response rather
 * than a faked token stream. Failover between models is the gateway's; this adds at most one retry,
 * and only for errors where nothing can have been billed. The direct DashScope model retries up to
 * three times on its own; that retry is gone here on purpose: a call whose outcome is unknown may
 * already have been paid for.
 *
 * <p>AgentScope 2.0.0's compaction middleware runs compaction and the reasoning step in one chain
 * with one error handler: when the model call fails, it logs "Compaction failed, continuing without
 * compaction" and runs the reasoning step again, so the failed request is sent a second time. Within
 * one turn, the same request after a failure is therefore answered with that failure, unsent.
 */
public final class GatewayChatModel implements Model {

    private static final System.Logger LOG = System.getLogger(GatewayChatModel.class.getName());
    private static final Set<String> RETRIED = Set.of("RATE_LIMITED", "NO_UPSTREAM_AVAILABLE", "GATEWAY_UNREACHABLE");
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);
    private static final Duration FAILURE_MEMORY = Duration.ofMinutes(5);
    private static final int FAILURES_KEPT = 1000;

    private final ModelGatewayClient gateway;
    private final String route;
    private final int maxTokens;
    private final Duration timeout;
    private final ObjectMapper json;

    /** Turn and request to the failure it ended with; bounded, oldest out first. */
    private final Map<String, Failed> failures =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Failed> eldest) {
                    return size() > FAILURES_KEPT;
                }
            };

    private record Failed(ModelCallFailedException failure, long atNanos) {}

    /**
     * @param route the gateway route; it must offer only models that call tools
     * @param maxTokens sent with every call: the gateway reserves budget by it
     * @param timeout for one model call, its retry included
     */
    public GatewayChatModel(
            ModelGatewayClient gateway, String route, int maxTokens, Duration timeout, ObjectMapper json) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("gateway route must not be blank");
        }
        if (maxTokens < 1 || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("max tokens and timeout must be positive");
        }
        this.gateway = gateway;
        this.route = route.trim();
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.json = json;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(
                context -> {
                    String turn = context.getOrDefault(ModelCallContext.CORRELATION_ID, null);
                    String correlationId = turn != null ? turn : "ag-" + UUID.randomUUID();
                    ObjectNode body = body(messages, tools, options);
                    // Without a turn id every call is new, so there is nothing to recognise.
                    String key = turn == null ? null : turn + ":" + digest(body);
                    return Mono.fromCallable(() -> call(body, correlationId, key))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flux();
                });
    }

    /** @return null when the turn was cancelled meanwhile: nobody is waiting for the answer */
    private ChatResponse call(ObjectNode body, String correlationId, String key) {
        ModelCallFailedException earlier = earlierFailure(key);
        if (earlier != null) {
            throw earlier;
        }
        long started = System.nanoTime();
        try {
            ModelGatewayClient.Completion completion;
            try {
                completion = gateway.complete(body, correlationId, timeout);
            } catch (ModelCallFailedException failure) {
                Duration left = timeout.minus(Duration.ofNanos(System.nanoTime() - started));
                if (!RETRIED.contains(failure.errorCode()) || left.minus(RETRY_BACKOFF).compareTo(timeout.dividedBy(2)) < 0) {
                    throw failure;
                }
                Thread.sleep(RETRY_BACKOFF.toMillis());
                completion = gateway.complete(body, correlationId, left.minus(RETRY_BACKOFF));
            }
            ChatResponse response =
                    GatewayMessages.response(
                            completion.body(),
                            completion.gatewayRequestId(),
                            completion.servedModel(),
                            (System.nanoTime() - started) / 1e9,
                            json);
            if ("length".equals(response.getFinishReason())) {
                LOG.log(System.Logger.Level.WARNING,
                        "Gateway answer cut at max_tokens=" + body.path("max_tokens").asInt()
                                + " (request " + completion.gatewayRequestId() + ", " + correlationId + ")");
            }
            return response;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ModelCallFailedException failure) {
            if ("GATEWAY_INTERRUPTED".equals(failure.errorCode())) {
                return null;
            }
            remember(key, failure);
            throw failure;
        }
    }

    private ModelCallFailedException earlierFailure(String key) {
        if (key == null) {
            return null;
        }
        synchronized (failures) {
            Failed failed = failures.get(key);
            if (failed == null || System.nanoTime() - failed.atNanos() > FAILURE_MEMORY.toNanos()) {
                return null;
            }
            LOG.log(System.Logger.Level.INFO,
                    "Not sending a request that already failed in this turn (" + failed.failure().errorCode() + ")");
            return failed.failure();
        }
    }

    private void remember(String key, ModelCallFailedException failure) {
        if (key != null) {
            synchronized (failures) {
                failures.put(key, new Failed(failure, System.nanoTime()));
            }
        }
    }

    ObjectNode body(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ObjectNode body = json.createObjectNode();
        body.put("model", route);
        body.set("messages", GatewayMessages.messages(messages, json));
        Integer requested = options == null ? null : firstNonNull(options.getMaxTokens(), options.getMaxCompletionTokens());
        body.put("max_tokens", requested == null ? maxTokens : Math.min(requested, maxTokens));
        if (options != null && options.getTemperature() != null) {
            body.put("temperature", options.getTemperature());
        }
        if (options != null && options.getTopP() != null) {
            body.put("top_p", options.getTopP());
        }
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", GatewayMessages.tools(tools, json));
            if (options != null && options.getToolChoice() != null) {
                body.set("tool_choice", GatewayMessages.toolChoice(options.getToolChoice(), json));
            }
            // One call at a time, as with the direct model: tool governance and the retrieval
            // budget count calls in order.
            body.put(
                    "parallel_tool_calls",
                    options != null && Boolean.TRUE.equals(options.getParallelToolCalls()));
        }
        return body;
    }

    private static String digest(ObjectNode body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    @Override
    public String getModelName() {
        return "gateway:" + route;
    }
}

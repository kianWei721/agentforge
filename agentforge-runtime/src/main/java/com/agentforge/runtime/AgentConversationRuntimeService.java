package com.agentforge.runtime;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.observability.execution.TraceEventInput;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

public class AgentConversationRuntimeService {

    private static final String USER_MESSAGE_SOURCE = "api";
    private static final String DEFAULT_ASSISTANT_SOURCE = "agentscope-harness";

    private final AgentDefinitionRepository agentRepository;
    private final ConversationService conversationService;
    private final AgentRuntimeManager runtimeManager;
    private final ExecutionService executionService;

    public AgentConversationRuntimeService(
            AgentDefinitionRepository agentRepository,
            ConversationService conversationService,
            AgentRuntimeManager runtimeManager,
            ExecutionService executionService) {
        this.agentRepository = agentRepository;
        this.conversationService = conversationService;
        this.runtimeManager = runtimeManager;
        this.executionService = executionService;
    }

    public Flux<AgentStreamChunk> stream(String agentId, AgentRuntimeRequest request) {
        return Flux.defer(
                        () -> {
                            AgentDefinition definition =
                                    agentRepository
                                            .findById(agentId)
                                            .orElseThrow(() -> new AgentNotFoundException(agentId));

                            if (!definition.enabled()) {
                                return Flux.error(
                                        new IllegalStateException("Agent is disabled: " + agentId));
                            }

                            String userId = conversationService.normalizeUserId(request.userId());
                            String sessionKey =
                                    conversationService.normalizeSessionKey(request.sessionId());
                            ConversationSession session =
                                    conversationService.openSession(agentId, userId, sessionKey);

                            ConversationMessage userMessage =
                                    conversationService.appendMessage(
                                            session.id(),
                                            ConversationRole.USER,
                                            request.message(),
                                            USER_MESSAGE_SOURCE);

                            AgentExecution execution =
                                    executionService.start(
                                            session.id(),
                                            agentId,
                                            userId,
                                            userMessage.id(),
                                            definition.modelProvider(),
                                            definition.modelName());

                            StringBuilder assistantText = new StringBuilder();
                            AtomicReference<String> assistantSource =
                                    new AtomicReference<>(DEFAULT_ASSISTANT_SOURCE);
                            AtomicReference<String> doneSource =
                                    new AtomicReference<>(DEFAULT_ASSISTANT_SOURCE);

                            AgentRuntimeRequest scopedRequest =
                                    new AgentRuntimeRequest(
                                            userId,
                                            session.id(),
                                            request.message());

                            Flux<AgentStreamChunk> runtimeStream =
                                    runtimeManager
                                            .stream(agentId, scopedRequest)
                                            .concatMap(
                                                    chunk -> {
                                                        captureAssistant(
                                                                chunk,
                                                                assistantText,
                                                                assistantSource);
                                                        if ("done".equals(chunk.type())) {
                                                            if (chunk.source() != null
                                                                    && !chunk.source().isBlank()) {
                                                                doneSource.set(chunk.source());
                                                            }
                                                            return Mono.empty();
                                                        }
                                                        if (!isPersistedTrace(chunk)) {
                                                            return Mono.just(chunk);
                                                        }
                                                        return Mono.fromCallable(
                                                                        () -> {
                                                                            executionService
                                                                                    .appendTrace(
                                                                                            execution.id(),
                                                                                            toTraceInput(
                                                                                                    chunk));
                                                                            return chunk;
                                                                        })
                                                                .subscribeOn(
                                                                        Schedulers.boundedElastic());
                                                    });

                            Flux<AgentStreamChunk> successTail =
                                    Mono.fromCallable(
                                                    () -> {
                                                        String assistantMessageId = null;
                                                        if (!assistantText.toString().isBlank()) {
                                                            ConversationMessage assistantMessage =
                                                                    conversationService
                                                                            .appendMessage(
                                                                                    session.id(),
                                                                                    ConversationRole
                                                                                            .ASSISTANT,
                                                                                    assistantText
                                                                                            .toString(),
                                                                                    assistantSource
                                                                                            .get());
                                                            assistantMessageId =
                                                                    assistantMessage.id();
                                                        }
                                                        executionService.succeed(
                                                                execution.id(),
                                                                assistantMessageId);
                                                        return AgentStreamChunk.done(
                                                                doneSource.get());
                                                    })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .flux();

                            return runtimeStream
                                    .concatWith(successTail)
                                    .onErrorResume(
                                            error ->
                                                    Mono.fromRunnable(
                                                                    () ->
                                                                            executionService.fail(
                                                                                    execution.id(),
                                                                                    error))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic())
                                                            .then(Mono.error(error)))
                                    .doFinally(
                                            signalType -> {
                                                if (signalType == SignalType.CANCEL) {
                                                    Schedulers.boundedElastic()
                                                            .schedule(
                                                                    () ->
                                                                            executionService.cancel(
                                                                                    execution.id()));
                                                }
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void captureAssistant(
            AgentStreamChunk chunk,
            StringBuilder assistantText,
            AtomicReference<String> assistantSource) {
        if (!"message".equals(chunk.type()) || chunk.content().isEmpty()) {
            return;
        }
        assistantText.append(chunk.content());
        if (chunk.source() != null && !chunk.source().isBlank()) {
            assistantSource.set(chunk.source());
        }
    }

    private static boolean isPersistedTrace(AgentStreamChunk chunk) {
        return !"message".equals(chunk.type()) && !"done".equals(chunk.type());
    }

    private static TraceEventInput toTraceInput(AgentStreamChunk chunk) {
        Map<String, Object> metadata = chunk.metadata();
        return new TraceEventInput(
                chunk.type(),
                chunk.source(),
                chunk.content(),
                stringMetadata(metadata, "toolCallId"),
                stringMetadata(metadata, "toolName"),
                stringMetadata(metadata, "replyId"),
                stringMetadata(metadata, "state"),
                stringMetadata(metadata, "usage"),
                integerMetadata(metadata, "inputTokens"),
                integerMetadata(metadata, "outputTokens"),
                integerMetadata(metadata, "cachedTokens"),
                longMetadata(metadata, "modelTimeMs"));
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integerMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static Long longMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}

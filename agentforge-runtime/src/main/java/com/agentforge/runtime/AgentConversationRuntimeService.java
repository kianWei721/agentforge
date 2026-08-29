package com.agentforge.runtime;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class AgentConversationRuntimeService {

    private static final String USER_MESSAGE_SOURCE = "api";
    private static final String DEFAULT_ASSISTANT_SOURCE = "agentscope-harness";

    private final AgentDefinitionRepository agentRepository;
    private final ConversationService conversationService;
    private final AgentRuntimeManager runtimeManager;

    public AgentConversationRuntimeService(
            AgentDefinitionRepository agentRepository,
            ConversationService conversationService,
            AgentRuntimeManager runtimeManager) {
        this.agentRepository = agentRepository;
        this.conversationService = conversationService;
        this.runtimeManager = runtimeManager;
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

                            conversationService.appendMessage(
                                    session.id(),
                                    ConversationRole.USER,
                                    request.message(),
                                    USER_MESSAGE_SOURCE);

                            StringBuilder assistantText = new StringBuilder();
                            AtomicReference<String> assistantSource =
                                    new AtomicReference<>(DEFAULT_ASSISTANT_SOURCE);

                            AgentRuntimeRequest scopedRequest =
                                    new AgentRuntimeRequest(
                                            userId,
                                            session.id(),
                                            request.message());

                            Flux<AgentStreamChunk> runtimeStream =
                                    runtimeManager
                                            .stream(agentId, scopedRequest)
                                            .doOnNext(
                                                    chunk -> {
                                                        if ("message".equals(chunk.type())
                                                                && !chunk.content().isEmpty()) {
                                                            assistantText.append(chunk.content());
                                                            if (chunk.source() != null
                                                                    && !chunk.source().isBlank()) {
                                                                assistantSource.set(chunk.source());
                                                            }
                                                        }
                                                    });

                            Flux<AgentStreamChunk> persistAssistant =
                                    Flux.defer(
                                                    () -> {
                                                        if (!assistantText.toString().isBlank()) {
                                                            conversationService.appendMessage(
                                                                    session.id(),
                                                                    ConversationRole.ASSISTANT,
                                                                    assistantText.toString(),
                                                                    assistantSource.get());
                                                        }
                                                        return Flux.<AgentStreamChunk>empty();
                                                    })
                                            .subscribeOn(Schedulers.boundedElastic());

                            return runtimeStream.concatWith(persistAssistant);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

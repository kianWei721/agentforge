package com.agentforge.app.api;

import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionNotFoundException;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.observability.execution.ExecutionTraceEvent;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executions and traces are readable by their owner only. Traces carry raw tool results,
 * including retrieved knowledge chunks, so they need the same boundary as retrieval itself.
 */
@RestController
public class ExecutionController {

    private final ExecutionService executionService;
    private final ConversationService conversationService;

    public ExecutionController(
            ExecutionService executionService,
            ConversationService conversationService) {
        this.executionService = executionService;
        this.conversationService = conversationService;
    }

    @GetMapping(AgentForgeConstants.API_PREFIX + "/executions/{executionId}")
    public Mono<AgentExecution> get(
            @PathVariable("executionId") String executionId,
            AccessPrincipal principal) {
        return blocking(() -> ownedExecution(executionId, principal));
    }

    @GetMapping(AgentForgeConstants.API_PREFIX + "/executions/{executionId}/traces")
    public Mono<List<ExecutionTraceEvent>> traces(
            @PathVariable("executionId") String executionId,
            AccessPrincipal principal) {
        return blocking(
                () -> {
                    ownedExecution(executionId, principal);
                    return executionService.traces(executionId);
                });
    }

    @GetMapping(
            AgentForgeConstants.API_PREFIX
                    + "/agents/{agentId}/sessions/{sessionKey}/executions")
    public Mono<List<AgentExecution>> sessionExecutions(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            AccessPrincipal principal) {
        return blocking(
                () -> {
                    ConversationSession session =
                            conversationService.getSession(
                                    agentId, principal.userId(), sessionKey);
                    return executionService.listBySessionId(session.id());
                });
    }

    /** Another user's execution is reported exactly like a missing one. */
    private AgentExecution ownedExecution(String executionId, AccessPrincipal principal) {
        AgentExecution execution = executionService.get(executionId);
        if (!execution.userId().equals(principal.userId())) {
            throw new ExecutionNotFoundException(executionId);
        }
        return execution;
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

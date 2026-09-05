package com.agentforge.app.api;

import com.agentforge.app.security.AuthenticatedUser;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.observability.execution.ExecutionTraceEvent;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(() -> executionService.getForUser(executionId, user.userId()));
    }

    @GetMapping(AgentForgeConstants.API_PREFIX + "/executions/{executionId}/traces")
    public Mono<List<ExecutionTraceEvent>> traces(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(() -> executionService.tracesForUser(executionId, user.userId()));
    }

    @GetMapping(
            AgentForgeConstants.API_PREFIX
                    + "/agents/{agentId}/sessions/{sessionKey}/executions")
    public Mono<List<AgentExecution>> sessionExecutions(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(
                () -> {
                    ConversationSession session =
                            conversationService.getSession(agentId, user.userId(), sessionKey);
                    return executionService.listBySessionId(session.id());
                });
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

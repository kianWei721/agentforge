package com.agentforge.app.api;

import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.observability.execution.ExecutionTraceEvent;
import java.util.List;
import java.util.concurrent.Callable;
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
    public Mono<AgentExecution> get(@PathVariable("executionId") String executionId) {
        return blocking(() -> executionService.get(executionId));
    }

    @GetMapping(AgentForgeConstants.API_PREFIX + "/executions/{executionId}/traces")
    public Mono<List<ExecutionTraceEvent>> traces(
            @PathVariable("executionId") String executionId) {
        return blocking(() -> executionService.traces(executionId));
    }

    @GetMapping(
            AgentForgeConstants.API_PREFIX
                    + "/agents/{agentId}/sessions/{sessionKey}/executions")
    public Mono<List<AgentExecution>> sessionExecutions(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @RequestParam(name = "userId", required = false) String userId) {
        return blocking(
                () -> {
                    ConversationSession session =
                            conversationService.getSession(agentId, userId, sessionKey);
                    return executionService.listBySessionId(session.id());
                });
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

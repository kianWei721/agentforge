package com.agentforge.app.api;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessPrincipal;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sessions are keyed by (agentId, userId, sessionKey); the userId is always the caller's own. */
@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/agents/{agentId}/sessions")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public Mono<List<ConversationSession>> list(
            @PathVariable("agentId") String agentId,
            AccessPrincipal principal) {
        return blocking(() -> conversationService.listSessions(agentId, principal.userId()));
    }

    @GetMapping("/{sessionKey}")
    public Mono<ConversationSession> get(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            AccessPrincipal principal) {
        return blocking(
                () -> conversationService.getSession(agentId, principal.userId(), sessionKey));
    }

    @GetMapping("/{sessionKey}/messages")
    public Mono<List<ConversationMessage>> messages(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            AccessPrincipal principal) {
        return blocking(
                () -> conversationService.listMessages(agentId, principal.userId(), sessionKey));
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

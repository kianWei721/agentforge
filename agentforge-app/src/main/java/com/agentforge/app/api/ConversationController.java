package com.agentforge.app.api;

import com.agentforge.app.security.AuthenticatedUser;
import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(() -> conversationService.listSessions(agentId, user.userId()));
    }

    @GetMapping("/{sessionKey}")
    public Mono<ConversationSession> get(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(
                () -> conversationService.getSession(agentId, user.userId(), sessionKey));
    }

    @GetMapping("/{sessionKey}/messages")
    public Mono<List<ConversationMessage>> messages(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return blocking(
                () -> conversationService.listMessages(agentId, user.userId(), sessionKey));
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

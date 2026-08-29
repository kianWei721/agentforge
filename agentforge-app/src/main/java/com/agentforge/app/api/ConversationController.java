package com.agentforge.app.api;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import com.agentforge.core.AgentForgeConstants;
import java.util.List;
import java.util.concurrent.Callable;
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
            @RequestParam(name = "userId", required = false) String userId) {
        return blocking(() -> conversationService.listSessions(agentId, userId));
    }

    @GetMapping("/{sessionKey}")
    public Mono<ConversationSession> get(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @RequestParam(name = "userId", required = false) String userId) {
        return blocking(() -> conversationService.getSession(agentId, userId, sessionKey));
    }

    @GetMapping("/{sessionKey}/messages")
    public Mono<List<ConversationMessage>> messages(
            @PathVariable("agentId") String agentId,
            @PathVariable("sessionKey") String sessionKey,
            @RequestParam(name = "userId", required = false) String userId) {
        return blocking(() -> conversationService.listMessages(agentId, userId, sessionKey));
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

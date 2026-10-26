package com.agentforge.app.api;

import com.agentforge.channel.handoff.ChannelHandoffService;
import com.agentforge.channel.handoff.HandoffResolution;
import com.agentforge.channel.handoff.HandoffStatus;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessDeniedException;
import com.agentforge.core.security.AccessPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Staff work the WeCom hand-off queue here. Who claims or resolves is the caller's server-resolved
 * identity; nothing in the request names the actor.
 */
@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/channel-handoffs")
@ConditionalOnProperty(
        prefix = "agentforge.channel.wecom",
        name = "enabled",
        havingValue = "true")
public class ChannelHandoffController {

    private final ChannelHandoffService handoffs;

    public ChannelHandoffController(ChannelHandoffService handoffs) {
        this.handoffs = handoffs;
    }

    @GetMapping
    public Mono<List<ChannelHandoffView>> list(
            @RequestParam("status") String status,
            @RequestParam(value = "createdAfter", required = false) Instant createdAfter,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "listing hand-offs");
        HandoffStatus parsed = parse(HandoffStatus.class, status, "status");
        return blocking(
                () -> handoffs.list(parsed, createdAfter, limit).stream().map(ChannelHandoffView::from).toList());
    }

    @GetMapping("/{handoffId}")
    public Mono<ChannelHandoffView> get(@PathVariable("handoffId") String handoffId, AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "reading hand-offs");
        return blocking(() -> ChannelHandoffView.from(handoffs.get(handoffId)));
    }

    @PostMapping("/{handoffId}/claim")
    public Mono<ChannelHandoffView> claim(@PathVariable("handoffId") String handoffId, AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "claiming hand-offs");
        return blocking(() -> ChannelHandoffView.from(handoffs.claim(handoffId, principal.userId())));
    }

    @PostMapping("/{handoffId}/resolve")
    public Mono<ChannelHandoffView> resolve(
            @PathVariable("handoffId") String handoffId,
            @RequestBody ChannelHandoffResolveRequest request,
            AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "resolving hand-offs");
        HandoffResolution resolution = parse(HandoffResolution.class, request.resolution(), "resolution");
        return blocking(
                () ->
                        ChannelHandoffView.from(
                                handoffs.resolve(handoffId, principal.userId(), resolution, request.note())));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

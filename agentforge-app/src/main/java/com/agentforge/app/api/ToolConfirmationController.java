package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import jakarta.validation.Valid;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/tool-confirmations")
public class ToolConfirmationController {

    private final ToolConfirmationService confirmationService;

    public ToolConfirmationController(ToolConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @GetMapping("/{confirmationId}")
    public Mono<ToolConfirmationView> get(
            @PathVariable("confirmationId") String confirmationId,
            @RequestParam("userId") String userId) {
        return blocking(
                () ->
                        ToolConfirmationView.from(
                                confirmationService.getForUser(confirmationId, userId)));
    }

    @PostMapping("/{confirmationId}/approve")
    public Mono<ToolConfirmationView> approve(
            @PathVariable("confirmationId") String confirmationId,
            @Valid @RequestBody ToolConfirmationActionRequest request) {
        return blocking(
                () ->
                        ToolConfirmationView.from(
                                confirmationService.approve(
                                        confirmationId, request.userId())));
    }

    @PostMapping("/{confirmationId}/reject")
    public Mono<ToolConfirmationView> reject(
            @PathVariable("confirmationId") String confirmationId,
            @Valid @RequestBody ToolConfirmationActionRequest request) {
        return blocking(
                () ->
                        ToolConfirmationView.from(
                                confirmationService.reject(
                                        confirmationId, request.userId())));
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}

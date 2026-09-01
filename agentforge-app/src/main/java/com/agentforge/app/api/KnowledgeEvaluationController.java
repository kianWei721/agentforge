package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.rag.KnowledgeEvaluationReport;
import com.agentforge.rag.KnowledgeEvaluator;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/knowledge/evaluation")
public class KnowledgeEvaluationController {

    private final Optional<KnowledgeEvaluator> evaluator;

    public KnowledgeEvaluationController(Optional<KnowledgeEvaluator> evaluator) {
        this.evaluator = evaluator;
    }

    @PostMapping("/run")
    public Mono<KnowledgeEvaluationReport> run() {
        KnowledgeEvaluator service =
                evaluator.orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Knowledge evaluation is disabled."));
        return Mono.fromCallable(service::run)
                .subscribeOn(Schedulers.boundedElastic());
    }
}

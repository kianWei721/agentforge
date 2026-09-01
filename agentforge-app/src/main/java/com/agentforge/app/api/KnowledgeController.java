package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeIndexReport;
import com.agentforge.rag.KnowledgeIndexService;
import com.agentforge.rag.KnowledgeIngestionService;
import com.agentforge.rag.KnowledgeRetriever;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeRetriever retriever;
    private final Optional<KnowledgeIndexService> indexService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            KnowledgeRetriever retriever,
            Optional<KnowledgeIndexService> indexService) {
        this.ingestionService = ingestionService;
        this.retriever = retriever;
        this.indexService = indexService;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<KnowledgeDocumentDetailView> create(
            @Valid @RequestBody KnowledgeDocumentCreateRequest request) {
        return Mono.fromCallable(
                        () ->
                                KnowledgeDocumentDetailView.from(
                                        ingestionService.create(
                                                request.title(),
                                                request.sourceType(),
                                                request.sourceRef(),
                                                request.content())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/documents")
    public Mono<List<KnowledgeDocumentView>> list() {
        return Mono.fromCallable(
                        () ->
                                ingestionService.list().stream()
                                        .map(KnowledgeDocumentView::from)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/documents/{documentId}")
    public Mono<KnowledgeDocumentDetailView> get(@PathVariable String documentId) {
        return Mono.fromCallable(
                        () -> KnowledgeDocumentDetailView.from(ingestionService.get(documentId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String documentId) {
        return Mono.fromRunnable(() -> ingestionService.delete(documentId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @PostMapping("/index/rebuild")
    public Mono<KnowledgeIndexReport> rebuildIndex() {
        KnowledgeIndexService service =
                indexService.orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Hybrid knowledge retrieval is disabled."));
        return Mono.fromCallable(service::rebuild)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/search")
    public Mono<KnowledgeSearchResponse> search(
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return Mono.fromCallable(
                        () -> {
                            var hits = retriever.search(request.query(), request.resolvedTopK());
                            boolean noAnswer = hits.isEmpty();
                            return new KnowledgeSearchResponse(
                                    retriever.mode(),
                                    hits.size(),
                                    noAnswer,
                                    noAnswer ? "NO_RELEVANT_KNOWLEDGE_RETURNED" : null,
                                    hits);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LexicalKnowledgeRetrieverTest {

    private static final AccessPrincipal STAFF = AccessPrincipal.staff("staff-1");
    private static final AccessPrincipal CUSTOMER_A = AccessPrincipal.customer("user-a", "cust-a");

    @Test
    void shouldRankRelevantChineseTroubleshootingChunkFirst() {
        FakeRepository repository = new FakeRepository(true);
        repository.candidates.add(
                new KnowledgeCandidate(
                        "doc-1",
                        "chunk-1",
                        0,
                        "设备离线排查",
                        KnowledgeSourceType.TROUBLESHOOTING,
                        "test://offline",
                        "设备长时间离线时，先检查供电，再检查 SIM 卡和蜂窝网络连接。",
                        KnowledgeAccessScope.internal()));
        repository.candidates.add(
                new KnowledgeCandidate(
                        "doc-2",
                        "chunk-2",
                        0,
                        "拍照参数说明",
                        KnowledgeSourceType.MANUAL,
                        "test://photo",
                        "拍照参数用于设置拍照时长和照片数量。",
                        KnowledgeAccessScope.internal()));

        KnowledgeRetriever retriever = new LexicalKnowledgeRetriever(repository);
        List<KnowledgeSearchHit> hits = retriever.search("设备离线应该检查什么", 2, STAFF);

        assertFalse(hits.isEmpty());
        assertEquals("doc-1", hits.get(0).documentId());
        assertEquals("KB:doc-1#0", hits.get(0).citationId());
        assertEquals("LEXICAL_V1", retriever.mode());
    }

    @Test
    void customerShouldFindOwnAndPublicKnowledgeOnly() {
        FakeRepository repository = filingNotes(true);

        List<String> documents =
                new LexicalKnowledgeRetriever(repository)
                        .search("季度申报说明", 10, CUSTOMER_A).stream()
                        .map(KnowledgeSearchHit::documentId)
                        .sorted()
                        .toList();

        assertEquals(List.of("doc-a", "doc-public"), documents);
    }

    @Test
    void shouldDropRowsAWrongRepositoryPredicateLetsThrough() {
        // The fake ignores the principal, like a broken SQL WHERE clause would.
        FakeRepository repository = filingNotes(false);

        List<String> documents =
                new LexicalKnowledgeRetriever(repository)
                        .search("季度申报说明", 10, CUSTOMER_A).stream()
                        .map(KnowledgeSearchHit::documentId)
                        .sorted()
                        .toList();

        assertEquals(List.of("doc-a", "doc-public"), documents);
    }

    @Test
    void chunkerShouldProduceOverlappingBoundedChunks() {
        KnowledgeChunker chunker = new KnowledgeChunker(200, 40);
        String content = "第一段。".repeat(80);
        List<KnowledgeChunk> chunks = chunker.chunk("doc", content);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.charCount() <= 200));
        assertEquals(0, chunks.get(0).chunkIndex());
    }

    private static FakeRepository filingNotes(boolean honorsPrincipal) {
        FakeRepository repository = new FakeRepository(honorsPrincipal);
        repository.candidates.add(note("doc-a", "客户 A 的季度申报说明", KnowledgeAccessScope.customer("cust-a")));
        repository.candidates.add(note("doc-b", "客户 B 的季度申报说明", KnowledgeAccessScope.customer("cust-b")));
        repository.candidates.add(note("doc-internal", "内部季度申报复核要点", KnowledgeAccessScope.internal()));
        repository.candidates.add(note("doc-public", "季度申报说明（公开版）", KnowledgeAccessScope.publicAudience()));
        return repository;
    }

    private static KnowledgeCandidate note(
            String documentId, String title, KnowledgeAccessScope access) {
        return new KnowledgeCandidate(
                documentId,
                documentId + "#0",
                0,
                title,
                KnowledgeSourceType.SOP,
                "test://" + documentId,
                title + "：季度申报前核对收入、进项和申报说明。",
                access);
    }

    private static final class FakeRepository implements KnowledgeRepository {
        private final List<KnowledgeCandidate> candidates = new ArrayList<>();
        private final boolean honorsPrincipal;

        private FakeRepository(boolean honorsPrincipal) {
            this.honorsPrincipal = honorsPrincipal;
        }

        @Override
        public KnowledgeDocument save(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
            return document;
        }

        @Override
        public Optional<KnowledgeDocument> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<KnowledgeDocument> findAll() {
            return List.of();
        }

        @Override
        public List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates) {
            return candidates.stream().limit(maxCandidates).toList();
        }

        @Override
        public List<KnowledgeCandidate> findAccessibleCandidates(
                int maxCandidates, AccessPrincipal principal) {
            return candidates.stream()
                    .filter(
                            candidate ->
                                    !honorsPrincipal
                                            || KnowledgeAccessPolicy.permits(
                                                    principal, candidate.access()))
                    .limit(maxCandidates)
                    .toList();
        }

        @Override
        public Map<String, KnowledgeAccessScope> findReadableScopes(
                Collection<String> documentIds) {
            return Map.of();
        }

        @Override
        public void deleteById(String id) {}
    }
}

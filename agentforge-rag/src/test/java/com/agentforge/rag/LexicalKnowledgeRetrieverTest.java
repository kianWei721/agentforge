package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LexicalKnowledgeRetrieverTest {

    @Test
    void shouldRankRelevantChineseTroubleshootingChunkFirst() {
        FakeRepository repository = new FakeRepository();
        repository.candidates.add(
                new KnowledgeCandidate(
                        "doc-1",
                        "chunk-1",
                        0,
                        "设备离线排查",
                        KnowledgeSourceType.TROUBLESHOOTING,
                        "test://offline",
                        "设备长时间离线时，先检查供电，再检查 SIM 卡和蜂窝网络连接。"));
        repository.candidates.add(
                new KnowledgeCandidate(
                        "doc-2",
                        "chunk-2",
                        0,
                        "拍照参数说明",
                        KnowledgeSourceType.MANUAL,
                        "test://photo",
                        "拍照参数用于设置拍照时长和照片数量。"));

        KnowledgeRetriever retriever = new LexicalKnowledgeRetriever(repository);
        List<KnowledgeSearchHit> hits = retriever.search("设备离线应该检查什么", 2);

        assertFalse(hits.isEmpty());
        assertEquals("doc-1", hits.get(0).documentId());
        assertEquals("KB:doc-1#0", hits.get(0).citationId());
        assertEquals("LEXICAL_V1", retriever.mode());
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

    private static final class FakeRepository implements KnowledgeRepository {
        private final List<KnowledgeCandidate> candidates = new ArrayList<>();

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
        public void deleteById(String id) {}
    }
}

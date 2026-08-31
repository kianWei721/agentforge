package com.agentforge.infrastructure.rag;

import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeChunk;
import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeRepository;
import com.agentforge.rag.KnowledgeSourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private static final RowMapper<KnowledgeDocument> DOCUMENT_MAPPER =
            (rs, rowNum) -> mapDocument(rs);

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public KnowledgeDocument save(
            KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        jdbcTemplate.update(
                """
                INSERT INTO af_knowledge_document (
                    id, title, source_type, source_ref, content,
                    enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                document.id(),
                document.title(),
                document.sourceType().name(),
                document.sourceRef(),
                document.content(),
                document.enabled(),
                Timestamp.from(document.createdAt()),
                Timestamp.from(document.updatedAt()));

        for (KnowledgeChunk chunk : chunks) {
            jdbcTemplate.update(
                    """
                    INSERT INTO af_knowledge_chunk (
                        id, document_id, chunk_index, content, char_count, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    chunk.id(),
                    chunk.documentId(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    chunk.charCount(),
                    Timestamp.from(document.createdAt()));
        }
        return document;
    }

    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        List<KnowledgeDocument> rows =
                jdbcTemplate.query(
                        """
                        SELECT id, title, source_type, source_ref, content,
                               enabled, created_at, updated_at
                        FROM af_knowledge_document
                        WHERE id = ?
                        """,
                        DOCUMENT_MAPPER,
                        id);
        return rows.stream().findFirst();
    }

    @Override
    public List<KnowledgeDocument> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, title, source_type, source_ref, content,
                       enabled, created_at, updated_at
                FROM af_knowledge_document
                ORDER BY updated_at DESC, id
                """,
                DOCUMENT_MAPPER);
    }

    @Override
    public List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates) {
        return jdbcTemplate.query(
                """
                SELECT d.id AS document_id,
                       d.title,
                       d.source_type,
                       d.source_ref,
                       c.id AS chunk_id,
                       c.chunk_index,
                       c.content
                FROM af_knowledge_document d
                JOIN af_knowledge_chunk c ON c.document_id = d.id
                WHERE d.enabled = 1
                ORDER BY d.updated_at DESC, d.id, c.chunk_index
                LIMIT ?
                """,
                (rs, rowNum) ->
                        new KnowledgeCandidate(
                                rs.getString("document_id"),
                                rs.getString("chunk_id"),
                                rs.getInt("chunk_index"),
                                rs.getString("title"),
                                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                                rs.getString("source_ref"),
                                rs.getString("content")),
                maxCandidates);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM af_knowledge_document WHERE id = ?", id);
    }

    private static KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("title"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_ref"),
                rs.getString("content"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

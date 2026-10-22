package com.agentforge.infrastructure.rag;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.rag.KnowledgeAccessPolicy;
import com.agentforge.rag.KnowledgeAccessScope;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeChunk;
import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeRepository;
import com.agentforge.rag.KnowledgeSourceType;
import com.agentforge.rag.KnowledgeVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private static final RowMapper<KnowledgeDocument> DOCUMENT_MAPPER =
            (rs, rowNum) -> mapDocument(rs);

    private static final RowMapper<KnowledgeCandidate> CANDIDATE_MAPPER =
            (rs, rowNum) ->
                    new KnowledgeCandidate(
                            rs.getString("document_id"),
                            rs.getString("chunk_id"),
                            rs.getInt("chunk_index"),
                            rs.getString("title"),
                            KnowledgeSourceType.valueOf(rs.getString("source_type")),
                            rs.getString("source_ref"),
                            rs.getString("content"),
                            mapAccess(rs));

    private static final String CANDIDATE_SELECT =
            """
            SELECT d.id AS document_id,
                   d.title,
                   d.source_type,
                   d.source_ref,
                   d.visibility,
                   d.customer_id,
                   c.id AS chunk_id,
                   c.chunk_index,
                   c.content
            FROM af_knowledge_document d
            JOIN af_knowledge_chunk c ON c.document_id = d.id
            """;

    private static final String CANDIDATE_ORDER =
            """
            ORDER BY d.updated_at DESC, d.id, c.chunk_index
            LIMIT ?
            """;

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
                    id, title, source_type, source_ref, visibility, customer_id,
                    content, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                document.id(),
                document.title(),
                document.sourceType().name(),
                document.sourceRef(),
                document.access().visibility().name(),
                document.access().customerId(),
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
                        SELECT id, title, source_type, source_ref, visibility, customer_id,
                               content, enabled, created_at, updated_at
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
                SELECT id, title, source_type, source_ref, visibility, customer_id,
                       content, enabled, created_at, updated_at
                FROM af_knowledge_document
                ORDER BY updated_at DESC, id
                """,
                DOCUMENT_MAPPER);
    }

    @Override
    public List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates) {
        return jdbcTemplate.query(
                CANDIDATE_SELECT + "WHERE d.enabled = 1\n" + CANDIDATE_ORDER,
                CANDIDATE_MAPPER,
                maxCandidates);
    }

    @Override
    public List<KnowledgeCandidate> findAccessibleCandidates(
            int maxCandidates, AccessPrincipal principal) {
        List<Object> args = new ArrayList<>();
        String accessPredicate = accessPredicate(principal, args);
        args.add(maxCandidates);
        return jdbcTemplate.query(
                CANDIDATE_SELECT
                        + "WHERE d.enabled = 1 AND "
                        + accessPredicate
                        + "\n"
                        + CANDIDATE_ORDER,
                CANDIDATE_MAPPER,
                args.toArray());
    }

    @Override
    public Map<String, KnowledgeAccessScope> findReadableScopes(Collection<String> documentIds) {
        Set<String> ids = new LinkedHashSet<>(documentIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, KnowledgeAccessScope> scopes = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, visibility, customer_id FROM af_knowledge_document"
                        + " WHERE enabled = 1 AND id IN ("
                        + placeholders(ids.size())
                        + ")",
                rs -> {
                    scopes.put(rs.getString("id"), mapAccess(rs));
                },
                ids.toArray());
        return Map.copyOf(scopes);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM af_knowledge_document WHERE id = ?", id);
    }

    /** SQL translation of {@link KnowledgeAccessPolicy#permits}; values are always bound. */
    private static String accessPredicate(AccessPrincipal principal, List<Object> args) {
        Set<KnowledgeVisibility> unrestricted =
                KnowledgeAccessPolicy.unrestrictedVisibilities(principal);
        unrestricted.forEach(visibility -> args.add(visibility.name()));
        StringBuilder predicate =
                new StringBuilder("(d.visibility IN (")
                        .append(placeholders(unrestricted.size()))
                        .append(")");
        KnowledgeAccessPolicy.customerScope(principal)
                .ifPresent(
                        customerId -> {
                            predicate.append(
                                    " OR (d.visibility = ? AND d.customer_id = ?)");
                            args.add(KnowledgeVisibility.CUSTOMER.name());
                            args.add(customerId);
                        });
        return predicate.append(")").toString();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("title"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_ref"),
                mapAccess(rs),
                rs.getString("content"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static KnowledgeAccessScope mapAccess(ResultSet rs) throws SQLException {
        return new KnowledgeAccessScope(
                KnowledgeVisibility.valueOf(rs.getString("visibility")),
                rs.getString("customer_id"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

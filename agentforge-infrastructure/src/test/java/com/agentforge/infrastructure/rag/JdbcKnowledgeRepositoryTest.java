package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.rag.KnowledgeAccessScope;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeChunk;
import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcKnowledgeRepositoryTest {

    private static final AccessPrincipal CUSTOMER_A = AccessPrincipal.customer("user-a", "cust-a");
    private static final AccessPrincipal CUSTOMER_B = AccessPrincipal.customer("user-b", "cust-b");

    private JdbcTemplate jdbcTemplate;
    private JdbcKnowledgeRepository repository;
    private Instant clock;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:knowledge;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_knowledge_chunk");
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_knowledge_document");
        jdbcTemplate.execute(
                """
                CREATE TABLE af_knowledge_document (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    title VARCHAR(256) NOT NULL,
                    source_type VARCHAR(32) NOT NULL,
                    source_ref VARCHAR(512) NOT NULL DEFAULT '',
                    visibility VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
                    customer_id VARCHAR(64) NULL,
                    content CLOB NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE af_knowledge_chunk (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    document_id VARCHAR(64) NOT NULL,
                    chunk_index INT NOT NULL,
                    content CLOB NOT NULL,
                    char_count INT NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL
                )
                """);
        repository = new JdbcKnowledgeRepository(jdbcTemplate);
        clock = Instant.parse("2026-10-01T00:00:00Z");
    }

    @Test
    void accessFilterShouldApplyBeforeLimit() {
        // The customer's own and public notes are the oldest rows. With filter-after-limit the
        // ten newer internal rows would fill the window and the customer would get nothing.
        save("doc-public", KnowledgeAccessScope.publicAudience());
        save("doc-a", KnowledgeAccessScope.customer("cust-a"));
        for (int i = 0; i < 10; i++) {
            save("doc-internal-" + i, KnowledgeAccessScope.internal());
        }

        List<String> documents =
                repository.findAccessibleCandidates(2, CUSTOMER_A).stream()
                        .map(KnowledgeCandidate::documentId)
                        .sorted()
                        .toList();

        assertEquals(List.of("doc-a", "doc-public"), documents);
    }

    @Test
    void customerShouldNeverReceiveAnotherCustomersOrInternalRows() {
        save("doc-a", KnowledgeAccessScope.customer("cust-a"));
        save("doc-b", KnowledgeAccessScope.customer("cust-b"));
        save("doc-internal", KnowledgeAccessScope.internal());
        save("doc-public", KnowledgeAccessScope.publicAudience());

        assertEquals(
                List.of("doc-b", "doc-public"),
                repository.findAccessibleCandidates(100, CUSTOMER_B).stream()
                        .map(KnowledgeCandidate::documentId)
                        .sorted()
                        .toList());
        assertEquals(
                4,
                repository.findAccessibleCandidates(100, AccessPrincipal.staff("staff-1")).size());
    }

    @Test
    void readableScopesShouldOmitDisabledAndUnknownDocuments() {
        save("doc-a", KnowledgeAccessScope.customer("cust-a"));
        save("doc-disabled", KnowledgeAccessScope.publicAudience());
        jdbcTemplate.update("UPDATE af_knowledge_document SET enabled = FALSE WHERE id = 'doc-disabled'");

        Map<String, KnowledgeAccessScope> scopes =
                repository.findReadableScopes(List.of("doc-a", "doc-disabled", "doc-deleted"));

        assertEquals(Map.of("doc-a", KnowledgeAccessScope.customer("cust-a")), scopes);
        assertEquals(Map.of(), repository.findReadableScopes(List.of()));
    }

    @Test
    void shouldRoundTripDocumentAccess() {
        save("doc-a", KnowledgeAccessScope.customer("cust-a"));

        assertEquals(
                KnowledgeAccessScope.customer("cust-a"),
                repository.findById("doc-a").orElseThrow().access());
    }

    private void save(String id, KnowledgeAccessScope access) {
        clock = clock.plusSeconds(60);
        repository.save(
                new KnowledgeDocument(
                        id,
                        "季度申报说明 " + id,
                        KnowledgeSourceType.SOP,
                        "",
                        access,
                        "季度申报前核对收入与进项。",
                        true,
                        clock,
                        clock),
                List.of(new KnowledgeChunk(id + "#0", id, 0, "季度申报前核对收入与进项。", 12)));
    }
}

package com.agentforge.infrastructure.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import java.time.Instant;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcAgentDefinitionRepositoryTest {

    private JdbcAgentDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agentforge;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_agent_tool");
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_agent");
        jdbcTemplate.execute(
                """
                CREATE TABLE af_agent (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    description VARCHAR(512) NOT NULL,
                    system_prompt CLOB NOT NULL,
                    model_provider VARCHAR(64) NOT NULL,
                    model_name VARCHAR(128) NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    version BIGINT NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE af_agent_tool (
                    agent_id VARCHAR(64) NOT NULL,
                    tool_name VARCHAR(128) NOT NULL,
                    sort_order INT NOT NULL,
                    PRIMARY KEY (agent_id, tool_name),
                    CONSTRAINT fk_test_agent_tool
                        FOREIGN KEY (agent_id) REFERENCES af_agent(id)
                        ON DELETE CASCADE
                )
                """);

        repository = new JdbcAgentDefinitionRepository(jdbcTemplate);
    }

    @Test
    void shouldPersistReloadUpdateAndDeleteAgent() {
        Instant createdAt = Instant.parse("2026-08-28T12:00:00Z");
        AgentDefinition created =
                new AgentDefinition(
                        "agent-1",
                        "persistent-agent",
                        "test",
                        "system prompt",
                        "dashscope",
                        "qwen3.8-flash",
                        List.of("query_device_status"),
                        true,
                        1L,
                        createdAt,
                        createdAt);

        repository.save(created);

        AgentDefinition reloaded = repository.findById("agent-1").orElseThrow();
        assertEquals(created.name(), reloaded.name());
        assertEquals(List.of("query_device_status"), reloaded.toolNames());
        assertEquals(1L, reloaded.version());

        AgentDefinition updated =
                new AgentDefinition(
                        created.id(),
                        "persistent-agent-v2",
                        created.description(),
                        created.systemPrompt(),
                        created.modelProvider(),
                        created.modelName(),
                        List.of(),
                        false,
                        2L,
                        created.createdAt(),
                        Instant.parse("2026-08-28T12:01:00Z"));

        repository.save(updated);

        AgentDefinition updatedReloaded = repository.findById("agent-1").orElseThrow();
        assertEquals("persistent-agent-v2", updatedReloaded.name());
        assertFalse(updatedReloaded.enabled());
        assertTrue(updatedReloaded.toolNames().isEmpty());
        assertEquals(2L, updatedReloaded.version());
        assertEquals(1, repository.findAll().size());

        repository.deleteById("agent-1");
        assertFalse(repository.existsById("agent-1"));
    }

    @Test
    void shouldRejectStaleVersionUpdate() {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        AgentDefinition created =
                new AgentDefinition(
                        "agent-2",
                        "versioned",
                        "",
                        "prompt",
                        "dashscope",
                        "qwen3.8-flash",
                        List.of(),
                        true,
                        1L,
                        now,
                        now);
        repository.save(created);

        AgentDefinition stale =
                new AgentDefinition(
                        "agent-2",
                        "stale",
                        "",
                        "prompt",
                        "dashscope",
                        "qwen3.8-flash",
                        List.of(),
                        true,
                        1L,
                        now,
                        now);

        assertThrows(IllegalStateException.class, () -> repository.save(stale));
    }
}

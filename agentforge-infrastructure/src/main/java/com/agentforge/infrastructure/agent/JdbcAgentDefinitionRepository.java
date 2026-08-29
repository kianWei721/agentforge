package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private static final String SELECT_COLUMNS =
            "id, name, description, system_prompt, model_provider, model_name, "
                    + "enabled, version, created_at, updated_at";

    private static final RowMapper<AgentRow> AGENT_ROW_MAPPER =
            (rs, rowNum) -> mapAgentRow(rs);

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AgentDefinition> findById(String id) {
        List<AgentRow> rows =
                jdbcTemplate.query(
                        "SELECT " + SELECT_COLUMNS + " FROM af_agent WHERE id = ?",
                        AGENT_ROW_MAPPER,
                        id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDefinition(rows.get(0), findToolNames(id)));
    }

    @Override
    public List<AgentDefinition> findAll() {
        List<AgentRow> rows =
                jdbcTemplate.query(
                        "SELECT " + SELECT_COLUMNS + " FROM af_agent ORDER BY created_at, id",
                        AGENT_ROW_MAPPER);

        Map<String, List<String>> toolsByAgent = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT agent_id, tool_name FROM af_agent_tool ORDER BY agent_id, sort_order, tool_name",
                (RowCallbackHandler)
                        rs ->
                                toolsByAgent
                                        .computeIfAbsent(
                                                rs.getString("agent_id"),
                                                ignored -> new ArrayList<>())
                                        .add(rs.getString("tool_name")));

        return rows.stream()
                .map(row -> toDefinition(row, toolsByAgent.getOrDefault(row.id(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public AgentDefinition save(AgentDefinition definition) {
        if (existsById(definition.id())) {
            updateExisting(definition);
        } else {
            insertNew(definition);
        }
        replaceTools(definition);
        return definition;
    }

    @Override
    public boolean existsById(String id) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM af_agent WHERE id = ?",
                        Long.class,
                        id);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM af_agent WHERE id = ?", id);
    }

    private void insertNew(AgentDefinition definition) {
        jdbcTemplate.update(
                """
                INSERT INTO af_agent (
                    id, name, description, system_prompt, model_provider, model_name,
                    enabled, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                definition.id(),
                definition.name(),
                definition.description(),
                definition.systemPrompt(),
                definition.modelProvider(),
                definition.modelName(),
                definition.enabled(),
                definition.version(),
                Timestamp.from(definition.createdAt()),
                Timestamp.from(definition.updatedAt()));
    }

    private void updateExisting(AgentDefinition definition) {
        long expectedVersion = definition.version() - 1;
        if (expectedVersion < 1) {
            throw new IllegalStateException(
                    "Cannot update existing Agent with version " + definition.version());
        }

        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE af_agent
                        SET name = ?,
                            description = ?,
                            system_prompt = ?,
                            model_provider = ?,
                            model_name = ?,
                            enabled = ?,
                            version = ?,
                            updated_at = ?
                        WHERE id = ? AND version = ?
                        """,
                        definition.name(),
                        definition.description(),
                        definition.systemPrompt(),
                        definition.modelProvider(),
                        definition.modelName(),
                        definition.enabled(),
                        definition.version(),
                        Timestamp.from(definition.updatedAt()),
                        definition.id(),
                        expectedVersion);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Agent version conflict for id="
                            + definition.id()
                            + ", expectedVersion="
                            + expectedVersion);
        }
    }

    private void replaceTools(AgentDefinition definition) {
        jdbcTemplate.update("DELETE FROM af_agent_tool WHERE agent_id = ?", definition.id());

        int sortOrder = 0;
        for (String toolName : definition.toolNames()) {
            jdbcTemplate.update(
                    "INSERT INTO af_agent_tool (agent_id, tool_name, sort_order) VALUES (?, ?, ?)",
                    definition.id(),
                    toolName,
                    sortOrder++);
        }
    }

    private List<String> findToolNames(String agentId) {
        return jdbcTemplate.query(
                "SELECT tool_name FROM af_agent_tool WHERE agent_id = ? ORDER BY sort_order, tool_name",
                (rs, rowNum) -> rs.getString("tool_name"),
                agentId);
    }

    private static AgentDefinition toDefinition(AgentRow row, List<String> toolNames) {
        return new AgentDefinition(
                row.id(),
                row.name(),
                row.description(),
                row.systemPrompt(),
                row.modelProvider(),
                row.modelName(),
                toolNames,
                row.enabled(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private static AgentRow mapAgentRow(ResultSet rs) throws SQLException {
        return new AgentRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("system_prompt"),
                rs.getString("model_provider"),
                rs.getString("model_name"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record AgentRow(
            String id,
            String name,
            String description,
            String systemPrompt,
            String modelProvider,
            String modelName,
            boolean enabled,
            long version,
            Instant createdAt,
            Instant updatedAt) {}
}

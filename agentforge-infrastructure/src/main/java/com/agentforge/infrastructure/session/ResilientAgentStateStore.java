package com.agentforge.infrastructure.session;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResilientAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(ResilientAgentStateStore.class);

    private final AgentStateStore primary;
    private final InMemoryAgentStateStore fallback;

    public ResilientAgentStateStore(
            AgentStateStore primary, InMemoryAgentStateStore fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        try {
            primary.save(userId, sessionId, key, value);
            fallback.delete(userId, sessionId);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState save failed; using JVM fallback for session {}: {}",
                    sessionId,
                    e.getMessage());
            fallback.save(userId, sessionId, key, value);
        }
    }

    @Override
    public void save(
            String userId, String sessionId, String key, List<? extends State> values) {
        try {
            primary.save(userId, sessionId, key, values);
            fallback.delete(userId, sessionId);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState list save failed; using JVM fallback for session {}: {}",
                    sessionId,
                    e.getMessage());
            fallback.save(userId, sessionId, key, values);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        try {
            Optional<T> value = primary.get(userId, sessionId, key, type);
            if (value.isPresent()) {
                fallback.delete(userId, sessionId);
                return value;
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState read failed; checking JVM fallback for session {}: {}",
                    sessionId,
                    e.getMessage());
        }
        return fallback.get(userId, sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        try {
            List<T> value = primary.getList(userId, sessionId, key, itemType);
            if (!value.isEmpty()) {
                fallback.delete(userId, sessionId);
                return value;
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState list read failed; checking JVM fallback for session {}: {}",
                    sessionId,
                    e.getMessage());
        }
        return fallback.getList(userId, sessionId, key, itemType);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        try {
            if (primary.exists(userId, sessionId)) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState exists check failed for session {}: {}",
                    sessionId,
                    e.getMessage());
        }
        return fallback.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            primary.delete(userId, sessionId);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState delete failed for session {}: {}",
                    sessionId,
                    e.getMessage());
        }
        fallback.delete(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        try {
            primary.delete(userId, sessionId, key);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis AgentState key delete failed for session {}: {}",
                    sessionId,
                    e.getMessage());
        }
        fallback.delete(userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> result = new HashSet<>(fallback.listSessionIds(userId));
        try {
            result.addAll(primary.listSessionIds(userId));
        } catch (RuntimeException e) {
            log.warn("Redis AgentState session listing failed: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void close() {
        try {
            primary.close();
        } finally {
            fallback.close();
        }
    }
}

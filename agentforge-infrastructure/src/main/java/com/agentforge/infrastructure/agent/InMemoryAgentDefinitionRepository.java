package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryAgentDefinitionRepository implements AgentDefinitionRepository {

    private final ConcurrentMap<String, AgentDefinition> store = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentDefinition> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AgentDefinition> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(AgentDefinition::createdAt))
                .toList();
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        store.put(definition.id(), definition);
        return definition;
    }

    @Override
    public boolean existsById(String id) {
        return store.containsKey(id);
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}

package com.agentforge.agent;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRepository {

    Optional<AgentDefinition> findById(String id);

    List<AgentDefinition> findAll();

    AgentDefinition save(AgentDefinition definition);

    boolean existsById(String id);

    void deleteById(String id);
}

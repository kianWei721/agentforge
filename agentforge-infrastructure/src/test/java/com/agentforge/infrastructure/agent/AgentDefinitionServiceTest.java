package com.agentforge.infrastructure.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentCreateCommand;
import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.agent.AgentUpdateCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDefinitionServiceTest {

    @Test
    void shouldCreateAndVersionAgentOnUpdate() {
        InMemoryAgentDefinitionRepository repository = new InMemoryAgentDefinitionRepository();
        AgentDefinitionService service = new AgentDefinitionService(repository);

        AgentDefinition created =
                service.create(
                        new AgentCreateCommand(
                                "demo",
                                "demo agent",
                                "system prompt",
                                "dashscope",
                                "qwen-plus",
                                List.of("query_device_status"),
                                true));

        assertEquals(1L, created.version());
        assertEquals(List.of("query_device_status"), created.toolNames());

        AgentDefinition updated =
                service.update(
                        created.id(),
                        new AgentUpdateCommand(
                                "demo-v2",
                                "updated",
                                "new prompt",
                                "dashscope",
                                "qwen-plus",
                                List.of(),
                                true));

        assertEquals(2L, updated.version());
        assertEquals("demo-v2", updated.name());
        assertTrue(updated.toolNames().isEmpty());
    }
}

package com.agentforge.infrastructure.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.infrastructure.model.DashScopeModelFactory;
import com.agentforge.infrastructure.model.GatewayChatModel;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AgentModelSelectionTest {

    private final DashScopeModelFactory direct = new DashScopeModelFactory(new ModelProviderProperties());

    @Test
    void aGatewayAgentUsesTheRouteItNamesAsItsModel() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            ObjectProvider<ModelGatewayClient> provider =
                    new StaticListableBeanFactory(Map.of("gateway", gateway.client()))
                            .getBeanProvider(ModelGatewayClient.class);

            Model model =
                    AgentScopeRuntimeConfiguration.model(
                            agent("gateway", "agent"), direct, new ModelGatewayProperties(), provider);

            assertInstanceOf(GatewayChatModel.class, model);
            assertEquals("gateway:agent", model.getModelName());
        }
    }

    @Test
    void aGatewayAgentWithTheGatewayOffFailsInsteadOfCallingTheVendor() {
        ObjectProvider<ModelGatewayClient> none =
                new StaticListableBeanFactory().getBeanProvider(ModelGatewayClient.class);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> AgentScopeRuntimeConfiguration.model(
                                agent("gateway", "agent"), direct, new ModelGatewayProperties(), none));
        assertTrue(failure.getMessage().contains("agentforge.model-gateway.enabled=true"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentScopeRuntimeConfiguration.model(
                        agent("openai", "gpt"), direct, new ModelGatewayProperties(), none));
    }

    private static AgentDefinition agent(String provider, String model) {
        return new AgentDefinition("wecom-assistant", "WeCom", null, "prompt", provider, model, List.of(), true, 1, null, null);
    }
}

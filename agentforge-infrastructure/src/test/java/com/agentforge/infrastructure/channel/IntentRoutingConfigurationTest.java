package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentSource;
import com.agentforge.infrastructure.config.WeComChannelProperties;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class IntentRoutingConfigurationTest {

    @Test
    void routingIsOffUnlessEnabled() {
        assertSame(IntentRouter.DISABLED, WeComChannelConfiguration.intentRouter(intent(false, false), noGateway()));
    }

    @Test
    void phrasesAloneNeedNoGateway() {
        IntentRouter router = WeComChannelConfiguration.intentRouter(intent(true, false), noGateway());

        assertTrue(router.enabled());
        assertFalse(router.modelStage());
        assertEquals(IntentSource.RULE, router.onArrival("我要转人工").orElseThrow().source());
    }

    @Test
    void theModelStageWithoutTheGatewayFailsStartupInsteadOfCallingAVendor() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> WeComChannelConfiguration.intentRouter(intent(true, true), noGateway()));
        assertTrue(failure.getMessage().contains("agentforge.model-gateway.enabled=true"));
    }

    @Test
    void theModelStageUsesTheGateway() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            ObjectProvider<ModelGatewayClient> provider =
                    new StaticListableBeanFactory(Map.of("gateway", gateway.client()))
                            .getBeanProvider(ModelGatewayClient.class);

            assertTrue(WeComChannelConfiguration.intentRouter(intent(true, true), provider).modelStage());
        }
    }

    static WeComChannelProperties.Intent intent(boolean enabled, boolean modelStage) {
        WeComChannelProperties.Intent intent = new WeComChannelProperties.Intent();
        intent.setEnabled(enabled);
        intent.setModelStage(modelStage);
        intent.setIntents(
                List.of(
                        entry("answer", "业务咨询", "answer", null, List.of()),
                        entry("handoff", "需要人工", "handoff", "已登记，请直接联系群里的客服同事。", List.of("转人工"))));
        return intent;
    }

    static WeComChannelProperties.Entry entry(
            String name, String description, String target, String reply, List<String> keywords) {
        WeComChannelProperties.Entry entry = new WeComChannelProperties.Entry();
        entry.setName(name);
        entry.setDescription(description);
        entry.setTarget(target);
        entry.setReply(reply);
        entry.setKeywords(keywords);
        return entry;
    }

    private static ObjectProvider<ModelGatewayClient> noGateway() {
        return new StaticListableBeanFactory().getBeanProvider(ModelGatewayClient.class);
    }
}

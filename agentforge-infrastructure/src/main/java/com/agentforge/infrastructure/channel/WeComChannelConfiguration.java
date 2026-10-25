package com.agentforge.infrastructure.channel;

import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentRoutingSettings;
import com.agentforge.channel.wecom.SessionTurnGate;
import com.agentforge.channel.wecom.StreamBuffers;
import com.agentforge.channel.wecom.WeComChannelService;
import com.agentforge.channel.wecom.WeComCrypto;
import com.agentforge.core.security.PrincipalDirectory;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.WeComChannelProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.runtime.AgentConversationRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(
        prefix = "agentforge.channel.wecom",
        name = "enabled",
        havingValue = "true")
public class WeComChannelConfiguration {

    @Bean
    public ChannelInboundRepository channelInboundRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcChannelInboundRepository(jdbcTemplate);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService weComTurnExecutor(WeComChannelProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(
                properties.getMaxConcurrentTurns(),
                runnable -> {
                    Thread thread = new Thread(runnable, "wecom-turn-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean
    public WeComChannelService weComChannelService(
            WeComChannelProperties properties,
            AgentRuntimeProperties agentProperties,
            ChannelInboundRepository inboundRepository,
            PrincipalDirectory principals,
            AgentDefinitionRepository agents,
            AgentConversationRuntimeService conversationRuntime,
            ExecutorService weComTurnExecutor,
            ObjectProvider<ChannelMetrics> metricsProvider,
            ObjectProvider<ModelGatewayClient> gatewayProvider) {
        properties.validate();
        // The channel runs agents; it must not be a way around the agent runtime switch.
        if (!agentProperties.isEnabled()) {
            throw new IllegalStateException(
                    "agentforge.channel.wecom.enabled=true requires agentforge.agent.enabled=true.");
        }
        ChannelMetrics metrics = metricsProvider.getIfAvailable(() -> ChannelMetrics.NONE);
        return new WeComChannelService(
                properties.toSettings(),
                inboundRepository,
                principals,
                agents,
                conversationRuntime::stream,
                new SessionTurnGate(
                        weComTurnExecutor,
                        properties.getMaxConcurrentTurns(),
                        properties.getMaxPendingTurns(),
                        properties.getMaxQueuedPerSession(),
                        properties.getExpectedTurnDuration(),
                        metrics),
                new StreamBuffers(properties.getStreamTtl(), Clock.systemUTC()),
                Clock.systemUTC(),
                metrics,
                intentRouter(properties.getIntent(), gatewayProvider));
    }

    /**
     * The classifier only goes through the model gateway. With the model stage on and the gateway
     * off, startup fails rather than calling a vendor directly or silently skipping the stage.
     */
    static IntentRouter intentRouter(
            WeComChannelProperties.Intent intent, ObjectProvider<ModelGatewayClient> gatewayProvider) {
        if (!intent.isEnabled()) {
            return IntentRouter.DISABLED;
        }
        IntentRoutingSettings settings = intent.toSettings();
        if (!settings.modelStage()) {
            return new IntentRouter(settings, null);
        }
        ModelGatewayClient gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException(
                    "agentforge.channel.wecom.intent.model-stage=true requires"
                            + " agentforge.model-gateway.enabled=true.");
        }
        return new IntentRouter(
                settings,
                new GatewayIntentClassifier(gateway, intent.getModelRoute().trim(), new ObjectMapper()));
    }

    @Bean
    public WeComCallbackHandler weComCallbackHandler(
            WeComChannelProperties properties, WeComChannelService service) {
        return new WeComCallbackHandler(
                new WeComCrypto(
                        properties.getToken().trim(),
                        properties.getEncodingAesKey().trim(),
                        properties.getReceiveId()),
                new WeComMessageCodec(new ObjectMapper()),
                service,
                properties.getTimestampTolerance(),
                Clock.systemUTC());
    }
}

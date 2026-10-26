package com.agentforge.infrastructure.channel;

import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.handoff.ChannelHandoffRepository;
import com.agentforge.channel.handoff.ChannelHandoffService;
import com.agentforge.channel.handoff.HandoffNotificationDispatcher;
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
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(
        prefix = "agentforge.channel.wecom",
        name = "enabled",
        havingValue = "true")
public class WeComChannelConfiguration {

    @Bean
    public ChannelHandoffRepository channelHandoffRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcChannelHandoffRepository(jdbcTemplate);
    }

    /** The application's transaction manager: a HANDOFF row and its ticket commit together. */
    @Bean
    public ChannelInboundRepository channelInboundRepository(
            JdbcTemplate jdbcTemplate,
            ChannelHandoffRepository handoffRepository,
            PlatformTransactionManager transactionManager) {
        return new JdbcChannelInboundRepository(jdbcTemplate, handoffRepository, transactionManager);
    }

    @Bean
    public ChannelHandoffService channelHandoffService(
            ChannelHandoffRepository handoffRepository, ObjectProvider<ChannelMetrics> metricsProvider) {
        return new ChannelHandoffService(
                handoffRepository, Clock.systemUTC(), metricsProvider.getIfAvailable(() -> ChannelMetrics.NONE));
    }

    @Bean(destroyMethod = "close")
    public HandoffNotificationLoop handoffNotificationLoop(
            WeComChannelProperties properties,
            ChannelHandoffRepository handoffRepository,
            ObjectProvider<ChannelMetrics> metricsProvider) {
        WeComChannelProperties.Handoff handoff = properties.getHandoff();
        handoff.validate();
        if (!handoff.notifies()) {
            return HandoffNotificationLoop.off();
        }
        WeComWebhookSender sender =
                new WeComWebhookSender(
                        HttpClient.newBuilder().connectTimeout(handoff.getWebhookTimeout()).build(),
                        handoff.webhook(),
                        handoff.mentioned(),
                        handoff.getWebhookTimeout(),
                        new ObjectMapper());
        return HandoffNotificationLoop.start(
                new HandoffNotificationDispatcher(
                        handoffRepository,
                        sender,
                        Clock.systemUTC(),
                        metricsProvider.getIfAvailable(() -> ChannelMetrics.NONE),
                        handoff.getMaxItemsPerMessage(),
                        handoff.getMaxAttempts(),
                        ZoneId.of(handoff.getZone())),
                handoff.getNotifyInterval());
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
                intentRouter(properties.getIntent(), gatewayProvider),
                properties.getHandoff().toSettings());
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

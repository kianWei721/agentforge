package com.agentforge.infrastructure.channel;

import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.wecom.SessionTurnGate;
import com.agentforge.channel.wecom.StreamBuffers;
import com.agentforge.channel.wecom.WeComChannelService;
import com.agentforge.channel.wecom.WeComCrypto;
import com.agentforge.core.security.PrincipalDirectory;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.WeComChannelProperties;
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
            ObjectProvider<ChannelMetrics> metricsProvider) {
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
                metrics);
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

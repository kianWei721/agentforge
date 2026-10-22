package com.agentforge.channel.wecom;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.AgentTurnRunner;
import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelInboundStatus;
import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.core.security.PrincipalDirectory;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.ChannelSessions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Exceptions;

/**
 * Turns WeCom smart-bot callbacks into agent turns.
 *
 * <p>The callback thread only records the message, decides who it runs as, and queues the turn;
 * WeCom waits at most 5 seconds before redelivering. The agent runs on the turn gate, and WeCom
 * pulls the growing answer through stream refresh callbacks.
 */
public final class WeComChannelService {

    public static final String CHANNEL = ChannelSessions.WECOM_SOURCE;

    static final String REFUSE_USER = "你还没有开通这个助手，请联系管理员。";
    static final String REFUSE_GROUP = "这个群还没有开通助手，请联系管理员。";
    static final String REFUSE_AGENT = "助手暂时不可用，请稍后再试。";
    static final String REFUSE_MESSAGE = "目前只支持文字消息。";
    static final String REFUSE_CHAT = "目前只支持单聊和群聊。";
    static final String REFUSE_SESSION_BUSY = "前面的问题还在处理中，请稍后再问。";
    static final String REFUSE_OVERLOADED = "现在咨询的人比较多，请稍后再试。";
    static final String EXPIRED = "这条回复已过期，请重新提问。";
    static final String FAILED_TIMEOUT = "回答超时了，请稍后再试。";
    static final String FAILED_EXPIRED_IN_QUEUE = "排队太久，这个问题没来得及回答，请重新提问。";
    static final String FAILED_OTHER = "处理出错了，请稍后再试。";

    /**
     * WeCom stops pulling a stream reply 6 minutes after the user's message. Every turn must end
     * inside that, with some room left for WeCom's last refresh to see the end.
     */
    static final Duration REPLY_WINDOW = Duration.ofMinutes(6).minusSeconds(20);

    private static final System.Logger LOG = System.getLogger(WeComChannelService.class.getName());

    private final WeComChannelSettings settings;
    private final ChannelInboundRepository inbound;
    private final PrincipalDirectory principals;
    private final AgentDefinitionRepository agents;
    private final AgentTurnRunner runner;
    private final SessionTurnGate gate;
    private final StreamBuffers streams;
    private final Clock clock;

    public WeComChannelService(
            WeComChannelSettings settings,
            ChannelInboundRepository inbound,
            PrincipalDirectory principals,
            AgentDefinitionRepository agents,
            AgentTurnRunner runner,
            SessionTurnGate gate,
            StreamBuffers streams,
            Clock clock) {
        this.settings = settings;
        this.inbound = inbound;
        this.principals = principals;
        this.agents = agents;
        this.runner = runner;
        this.gate = gate;
        this.streams = streams;
        this.clock = clock;
    }

    public WeComStreamReply handle(WeComInbound message) {
        if (message.isStreamRefresh()) {
            return streams.snapshot(message.streamId())
                    .orElseGet(() -> new WeComStreamReply(message.streamId(), EXPIRED, true));
        }

        String streamId = WeComSessionKeys.streamId(settings.botId(), message.msgId());
        Route route = route(message);
        ChannelInbound record =
                new ChannelInbound(
                        UUID.randomUUID().toString(),
                        CHANNEL,
                        settings.botId(),
                        message.msgId(),
                        message.chatType().name(),
                        message.chatId(),
                        message.fromUserId(),
                        route.refused() ? null : settings.agentId(),
                        route.userId(),
                        route.sessionKey(),
                        null,
                        route.refused() ? ChannelInboundStatus.REJECTED : ChannelInboundStatus.RECEIVED,
                        route.refusalCode(),
                        clock.instant(),
                        clock.instant());
        if (!inbound.insertIfAbsent(record)) {
            // WeCom redelivered a message we already took: answer from the same stream. The
            // original callback may not have opened it yet (it lost the race to a slow insert), so
            // keep WeCom refreshing rather than ending the reply; an unknown stream ends as
            // expired on a later refresh.
            return streams.snapshot(streamId)
                    .orElseGet(() -> new WeComStreamReply(streamId, "", false));
        }
        if (route.refused()) {
            streams.openFinished(streamId, route.refusal());
            return snapshot(streamId);
        }

        streams.open(streamId);
        Instant acceptedAt = record.createdAt();
        SessionTurnGate.Admission admission =
                gate.submit(
                        route.sessionKey(), () -> runTurn(record.id(), route, streamId, acceptedAt));
        if (admission != SessionTurnGate.Admission.ACCEPTED) {
            streams.fail(
                    streamId,
                    admission == SessionTurnGate.Admission.SESSION_BUSY
                            ? REFUSE_SESSION_BUSY
                            : REFUSE_OVERLOADED);
            inbound.complete(
                    record.id(),
                    ChannelInboundStatus.REJECTED,
                    null,
                    admission.name(),
                    clock.instant());
        }
        return snapshot(streamId);
    }

    private Route route(WeComInbound message) {
        if (!settings.botId().equals(message.botId())) {
            return Route.refuse("BOT_MISMATCH", REFUSE_AGENT);
        }
        // Never guess: treating an unknown chat as a single chat would answer with a person's
        // own permissions in a place others may read.
        if (message.chatType() == WeComInbound.ChatType.UNKNOWN) {
            return Route.refuse("UNSUPPORTED_CHAT", REFUSE_CHAT);
        }
        if (!"text".equals(message.msgType())) {
            return Route.refuse("UNSUPPORTED_MESSAGE", REFUSE_MESSAGE);
        }
        String text = questionText(message);
        if (text.isEmpty()) {
            return Route.refuse("EMPTY_MESSAGE", REFUSE_MESSAGE);
        }

        Optional<String> agentRefusal = agentRefusal();
        if (agentRefusal.isPresent()) {
            return Route.refuse(agentRefusal.get(), REFUSE_AGENT);
        }

        if (message.chatType() == WeComInbound.ChatType.SINGLE) {
            String userId = settings.directUsers().get(message.fromUserId());
            if (userId == null || principals.resolve(userId).isEmpty()) {
                return Route.refuse("UNKNOWN_USER", REFUSE_USER);
            }
            return Route.accept(
                    userId, WeComSessionKeys.direct(settings.botId(), message.fromUserId()), text);
        }

        String groupUserId = settings.groupUsers().get(message.chatId());
        Optional<AccessPrincipal> principal =
                groupUserId == null ? Optional.empty() : principals.resolve(groupUserId);
        if (principal.isEmpty()) {
            return Route.refuse("GROUP_NOT_BOUND", REFUSE_GROUP);
        }
        if (principal.get().isStaff() && !settings.allowStaffGroups()) {
            return Route.refuse("STAFF_GROUP_DISABLED", REFUSE_GROUP);
        }
        return Route.accept(
                groupUserId, WeComSessionKeys.group(settings.botId(), message.chatId()), text);
    }

    /**
     * Checked when the message arrives and again right before the turn runs: the agent can be
     * edited while a message waits in the queue, and the runtime builds the turn from the agent's
     * current tools.
     */
    private Optional<String> agentRefusal() {
        Optional<AgentDefinition> agent = agents.findById(settings.agentId());
        if (agent.isEmpty() || !agent.get().enabled()) {
            return Optional.of("AGENT_UNAVAILABLE");
        }
        if (!settings.allowedTools().containsAll(agent.get().toolNames())) {
            return Optional.of("AGENT_TOOLS_NOT_ALLOWED");
        }
        return Optional.empty();
    }

    private void runTurn(String inboundId, Route route, String streamId, Instant acceptedAt) {
        // WeCom's clock started when the user sent the message, not when this turn got a worker.
        Duration remaining = Duration.between(clock.instant(), acceptedAt.plus(REPLY_WINDOW));
        if (remaining.isNegative() || remaining.isZero()) {
            endTurn(inboundId, streamId, null, "EXPIRED_IN_QUEUE", FAILED_EXPIRED_IN_QUEUE);
            return;
        }
        Optional<String> agentRefusal = agentRefusal();
        if (agentRefusal.isPresent()) {
            endTurn(inboundId, streamId, null, agentRefusal.get(), REFUSE_AGENT);
            return;
        }
        Duration limit =
                settings.turnTimeout().compareTo(remaining) < 0 ? settings.turnTimeout() : remaining;

        AtomicReference<String> executionId = new AtomicReference<>();
        try {
            runner.stream(
                            settings.agentId(),
                            new AgentRuntimeRequest(
                                    route.userId(), route.sessionKey(), route.text(), null, CHANNEL))
                    .doOnNext(
                            chunk -> {
                                if ("execution_start".equals(chunk.type())) {
                                    Object id = chunk.metadata().get("executionId");
                                    executionId.set(id == null ? null : id.toString());
                                } else if ("message".equals(chunk.type())) {
                                    streams.append(streamId, chunk.content());
                                }
                            })
                    // On the completion signal: Flux.timeout only bounds the gap between two
                    // chunks, and an agent that keeps talking would never time out.
                    .then()
                    .timeout(limit)
                    .block();
        } catch (RuntimeException exception) {
            Throwable cause = Exceptions.unwrap(exception);
            ModelCallFailedException modelFailure = ModelCallFailedException.find(cause);
            String code;
            String notice;
            if (modelFailure != null) {
                code = "MODEL_" + modelFailure.errorCode();
                notice = modelNotice(modelFailure);
            } else if (cause instanceof TimeoutException) {
                code = "TURN_TIMEOUT";
                notice = FAILED_TIMEOUT;
            } else {
                code = "TURN_FAILED";
                notice = FAILED_OTHER;
                LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " failed", cause);
            }
            endTurn(inboundId, streamId, executionId.get(), code, notice);
            return;
        }
        // Record first: once WeCom shows the answer as finished, the audit row says so too.
        try {
            inbound.complete(
                    inboundId, ChannelInboundStatus.DONE, executionId.get(), null, clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " not recorded", exception);
        } finally {
            streams.finish(streamId);
        }
    }

    /** Records the failure, then ends the stream with a notice, keeping what was already shown. */
    private void endTurn(
            String inboundId, String streamId, String executionId, String code, String notice) {
        try {
            inbound.complete(inboundId, ChannelInboundStatus.FAILED, executionId, code, clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " not recorded", exception);
        } finally {
            streams.fail(streamId, notice);
        }
    }

    private WeComStreamReply snapshot(String streamId) {
        return streams.snapshot(streamId)
                .orElseGet(() -> new WeComStreamReply(streamId, EXPIRED, true));
    }

    static String modelNotice(ModelCallFailedException failure) {
        String notice =
                failure.retryable()
                        ? "模型服务繁忙，请稍后再试。"
                        : "这次没能生成回答，请换个说法或稍后再试。";
        return failure.gatewayRequestId() == null
                ? notice
                : notice + "（请求号 " + failure.gatewayRequestId() + "）";
    }

    /** Group messages arrive as "@Bot question"; the mention is not part of the question. */
    static String questionText(WeComInbound message) {
        String text = message.text() == null ? "" : message.text().trim();
        if (message.chatType() == WeComInbound.ChatType.GROUP) {
            text = text.replaceFirst("^@\\S+\\s*", "").trim();
        }
        return text;
    }

    private record Route(
            String userId, String sessionKey, String text, String refusalCode, String refusal) {

        static Route accept(String userId, String sessionKey, String text) {
            return new Route(userId, sessionKey, text, null, null);
        }

        static Route refuse(String code, String refusal) {
            return new Route(null, null, null, code, refusal);
        }

        boolean refused() {
            return refusalCode != null;
        }
    }
}

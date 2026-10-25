package com.agentforge.channel.wecom;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.AgentTurnRunner;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelInboundStatus;
import com.agentforge.channel.ChannelIntent;
import com.agentforge.channel.intent.IntentDecision;
import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentTarget;
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

    static final Duration REPLY_WINDOW = WeComChannelSettings.DEFAULT_REPLY_WINDOW;

    private static final System.Logger LOG = System.getLogger(WeComChannelService.class.getName());

    private final WeComChannelSettings settings;
    private final ChannelInboundRepository inbound;
    private final PrincipalDirectory principals;
    private final AgentDefinitionRepository agents;
    private final AgentTurnRunner runner;
    private final SessionTurnGate gate;
    private final StreamBuffers streams;
    private final Clock clock;
    private final ChannelMetrics metrics;
    private final IntentRouter intents;

    public WeComChannelService(
            WeComChannelSettings settings,
            ChannelInboundRepository inbound,
            PrincipalDirectory principals,
            AgentDefinitionRepository agents,
            AgentTurnRunner runner,
            SessionTurnGate gate,
            StreamBuffers streams,
            Clock clock) {
        this(settings, inbound, principals, agents, runner, gate, streams, clock, ChannelMetrics.NONE);
    }

    public WeComChannelService(
            WeComChannelSettings settings,
            ChannelInboundRepository inbound,
            PrincipalDirectory principals,
            AgentDefinitionRepository agents,
            AgentTurnRunner runner,
            SessionTurnGate gate,
            StreamBuffers streams,
            Clock clock,
            ChannelMetrics metrics) {
        this(settings, inbound, principals, agents, runner, gate, streams, clock, metrics,
                IntentRouter.DISABLED);
    }

    public WeComChannelService(
            WeComChannelSettings settings,
            ChannelInboundRepository inbound,
            PrincipalDirectory principals,
            AgentDefinitionRepository agents,
            AgentTurnRunner runner,
            SessionTurnGate gate,
            StreamBuffers streams,
            Clock clock,
            ChannelMetrics metrics,
            IntentRouter intents) {
        this.metrics = metrics;
        this.intents = intents;
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
                        route.stopped() ? null : settings.agentId(),
                        route.userId(),
                        route.sessionKey(),
                        null,
                        route.stopped() ? route.status() : ChannelInboundStatus.RECEIVED,
                        route.refusalCode(),
                        clock.instant(),
                        clock.instant(),
                        route.intent() == null ? null : ChannelIntent.of(route.intent()));
        if (!inbound.insertIfAbsent(record)) {
            // WeCom redelivered a message we already took: answer from the same stream. The
            // original callback may not have opened it yet (it lost the race to a slow insert), so
            // keep WeCom refreshing rather than ending the reply; an unknown stream ends as
            // expired on a later refresh.
            return streams.snapshot(streamId)
                    .orElseGet(() -> new WeComStreamReply(streamId, "", false));
        }
        if (route.intent() != null) {
            metrics.intent(route.intent().intent(), route.intent().source().name());
        }
        if (route.stopped()) {
            metrics.admission(
                    route.refusalCode() == null ? route.status().name() : route.refusalCode());
            streams.openFinished(streamId, route.refusal());
            return snapshot(streamId);
        }

        streams.open(streamId);
        Instant acceptedAt = record.createdAt();
        // Admitted only if it can finish before WeCom stops pulling; otherwise refused right now.
        Duration timeLeft =
                Duration.between(clock.instant(), acceptedAt.plus(settings.replyWindow()));
        SessionTurnGate.Admission admission =
                gate.submit(
                        route.sessionKey(),
                        timeLeft,
                        () -> runTurn(record.id(), route, streamId, acceptedAt));
        metrics.admission(admission.name());
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
            return routed(
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
        return routed(
                groupUserId, WeComSessionKeys.group(settings.botId(), message.chatId()), text);
    }

    /**
     * The rule stage of intent routing, on the callback thread: a configured phrase that hands the
     * message to a person or refuses it decides here, without a worker and without a model.
     */
    private Route routed(String userId, String sessionKey, String text) {
        Optional<IntentDecision> decided = intents.onArrival(text);
        if (decided.isPresent() && decided.get().target() != IntentTarget.ANSWER) {
            return Route.stop(userId, sessionKey, text, decided.get(), reply(decided.get()));
        }
        return Route.accept(userId, sessionKey, text, decided.orElse(null));
    }

    private String reply(IntentDecision decision) {
        return intents.intent(decision.intent()).reply();
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

    /** @return whether the agent ran to an answer or to the time limit, see {@link SessionTurnGate.Turn} */
    private boolean runTurn(String inboundId, Route route, String streamId, Instant acceptedAt) {
        // WeCom's clock started when the user sent the message, not when this turn got a worker.
        Duration remaining =
                Duration.between(clock.instant(), acceptedAt.plus(settings.replyWindow()));
        if (remaining.isNegative() || remaining.isZero()) {
            endTurn(inboundId, streamId, null, "EXPIRED_IN_QUEUE", FAILED_EXPIRED_IN_QUEUE);
            return false;
        }
        if (route.intent() == null && intents.modelStage()) {
            IntentDecision decision = classify(inboundId, route, remaining);
            if (decision.target() != IntentTarget.ANSWER) {
                // A fixed reply took no agent time: not a measure of how long turns take.
                endByIntent(inboundId, streamId, decision);
                return false;
            }
            // The classifier used part of the window; the answer gets what is left.
            remaining = Duration.between(clock.instant(), acceptedAt.plus(settings.replyWindow()));
            if (remaining.isNegative() || remaining.isZero()) {
                endTurn(inboundId, streamId, null, "EXPIRED_IN_QUEUE", FAILED_EXPIRED_IN_QUEUE);
                return false;
            }
        }
        Optional<String> agentRefusal = agentRefusal();
        if (agentRefusal.isPresent()) {
            endTurn(inboundId, streamId, null, agentRefusal.get(), REFUSE_AGENT);
            return false;
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
            // A model failure may come back at once; only a turn that used its time is a measure.
            return "TURN_TIMEOUT".equals(code);
        }
        // Record first: once WeCom shows the answer as finished, the audit row says so too.
        metrics.turnOutcome(ChannelInboundStatus.DONE.name());
        try {
            inbound.complete(
                    inboundId, ChannelInboundStatus.DONE, executionId.get(), null, clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " not recorded", exception);
        } finally {
            streams.finish(streamId);
        }
        return true;
    }

    /**
     * The model stage of intent routing, on the worker. It may take at most half of what is left of
     * the reply window, so a slow classifier never leaves the answer without time. Never throws:
     * the router turns every failure into the default intent.
     */
    private IntentDecision classify(String inboundId, Route route, Duration remaining) {
        Duration half = remaining.dividedBy(2);
        Duration timeout = half.compareTo(intents.timeout()) < 0 ? half : intents.timeout();
        // The same id finds this call in the gateway's ledger.
        IntentDecision decision = intents.classify(route.text(), "wecom-" + inboundId, timeout);
        metrics.intent(decision.intent(), decision.source().name());
        try {
            inbound.recordIntent(inboundId, ChannelIntent.of(decision));
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " intent not recorded", exception);
        }
        return decision;
    }

    /** Hands the message to a person or refuses it, with the intent's reply. */
    private void endByIntent(String inboundId, String streamId, IntentDecision decision) {
        boolean handoff = decision.target() == IntentTarget.HANDOFF;
        String code = handoff ? null : "INTENT_REFUSED";
        metrics.turnOutcome(handoff ? ChannelInboundStatus.HANDOFF.name() : code);
        try {
            inbound.complete(
                    inboundId,
                    handoff ? ChannelInboundStatus.HANDOFF : ChannelInboundStatus.REJECTED,
                    null,
                    code,
                    clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "WeCom turn " + inboundId + " not recorded", exception);
        } finally {
            streams.fail(streamId, reply(decision));
        }
    }

    /** Records the failure, then ends the stream with a notice, keeping what was already shown. */
    private void endTurn(
            String inboundId, String streamId, String executionId, String code, String notice) {
        metrics.turnOutcome(code);
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

    /**
     * @param status set when the message ends here, without a turn: REJECTED or HANDOFF
     * @param refusal what the user sees when it ends here
     * @param intent decided on arrival; null when routing is off or the classifier is to decide
     */
    private record Route(
            String userId,
            String sessionKey,
            String text,
            ChannelInboundStatus status,
            String refusalCode,
            String refusal,
            IntentDecision intent) {

        static Route accept(String userId, String sessionKey, String text, IntentDecision intent) {
            return new Route(userId, sessionKey, text, null, null, null, intent);
        }

        static Route refuse(String code, String refusal) {
            return new Route(null, null, null, ChannelInboundStatus.REJECTED, code, refusal, null);
        }

        /** Handed to a person or refused by its intent; who asked is still recorded. */
        static Route stop(
                String userId, String sessionKey, String text, IntentDecision intent, String reply) {
            boolean handoff = intent.target() == IntentTarget.HANDOFF;
            return new Route(
                    userId,
                    sessionKey,
                    text,
                    handoff ? ChannelInboundStatus.HANDOFF : ChannelInboundStatus.REJECTED,
                    handoff ? null : "INTENT_REFUSED",
                    reply,
                    intent);
        }

        boolean stopped() {
            return status != null;
        }
    }
}

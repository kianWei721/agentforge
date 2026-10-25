package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelInboundStatus;
import com.agentforge.channel.ChannelIntent;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.intent.IntentClassifier;
import com.agentforge.channel.intent.IntentClassifier.Classification;
import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentRouterTest;
import com.agentforge.channel.intent.IntentRoutingSettings;
import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentStreamChunk;

import java.time.Duration;
import java.time.Instant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class WeComChannelServiceTest {

    private static final String BOT = "bot-1";
    private static final String AGENT = "wecom-assistant";
    private static final String GROUP_USER = "wecom-group:cust-a";
    private static final String STAFF_GROUP_USER = "wecom-group:ops";

    private final InMemoryInbound inbound = new InMemoryInbound();
    private final StreamBuffersTest.MutableClock clock =
            new StreamBuffersTest.MutableClock(Instant.parse("2026-10-23T00:00:00Z"));
    private final List<AgentRuntimeRequest> runs = new ArrayList<>();
    private final Map<String, AccessPrincipal> principals =
            Map.of(
                    "dev-staff", AccessPrincipal.staff("dev-staff"),
                    GROUP_USER, AccessPrincipal.customer(GROUP_USER, "cust-a"),
                    STAFF_GROUP_USER, AccessPrincipal.staff(STAFF_GROUP_USER));
    private List<String> agentTools = List.of("search_knowledge");
    private final List<String> intentsCounted = new ArrayList<>();
    private final ChannelMetrics metrics =
            new ChannelMetrics() {
                @Override
                public void intent(String intent, String source) {
                    intentsCounted.add(intent + "/" + source);
                }
            };
    private SessionTurnGate gate;
    private Function<AgentRuntimeRequest, Flux<AgentStreamChunk>> answer =
            request ->
                    Flux.just(
                            AgentStreamChunk.trace(
                                    "execution_start", "", "test", Map.of("executionId", "exec-1")),
                            AgentStreamChunk.message("季度申报", "test"),
                            AgentStreamChunk.message("需要准备报表。", "test"),
                            AgentStreamChunk.done("test"));

    @Test
    void directMessageRunsAsTheMappedUserInItsOwnSession() {
        WeComStreamReply reply = service(Runnable::run, false).handle(direct("m1", "zhangsan", "季度申报要准备什么"));

        assertEquals("季度申报需要准备报表。", reply.content());
        assertTrue(reply.finished());
        AgentRuntimeRequest run = runs.get(0);
        assertEquals("dev-staff", run.userId());
        assertEquals(WeComSessionKeys.direct(BOT, "zhangsan"), run.sessionId());
        assertEquals("wecom", run.source());

        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.DONE, row.status());
        assertEquals("exec-1", row.executionId());
        assertEquals(AGENT, row.agentId());
    }

    @Test
    void aRedeliveredMessageRunsOnceAndGetsTheSameStream() {
        WeComChannelService service = service(Runnable::run, false);

        WeComStreamReply first = service.handle(direct("m1", "zhangsan", "问题"));
        WeComStreamReply again = service.handle(direct("m1", "zhangsan", "问题"));

        assertEquals(1, runs.size());
        assertEquals(first, again);
    }

    @Test
    void unknownUsersAreRefusedWithoutRunningAnything() {
        WeComStreamReply reply = service(Runnable::run, false).handle(direct("m1", "stranger", "问题"));

        assertEquals(WeComChannelService.REFUSE_USER, reply.content());
        assertTrue(reply.finished());
        assertTrue(runs.isEmpty());
        assertRejected("m1", "UNKNOWN_USER");
    }

    @Test
    void aBoundGroupActsAsItsGroupUserNotAsTheAsker() {
        service(Runnable::run, false).handle(group("m1", "wrkCustA", "zhangsan", "@助手 发票怎么开"));

        AgentRuntimeRequest run = runs.get(0);
        // Everyone in the group reads the answer, so retrieval runs as the group.
        assertEquals(GROUP_USER, run.userId());
        assertEquals(WeComSessionKeys.group(BOT, "wrkCustA"), run.sessionId());
        assertEquals("发票怎么开", run.message());
        assertEquals("zhangsan", inbound.rows.get("m1").senderUserId());
    }

    @Test
    void unboundAndStaffGroupsAreRefused() {
        WeComChannelService service = service(Runnable::run, false);

        assertEquals(
                WeComChannelService.REFUSE_GROUP,
                service.handle(group("m1", "wrkUnknown", "zhangsan", "@助手 hi")).content());
        assertEquals(
                WeComChannelService.REFUSE_GROUP,
                service.handle(group("m2", "wrkOps", "zhangsan", "@助手 hi")).content());

        assertTrue(runs.isEmpty());
        assertRejected("m1", "GROUP_NOT_BOUND");
        assertRejected("m2", "STAFF_GROUP_DISABLED");
    }

    @Test
    void staffGroupsRunWhenExplicitlyAllowed() {
        service(Runnable::run, true).handle(group("m1", "wrkOps", "zhangsan", "@助手 hi"));

        assertEquals(STAFF_GROUP_USER, runs.get(0).userId());
    }

    @Test
    void anAgentWithAToolOutsideTheAllowListIsRefused() {
        agentTools = List.of("search_knowledge", "query_device_status");

        WeComStreamReply reply = service(Runnable::run, false).handle(direct("m1", "zhangsan", "问题"));

        assertEquals(WeComChannelService.REFUSE_AGENT, reply.content());
        assertTrue(runs.isEmpty());
        assertRejected("m1", "AGENT_TOOLS_NOT_ALLOWED");
    }

    @Test
    void nonTextMessagesAreRefused() {
        WeComInbound image =
                new WeComInbound("m1", BOT, WeComInbound.ChatType.SINGLE, null, "zhangsan", "image", null, null);

        assertEquals(WeComChannelService.REFUSE_MESSAGE, service(Runnable::run, false).handle(image).content());
        assertRejected("m1", "UNSUPPORTED_MESSAGE");
    }

    @Test
    void modelFailureKeepsThePartialAnswerAndNamesTheGatewayRequest() {
        answer =
                request ->
                        Flux.concat(
                                Flux.just(AgentStreamChunk.message("部分", "test")),
                                Flux.error(
                                        new ModelCallFailedException(
                                                "BUDGET_EXCEEDED", false, "gw-9", "budget")));

        WeComStreamReply reply = service(Runnable::run, false).handle(direct("m1", "zhangsan", "问题"));

        assertTrue(reply.finished());
        assertTrue(reply.content().startsWith("部分\n\n"));
        assertTrue(reply.content().contains("gw-9"));
        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.FAILED, row.status());
        assertEquals("MODEL_BUDGET_EXCEEDED", row.errorCode());
    }

    @Test
    void aTurnThatOutlivesItsTimeoutEnds() {
        answer = request -> Flux.never();

        WeComStreamReply reply = service(Runnable::run, false, Duration.ofMillis(50))
                .handle(direct("m1", "zhangsan", "问题"));

        assertEquals(WeComChannelService.FAILED_TIMEOUT, reply.content());
        assertEquals("TURN_TIMEOUT", inbound.rows.get("m1").errorCode());
    }

    @Test
    void anAgentThatKeepsTalkingStillHitsTheTotalTimeout() {
        // A chunk every 20ms: a per-chunk timeout would never fire.
        answer =
                request ->
                        Flux.interval(Duration.ofMillis(20))
                                .map(tick -> AgentStreamChunk.message("字", "test"));

        WeComStreamReply reply =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () ->
                                service(Runnable::run, false, Duration.ofMillis(300))
                                        .handle(direct("m1", "zhangsan", "问题")));

        assertTrue(reply.finished());
        assertTrue(reply.content().endsWith(WeComChannelService.FAILED_TIMEOUT));
        assertEquals("TURN_TIMEOUT", inbound.rows.get("m1").errorCode());
    }

    @Test
    void aTurnThatWaitedPastWeComsWindowNeverRuns() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        WeComChannelService service = service(scheduled::add, false);

        WeComStreamReply first = service.handle(direct("m1", "zhangsan", "问题"));
        clock.advance(Duration.ofMinutes(6));
        scheduled.poll().run();

        assertTrue(runs.isEmpty());
        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.FAILED, row.status());
        assertEquals("EXPIRED_IN_QUEUE", row.errorCode());
        assertEquals(
                WeComChannelService.FAILED_EXPIRED_IN_QUEUE,
                service.handle(refresh(first.streamId())).content());
    }

    @Test
    void queueTimeCountsAgainstTheTurnsTimeout() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        answer = request -> Flux.never();
        WeComChannelService service = service(scheduled::add, false, Duration.ofSeconds(30));

        service.handle(direct("m1", "zhangsan", "问题"));
        // 200ms of WeCom's window left: the turn gets that, not its own 30 seconds.
        clock.advance(WeComChannelService.REPLY_WINDOW.minusMillis(200));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> scheduled.poll().run());

        assertEquals("TURN_TIMEOUT", inbound.rows.get("m1").errorCode());
    }

    @Test
    void toolsAddedWhileAMessageWaitsAreCaughtBeforeItRuns() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        WeComChannelService service = service(scheduled::add, false);

        service.handle(direct("m1", "zhangsan", "问题"));
        agentTools = List.of("search_knowledge", "query_device_status");
        scheduled.poll().run();

        assertTrue(runs.isEmpty());
        assertEquals("AGENT_TOOLS_NOT_ALLOWED", inbound.rows.get("m1").errorCode());
    }

    @Test
    void aRedeliveryThatOvertakesTheOriginalKeepsWeComRefreshing() {
        WeComChannelService service = service(Runnable::run, false);
        // The original callback has inserted its row but not yet opened the stream.
        inbound.rows.put(
                "m1",
                new ChannelInbound(
                        "id-1", "wecom", BOT, "m1", "SINGLE", null, "zhangsan", AGENT, "dev-staff",
                        "wecom:x", null, ChannelInboundStatus.RECEIVED, null, Instant.EPOCH, Instant.EPOCH));

        WeComStreamReply reply = service.handle(direct("m1", "zhangsan", "问题"));

        assertEquals("", reply.content());
        assertFalse(reply.finished());
        assertTrue(runs.isEmpty());
    }

    @Test
    void unknownChatTypesAreRefusedRatherThanTreatedAsSingleChats() {
        WeComInbound unknown =
                new WeComInbound("m1", BOT, WeComInbound.ChatType.UNKNOWN, null, "zhangsan", "text", "问题", null);

        assertEquals(WeComChannelService.REFUSE_CHAT, service(Runnable::run, false).handle(unknown).content());
        assertTrue(runs.isEmpty());
        assertRejected("m1", "UNSUPPORTED_CHAT");
    }

    @Test
    void refreshReturnsTheCurrentAnswerAndUnknownStreamsExpire() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        WeComChannelService service = service(scheduled::add, false);

        WeComStreamReply first = service.handle(direct("m1", "zhangsan", "问题"));
        assertEquals("", first.content());
        assertFalse(first.finished());

        scheduled.poll().run();
        WeComStreamReply refreshed = service.handle(refresh(first.streamId()));
        assertEquals("季度申报需要准备报表。", refreshed.content());
        assertTrue(refreshed.finished());

        assertEquals(WeComChannelService.EXPIRED, service.handle(refresh("unknown")).content());
    }

    @Test
    void aSessionAlreadyWaitingOnTurnsIsToldToWait() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        WeComChannelService service = service(scheduled::add, false);

        service.handle(direct("m1", "zhangsan", "一"));
        service.handle(direct("m2", "zhangsan", "二"));
        WeComStreamReply third = service.handle(direct("m3", "zhangsan", "三"));

        assertEquals(WeComChannelService.REFUSE_SESSION_BUSY, third.content());
        assertTrue(third.finished());
        assertRejected("m3", "SESSION_BUSY");
    }

    @Test
    void aHandoffPhraseIsAnsweredOnArrivalWithoutAWorkerOrAnAgent() {
        WeComChannelService service = service(runnable -> fail("no turn for a handoff"), router(false, null));

        WeComStreamReply reply = service.handle(group("m1", "wrkCustA", "zhangsan", "@助手 我要转人工"));

        assertEquals("已登记", reply.content());
        assertTrue(reply.finished());
        assertTrue(runs.isEmpty());
        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.HANDOFF, row.status());
        assertNull(row.agentId());
        assertEquals(GROUP_USER, row.userId(), "the handoff list shows which group asked");
        assertEquals(new ChannelIntent("handoff", "RULE", null, null), row.intent());
    }

    @Test
    void anOutOfScopePhraseIsRefusedOnArrival() {
        WeComStreamReply reply =
                service(runnable -> fail("no turn"), router(false, null))
                        .handle(direct("m1", "zhangsan", "please write code"));

        assertEquals("不在范围内", reply.content());
        assertTrue(runs.isEmpty());
        assertRejected("m1", "INTENT_REFUSED");
        assertEquals("out_of_scope", inbound.rows.get("m1").intent().intent());
    }

    @Test
    void withoutTheModelStageEverythingElseIsAnswered() {
        service(Runnable::run, router(false, null)).handle(direct("m1", "zhangsan", "发票怎么开"));

        assertEquals(1, runs.size());
        assertEquals(ChannelInboundStatus.DONE, inbound.rows.get("m1").status());
        assertEquals(new ChannelIntent("answer", "DEFAULT", null, null), inbound.rows.get("m1").intent());
    }

    @Test
    void theClassifierCanHandAMessageToAPersonWithoutRunningTheAgent() {
        List<String> correlationIds = new ArrayList<>();
        WeComChannelService service =
                service(
                        Runnable::run,
                        router(
                                true,
                                (text, intents, correlationId, timeout) -> {
                                    correlationIds.add(correlationId);
                                    return new Classification("handoff", 0.95, "gw-9");
                                }));

        WeComStreamReply reply = service.handle(group("m1", "wrkCustA", "zhangsan", "@助手 什么破东西我要退款"));

        assertEquals("已登记", reply.content());
        assertTrue(reply.finished());
        assertTrue(runs.isEmpty());
        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.HANDOFF, row.status());
        assertEquals(new ChannelIntent("handoff", "MODEL", 0.95, "gw-9"), row.intent());
        assertEquals(List.of("wecom-" + row.id()), correlationIds, "the gateway ledger finds the message");
    }

    @Test
    void aFailingClassifierStillAnswers() {
        service(
                        Runnable::run,
                        router(
                                true,
                                (text, intents, correlationId, timeout) -> {
                                    throw new ModelCallFailedException("GATEWAY_UNREACHABLE", true, null, "down");
                                }))
                .handle(direct("m1", "zhangsan", "发票怎么开"));

        assertEquals(1, runs.size());
        ChannelInbound row = inbound.rows.get("m1");
        assertEquals(ChannelInboundStatus.DONE, row.status());
        assertEquals(new ChannelIntent("answer", "FALLBACK_MODEL_ERROR", null, null), row.intent());
    }

    @Test
    void theToolAllowListIsStillCheckedAfterClassification() {
        service(
                        Runnable::run,
                        router(
                                true,
                                (text, intents, correlationId, timeout) -> {
                                    // The agent is edited while the message is being classified.
                                    agentTools = List.of("search_knowledge", "query_device_status");
                                    return new Classification("answer", 0.9, "gw-1");
                                }))
                .handle(direct("m1", "zhangsan", "发票怎么开"));

        assertTrue(runs.isEmpty());
        assertEquals(ChannelInboundStatus.FAILED, inbound.rows.get("m1").status());
        assertEquals("AGENT_TOOLS_NOT_ALLOWED", inbound.rows.get("m1").errorCode());
    }

    @Test
    void classificationTakesAtMostHalfOfWhatIsLeftOfTheWindow() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        List<Duration> timeouts = new ArrayList<>();
        WeComChannelService service =
                service(
                        scheduled::add,
                        router(
                                true,
                                (text, intents, correlationId, timeout) -> {
                                    timeouts.add(timeout);
                                    return new Classification("answer", 0.9, "gw-1");
                                }));

        service.handle(direct("m1", "zhangsan", "一"));
        scheduled.poll().run();
        service.handle(direct("m2", "zhangsan", "二"));
        clock.advance(WeComChannelService.REPLY_WINDOW.minusSeconds(2));
        scheduled.poll().run();

        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(1)), timeouts);
    }

    @Test
    void aRedeliveredMessageIsRoutedAndCountedOnce() {
        List<String> classified = new ArrayList<>();
        WeComChannelService rules = service(Runnable::run, router(false, null));
        rules.handle(group("m1", "wrkCustA", "zhangsan", "@助手 转人工"));
        rules.handle(group("m1", "wrkCustA", "zhangsan", "@助手 转人工"));
        WeComChannelService model =
                service(
                        Runnable::run,
                        router(
                                true,
                                (text, intents, correlationId, timeout) -> {
                                    classified.add(correlationId);
                                    return new Classification("handoff", 0.9, "gw-1");
                                }));
        model.handle(direct("m2", "zhangsan", "什么破服务"));
        model.handle(direct("m2", "zhangsan", "什么破服务"));

        assertEquals(List.of("handoff/RULE", "handoff/MODEL"), intentsCounted);
        assertEquals(1, classified.size(), "a redelivery is not classified again");
        assertEquals(new ChannelIntent("handoff", "MODEL", 0.9, "gw-1"), inbound.rows.get("m2").intent());
    }

    @Test
    void aFixedReplyFromTheClassifierDoesNotTeachTheTurnEstimate() {
        List<String> next = new ArrayList<>(List.of("handoff", "answer"));
        WeComChannelService service =
                service(
                        Runnable::run,
                        router(
                                true,
                                (text, intents, correlationId, timeout) ->
                                        new Classification(next.remove(0), 0.9, "gw-1")));

        service.handle(direct("m1", "zhangsan", "什么破服务"));
        assertEquals(Duration.ZERO, gate.averageTurn(), "a hand-off took no agent time");

        service.handle(direct("m2", "zhangsan", "发票怎么开"));
        assertTrue(gate.averageTurn().compareTo(Duration.ZERO) > 0, "an answered turn does count");
    }

    private IntentRouter router(boolean modelStage, IntentClassifier classifier) {
        return new IntentRouter(
                new IntentRoutingSettings(
                        modelStage, "answer", Duration.ofSeconds(3), 0.7, 500, IntentRouterTest.INTENTS),
                classifier);
    }

    private void assertRejected(String msgId, String code) {
        ChannelInbound row = inbound.rows.get(msgId);
        assertEquals(ChannelInboundStatus.REJECTED, row.status());
        assertEquals(code, row.errorCode());
    }

    private WeComChannelService service(java.util.concurrent.Executor executor, boolean allowStaffGroups) {
        return service(executor, allowStaffGroups, Duration.ofSeconds(5));
    }

    private WeComChannelService service(java.util.concurrent.Executor executor, IntentRouter router) {
        return service(executor, false, Duration.ofSeconds(5), router);
    }

    private WeComChannelService service(
            java.util.concurrent.Executor executor, boolean allowStaffGroups, Duration turnTimeout) {
        return service(executor, allowStaffGroups, turnTimeout, IntentRouter.DISABLED);
    }

    private WeComChannelService service(
            java.util.concurrent.Executor executor,
            boolean allowStaffGroups,
            Duration turnTimeout,
            IntentRouter router) {
        WeComChannelSettings settings =
                new WeComChannelSettings(
                        BOT,
                        AGENT,
                        Set.of("search_knowledge"),
                        Map.of("zhangsan", "dev-staff"),
                        Map.of("wrkCustA", GROUP_USER, "wrkOps", STAFF_GROUP_USER),
                        allowStaffGroups,
                        turnTimeout);
        AgentTurnRunnerFake runner = new AgentTurnRunnerFake();
        return new WeComChannelService(
                settings,
                inbound,
                userId -> Optional.ofNullable(userId == null ? null : principals.get(userId)),
                new AgentsFake(),
                runner::stream,
                gate = new SessionTurnGate(executor, 64, 1),
                new StreamBuffers(Duration.ofMinutes(10), clock),
                clock,
                metrics,
                router);
    }

    private static WeComInbound direct(String msgId, String from, String text) {
        return new WeComInbound(msgId, BOT, WeComInbound.ChatType.SINGLE, null, from, "text", text, null);
    }

    private static WeComInbound group(String msgId, String chatId, String from, String text) {
        return new WeComInbound(msgId, BOT, WeComInbound.ChatType.GROUP, chatId, from, "text", text, null);
    }

    private static WeComInbound refresh(String streamId) {
        return new WeComInbound("r-" + streamId, BOT, WeComInbound.ChatType.SINGLE, null, "zhangsan", "stream", null, streamId);
    }

    private final class AgentTurnRunnerFake {
        Flux<AgentStreamChunk> stream(String agentId, AgentRuntimeRequest request) {
            assertEquals(AGENT, agentId);
            runs.add(request);
            return answer.apply(request);
        }
    }

    private final class AgentsFake implements AgentDefinitionRepository {
        @Override
        public Optional<AgentDefinition> findById(String id) {
            if (!AGENT.equals(id)) {
                return Optional.empty();
            }
            return Optional.of(
                    new AgentDefinition(
                            AGENT, "WeCom", null, "prompt", "mock", "mock", agentTools, true, 1, null, null));
        }

        @Override
        public List<AgentDefinition> findAll() {
            return List.of();
        }

        @Override
        public AgentDefinition save(AgentDefinition definition) {
            return definition;
        }

        @Override
        public boolean existsById(String id) {
            return AGENT.equals(id);
        }

        @Override
        public void deleteById(String id) {}
    }

    static final class InMemoryInbound implements ChannelInboundRepository {
        final Map<String, ChannelInbound> rows = new ConcurrentHashMap<>();

        @Override
        public boolean insertIfAbsent(ChannelInbound inbound) {
            return rows.putIfAbsent(inbound.msgId(), inbound) == null;
        }

        @Override
        public void complete(
                String id, ChannelInboundStatus status, String executionId, String errorCode, Instant updatedAt) {
            rows.replaceAll(
                    (msgId, row) ->
                            row.id().equals(id)
                                    ? new ChannelInbound(
                                            row.id(), row.channel(), row.botId(), row.msgId(), row.chatType(),
                                            row.chatId(), row.senderUserId(), row.agentId(), row.userId(),
                                            row.sessionKey(),
                                            executionId == null ? row.executionId() : executionId,
                                            status, errorCode, row.createdAt(), updatedAt, row.intent())
                                    : row);
        }

        @Override
        public void recordIntent(String id, ChannelIntent intent) {
            rows.replaceAll(
                    (msgId, row) ->
                            row.id().equals(id)
                                    ? new ChannelInbound(
                                            row.id(), row.channel(), row.botId(), row.msgId(), row.chatType(),
                                            row.chatId(), row.senderUserId(), row.agentId(), row.userId(),
                                            row.sessionKey(), row.executionId(), row.status(), row.errorCode(),
                                            row.createdAt(), row.updatedAt(), intent)
                                    : row);
        }

        @Override
        public Optional<ChannelInbound> find(String channel, String botId, String msgId) {
            return Optional.ofNullable(rows.get(msgId));
        }
    }
}

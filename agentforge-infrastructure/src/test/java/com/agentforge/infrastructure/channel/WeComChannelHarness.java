package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.fail;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.wecom.SessionTurnGate;
import com.agentforge.channel.wecom.StreamBuffers;
import com.agentforge.channel.wecom.WeComChannelService;
import com.agentforge.channel.wecom.WeComChannelSettings;
import com.agentforge.channel.wecom.WeComCrypto;
import com.agentforge.conversation.ConversationService;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.conversation.JdbcConversationRepository;
import com.agentforge.infrastructure.execution.JdbcExecutionRepository;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.runtime.AgentConversationRuntimeService;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.agentforge.runtime.RetrievalBudgetOptions;
import com.agentforge.runtime.SessionContextRestoreService;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.tool.DefaultToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

/**
 * The WeCom channel wired through the real chain: callback crypto, channel service, turn gate,
 * AgentConversationRuntimeService, one shared AgentScope HarnessAgent with its state store, and
 * the JDBC stores (H2). Only the model is fake: it answers with every group marker it finds in
 * the user messages it was handed, so an answer shows exactly which history the agent restored.
 */
final class WeComChannelHarness implements AutoCloseable {

    static final Pattern MARKER = Pattern.compile("G(\\d+)-T(\\d+)");
    static final String AGENT = "wecom-assistant";
    private static final String TOKEN = "scale-token";
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String BOT = "bot-scale";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How the harness is built; defaults admit everything and answer instantly. */
    static final class Options {
        int groups = 50;
        int workers = 16;
        int maxPending = Integer.MAX_VALUE;
        int maxQueuedPerSession = 3;
        long modelLatencyMillis;
        int modelOutputCapChars = 300;
        Duration replyWindow = WeComChannelSettings.DEFAULT_REPLY_WINDOW;
        Duration expectedTurn = Duration.ZERO;
        HarnessMemoryOptions memory = HarnessMemoryOptions.defaults();
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        int callbackThreads = 64;
        /** Turns per agent runtime generation; 0 keeps one runtime for the whole run. */
        long generationTurns;
    }

    final Options options;
    final JdbcTemplate jdbc;
    final SessionTurnGate gate;
    final EchoHistoryModel model;
    final AgentConversationRuntimeService conversationRuntime;
    final AtomicReference<AgentScopeHarnessRuntime> runtime = new AtomicReference<>();
    final AtomicInteger generations = new AtomicInteger();
    private final WeComCrypto wecom = new WeComCrypto(TOKEN, AES_KEY, BOT);
    private final ExecutorService callbacks;
    private final ExecutorService turns;
    private final WeComCallbackHandler handler;
    private final AtomicInteger messageIds = new AtomicInteger();

    WeComChannelHarness(Options options) {
        this.options = options;
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:wecom-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);

        AgentDefinitionRepository agents = new SingleAgent();
        ConversationService conversations = new ConversationService(new JdbcConversationRepository(jdbc));
        model = new EchoHistoryModel(options.modelLatencyMillis, options.modelOutputCapChars);
        AgentRuntimeManager runtimes =
                new AgentRuntimeManager(
                        agents,
                        new DefaultToolRegistry(),
                        (definition, tools) -> {
                            AgentScopeHarnessRuntime created =
                                    new AgentScopeHarnessRuntime(
                                            definition.id(),
                                            definition.name(),
                                            definition.systemPrompt(),
                                            model,
                                            tools,
                                            options.stateStore,
                                            ToolGovernanceOptions.defaults(),
                                            new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                                            null,
                                            RetrievalBudgetOptions.defaults(),
                                            options.memory);
                            runtime.set(created);
                            generations.incrementAndGet();
                            return created;
                        },
                        options.generationTurns);
        conversationRuntime =
                new AgentConversationRuntimeService(
                        agents,
                        conversations,
                        runtimes,
                        new ExecutionService(new JdbcExecutionRepository(jdbc)),
                        new SessionContextRestoreService(conversations, options.stateStore, 20));

        Map<String, String> groups = new HashMap<>();
        for (int i = 0; i < options.groups; i++) {
            groups.put("wrk-" + i, "wecom-group:c" + i);
        }
        callbacks = Executors.newFixedThreadPool(options.callbackThreads);
        turns = Executors.newFixedThreadPool(options.workers);
        gate =
                new SessionTurnGate(
                        turns,
                        options.workers,
                        options.maxPending,
                        options.maxQueuedPerSession,
                        options.expectedTurn,
                        ChannelMetrics.NONE);
        WeComChannelService service =
                new WeComChannelService(
                        new WeComChannelSettings(
                                BOT, AGENT, Set.of(), Map.of(), groups, false,
                                Duration.ofMinutes(5), options.replyWindow),
                        new JdbcChannelInboundRepository(jdbc),
                        userId ->
                                userId != null && userId.startsWith("wecom-group:c")
                                        ? Optional.of(AccessPrincipal.customer(userId, userId.substring(12)))
                                        : Optional.empty(),
                        agents,
                        conversationRuntime::stream,
                        gate,
                        new StreamBuffers(Duration.ofMinutes(30), Clock.systemUTC()),
                        Clock.systemUTC());
        handler =
                new WeComCallbackHandler(
                        new WeComCrypto(TOKEN, AES_KEY, BOT),
                        new WeComMessageCodec(JSON),
                        service,
                        Duration.ofMinutes(5),
                        Clock.systemUTC());
    }

    static String marker(int group, int turn) {
        return "G" + group + "-T" + turn;
    }

    static String expected(int group, int upToTurn) {
        List<String> markers = new ArrayList<>();
        for (int t = 1; t <= upToTurn; t++) {
            markers.add(marker(group, t));
        }
        return "SEEN " + String.join(",", markers);
    }

    /** One message per group, all groups concurrently; returns each group's first reply. */
    Map<Integer, JsonNode> sendAll(int turn, Report report) throws Exception {
        Map<Integer, Future<JsonNode>> futures = new HashMap<>();
        for (int i = 0; i < options.groups; i++) {
            int group = i;
            futures.put(group, callbacks.submit(() -> send(group, marker(group, turn), report)));
        }
        Map<Integer, JsonNode> replies = new HashMap<>();
        for (Map.Entry<Integer, Future<JsonNode>> entry : futures.entrySet()) {
            replies.put(entry.getKey(), entry.getValue().get(10, TimeUnit.MINUTES));
        }
        return replies;
    }

    /** Every group sends {@code count} messages back to back from one callback thread. */
    Map<Integer, List<JsonNode>> sendBursts(int afterTurn, int count, Report report) throws Exception {
        Map<Integer, Future<List<JsonNode>>> futures = new HashMap<>();
        for (int i = 0; i < options.groups; i++) {
            int group = i;
            futures.put(
                    group,
                    callbacks.submit(
                            () -> {
                                List<JsonNode> replies = new ArrayList<>();
                                for (int j = 1; j <= count; j++) {
                                    replies.add(send(group, marker(group, afterTurn + j), report));
                                }
                                return replies;
                            }));
        }
        Map<Integer, List<JsonNode>> replies = new HashMap<>();
        for (Map.Entry<Integer, Future<List<JsonNode>>> entry : futures.entrySet()) {
            replies.put(entry.getKey(), entry.getValue().get(10, TimeUnit.MINUTES));
        }
        return replies;
    }

    Future<JsonNode> sendAsync(int group, String marker, Report report) {
        return callbacks.submit(() -> send(group, marker, report));
    }

    /** The first reply WeCom gets for the message: {@code stream{id, finish, content}}. */
    JsonNode send(int group, String marker, Report report) throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("msgid", "m-" + messageIds.incrementAndGet());
        message.put("aibotid", BOT);
        message.put("chattype", "group");
        message.put("chatid", "wrk-" + group);
        message.putObject("from").put("userid", "u-" + group);
        message.put("msgtype", "text");
        message.putObject("text").put("content", "@bot 问题 " + marker);
        long started = System.nanoTime();
        JsonNode reply = call(JSON.writeValueAsString(message)).path("stream");
        if (report != null) {
            report.callbackLatencies.add(System.nanoTime() - started);
        }
        return reply;
    }

    JsonNode finalAnswer(JsonNode firstReply) throws Exception {
        ObjectNode refresh = JSON.createObjectNode();
        refresh.put("msgid", "r-" + messageIds.incrementAndGet());
        refresh.put("aibotid", BOT);
        refresh.put("chattype", "group");
        refresh.put("msgtype", "stream");
        refresh.putObject("stream").put("id", firstReply.path("id").asText());
        return call(JSON.writeValueAsString(refresh)).path("stream");
    }

    private JsonNode call(String plaintext) throws Exception {
        String encrypted = wecom.encrypt(plaintext);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String nonce = Integer.toString(messageIds.incrementAndGet());
        String envelope =
                handler.handle(
                        wecom.sign(timestamp, nonce, encrypted),
                        timestamp,
                        nonce,
                        "{\"encrypt\":\"" + encrypted + "\"}");
        return JSON.readTree(wecom.decrypt(JSON.readTree(envelope).path("encrypt").asText()));
    }

    void drain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        while (gate.activeSessions() > 0 || gate.pendingTurns() > 0) {
            if (System.nanoTime() > deadline) {
                fail("turns did not drain");
            }
            Thread.sleep(20);
        }
    }

    /** Stored messages whose markers belong to a different group than their session. */
    int storedMemoryViolations() {
        AtomicInteger violations = new AtomicInteger();
        jdbc.query(
                "SELECT s.user_id, m.content FROM af_message m JOIN af_session s ON s.id = m.session_id",
                rs -> {
                    String user = rs.getString(1);
                    if (!user.startsWith("wecom-group:c")) {
                        return;
                    }
                    String group = user.substring("wecom-group:c".length());
                    Matcher m = MARKER.matcher(rs.getString(2));
                    while (m.find()) {
                        if (!m.group(1).equals(group)) {
                            violations.incrementAndGet();
                        }
                    }
                });
        return violations.get();
    }

    Map<String, Integer> inboundOutcomes() {
        Map<String, Integer> outcomes = new HashMap<>();
        jdbc.query(
                "SELECT status, error_code, COUNT(*) FROM af_channel_inbound GROUP BY status, error_code",
                rs -> {
                    outcomes.put(
                            rs.getString(1) + (rs.getString(2) == null ? "" : "/" + rs.getString(2)),
                            rs.getInt(3));
                });
        return outcomes;
    }

    @Override
    public void close() {
        callbacks.shutdownNow();
        turns.shutdownNow();
    }

    /** Answers with the group markers in the user messages it was given, cut to a fixed length. */
    static final class EchoHistoryModel implements Model {
        private final long latencyMillis;
        private final int outputCapChars;
        final AtomicInteger calls = new AtomicInteger();
        volatile int maxPromptChars;
        volatile int lastMessageCount;

        EchoHistoryModel(long latencyMillis, int outputCapChars) {
            this.latencyMillis = latencyMillis;
            this.outputCapChars = outputCapChars;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            lastMessageCount = messages.size();
            int promptChars = 0;
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (Message message : Message.of(messages)) {
                promptChars += message.text().length();
                if (message.user()) {
                    Matcher m = MARKER.matcher(message.text());
                    while (m.find()) {
                        seen.add(m.group());
                    }
                }
            }
            maxPromptChars = Math.max(maxPromptChars, promptChars);
            String answer = "SEEN " + String.join(",", seen);
            if (answer.length() > outputCapChars) {
                answer = answer.substring(0, outputCapChars);
            }
            ChatResponse response =
                    ChatResponse.builder()
                            .id(UUID.randomUUID().toString())
                            .content(List.<ContentBlock>of(TextBlock.builder().text(answer).build()))
                            .usage(new ChatUsage(1, 1, 0))
                            .finishReason("stop")
                            .build();
            Flux<ChatResponse> reply = Flux.just(response);
            return latencyMillis > 0 ? reply.delaySubscription(Duration.ofMillis(latencyMillis)) : reply;
        }

        @Override
        public String getModelName() {
            return "echo-history";
        }

        private record Message(boolean user, String text) {
            static List<Message> of(List<Msg> messages) {
                List<Message> out = new ArrayList<>();
                for (Msg msg : messages) {
                    String text = msg.getTextContent();
                    out.add(new Message(msg.getRole() == MsgRole.USER, text == null ? "" : text));
                }
                return out;
            }
        }
    }

    /** Counts what the answers show, and prints it. */
    static final class Report {
        final String name;
        final List<String> phases = new ArrayList<>();
        final ConcurrentLinkedQueue<Long> callbackLatencies = new ConcurrentLinkedQueue<>();
        final AtomicInteger checked = new AtomicInteger();
        final AtomicInteger crossGroup = new AtomicInteger();
        final AtomicInteger wrongHistory = new AtomicInteger();
        final AtomicInteger unfinished = new AtomicInteger();
        final List<String> examples = new ArrayList<>();
        final Map<String, Object> extra = new java.util.LinkedHashMap<>();

        Report(String name) {
            this.name = name;
        }

        void phase(String label, int turns, long startedNanos) {
            double seconds = (System.nanoTime() - startedNanos) / 1e9;
            phases.add(String.format("%s: %d turns in %.1fs (%.0f turns/s)", label, turns, seconds, turns / seconds));
        }

        void check(int group, JsonNode reply, String expected) {
            checked.incrementAndGet();
            String content = reply.path("content").asText("");
            if (!reply.path("finish").asBoolean(false)) {
                unfinished.incrementAndGet();
                example("unfinished G" + group + ": " + content);
                return;
            }
            Matcher m = MARKER.matcher(content);
            while (m.find()) {
                if (!m.group(1).equals(Integer.toString(group))) {
                    crossGroup.incrementAndGet();
                    example("cross-group G" + group + ": " + content);
                    return;
                }
            }
            if (!content.equals(expected)) {
                wrongHistory.incrementAndGet();
                example("G" + group + " expected [" + expected + "] got [" + content + "]");
            }
        }

        private synchronized void example(String text) {
            if (examples.size() < 10) {
                examples.add(text);
            }
        }

        void print() {
            List<Long> latencies = new ArrayList<>(callbackLatencies);
            latencies.sort(Long::compare);
            StringBuilder out = new StringBuilder("\n===== WeCom channel: ").append(name).append(" =====\n");
            phases.forEach(p -> out.append(p).append('\n'));
            if (!latencies.isEmpty()) {
                out.append(String.format(
                        "callback (synchronous part) ms: p50=%.1f p99=%.1f max=%.1f over %d callbacks%n",
                        percentile(latencies, 0.50), percentile(latencies, 0.99),
                        latencies.get(latencies.size() - 1) / 1e6, latencies.size()));
            }
            out.append("answers checked: ").append(checked.get())
                    .append(", cross-group: ").append(crossGroup.get())
                    .append(", wrong/missing history: ").append(wrongHistory.get())
                    .append(", unfinished: ").append(unfinished.get()).append('\n');
            extra.forEach((k, v) -> out.append(k).append(": ").append(v).append('\n'));
            examples.forEach(e -> out.append("  e.g. ").append(e).append('\n'));
            System.out.println(out);
        }

        private static double percentile(List<Long> sorted, double q) {
            return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1)) / 1e6;
        }
    }

    private static final class SingleAgent implements AgentDefinitionRepository {
        private final AgentDefinition definition =
                new AgentDefinition(
                        AGENT, "WeCom assistant", null, "Answer from the conversation only.",
                        "fake", "echo-history", List.of(), true, 1, null, null);

        @Override
        public Optional<AgentDefinition> findById(String id) {
            return AGENT.equals(id) ? Optional.of(definition) : Optional.empty();
        }

        @Override
        public List<AgentDefinition> findAll() {
            return List.of(definition);
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

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE af_session (
                    id VARCHAR(64) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(128) NOT NULL, session_key VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL, next_sequence BIGINT NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
                    last_message_at TIMESTAMP(6), UNIQUE (agent_id, user_id, session_key))
                """);
        jdbc.execute(
                """
                CREATE TABLE af_message (
                    id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(64) NOT NULL,
                    sequence_no BIGINT NOT NULL, role VARCHAR(16) NOT NULL, content CLOB NOT NULL,
                    source VARCHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (session_id, sequence_no),
                    FOREIGN KEY (session_id) REFERENCES af_session(id) ON DELETE CASCADE)
                """);
        jdbc.execute(
                """
                CREATE TABLE af_execution (
                    id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(64) NOT NULL,
                    agent_id VARCHAR(64) NOT NULL, user_id VARCHAR(128) NOT NULL,
                    user_message_id VARCHAR(64) NOT NULL, assistant_message_id VARCHAR(64),
                    model_provider VARCHAR(64) NOT NULL, model_name VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL, next_trace_sequence BIGINT NOT NULL,
                    started_at TIMESTAMP(6) NOT NULL, completed_at TIMESTAMP(6), duration_ms BIGINT,
                    error_type VARCHAR(128), error_message CLOB)
                """);
        jdbc.execute(
                """
                CREATE TABLE af_trace_event (
                    id VARCHAR(64) PRIMARY KEY, execution_id VARCHAR(64) NOT NULL,
                    sequence_no BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL,
                    source VARCHAR(64) NOT NULL, content CLOB NOT NULL, tool_call_id VARCHAR(128),
                    tool_name VARCHAR(128), reply_id VARCHAR(128), state VARCHAR(32), usage_text CLOB,
                    input_tokens INT, output_tokens INT, cached_tokens INT, model_time_ms BIGINT,
                    tool_duration_ms BIGINT, failure_code VARCHAR(64), retryable BOOLEAN,
                    governance_decision VARCHAR(32), governance_reason VARCHAR(128),
                    argument_fingerprint VARCHAR(64), blocked_by_tool_call_id VARCHAR(128),
                    blocked_by_failure_code VARCHAR(64), outbound_protocol VARCHAR(32),
                    downstream_system VARCHAR(64), outbound_attempts INT, http_status INT,
                    business_code INT, downstream_duration_ms BIGINT, outbound_outcome VARCHAR(64),
                    tool_risk_level VARCHAR(16), tool_read_only BOOLEAN, policy_decision VARCHAR(32),
                    policy_reason VARCHAR(128), confirmation_id VARCHAR(64),
                    confirmation_status VARCHAR(16), retrieval_budget_decision VARCHAR(32),
                    retrieval_budget_reason VARCHAR(128), retrieval_budget_limit INT,
                    retrieval_budget_used INT, retrieval_budget_remaining INT,
                    occurred_at TIMESTAMP(6) NOT NULL, UNIQUE (execution_id, sequence_no))
                """);
        jdbc.execute(
                """
                CREATE TABLE af_channel_inbound (
                    id VARCHAR(64) PRIMARY KEY, channel VARCHAR(32) NOT NULL,
                    bot_id VARCHAR(128) NOT NULL, msg_id VARCHAR(128) NOT NULL,
                    chat_type VARCHAR(16) NOT NULL, chat_id VARCHAR(128),
                    sender_userid VARCHAR(128) NOT NULL, agent_id VARCHAR(64), user_id VARCHAR(128),
                    session_key VARCHAR(128), execution_id VARCHAR(64), status VARCHAR(16) NOT NULL,
                    error_code VARCHAR(64), created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL, UNIQUE (channel, bot_id, msg_id))
                """);
    }
}

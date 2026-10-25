package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.AgentStreamChunk;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.agentforge.runtime.RetrievalBudgetOptions;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real agent turns through the real model gateway, against the same turns calling DashScope
 * directly. In-memory agent state and two fake tools: no MySQL, Redis or Elasticsearch. Costs
 * money, so off in every build. Run with a running gateway and both keys in the environment:
 *
 * <pre>
 * AGENTFORGE_MODEL_GATEWAY_API_KEY=gk-... DASHSCOPE_API_KEY=sk-... mvn -pl agentforge-infrastructure test \
 *     -Dagentforge.agent.smoke=true -Dtest=GatewayAgentSmokeTest [-Dagentforge.agent.smoke.base-url=...]
 * </pre>
 *
 * The report goes to {@code target/agent-smoke/report.md}.
 */
@EnabledIfSystemProperty(named = "agentforge.agent.smoke", matches = "true")
class GatewayAgentSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT =
            "你是企业服务助手。查订单状态用 lookup_order，查发票进度用 invoice_status；"
                    + "没有订单号或发票号时先问用户要，不要猜。只根据工具结果回答，不确定就说明。";

    /** One user message; a case with several runs them in one session, in order. */
    record Turn(String message, List<String> expectedCalls, List<String> mustMention) {}

    record Case(String id, List<Turn> turns) {}

    static final List<Case> CASES =
            List.of(
                    new Case("order", List.of(new Turn("订单 A1 到哪了", List.of("lookup_order(A1)"), List.of("发货")))),
                    new Case("order-2", List.of(new Turn("帮我查下订单 B2 的状态", List.of("lookup_order(B2)"), List.of("付款")))),
                    new Case("invoice", List.of(new Turn("发票 F100 开好了吗", List.of("invoice_status(F100)"), List.of("开具")))),
                    new Case("invoice-2", List.of(new Turn("F200 这张发票什么进度", List.of("invoice_status(F200)"), List.of("审核")))),
                    new Case("greeting", List.of(new Turn("你好", List.of(), List.of()))),
                    new Case("no-tool", List.of(new Turn("你们周末上班吗", List.of(), List.of()))),
                    new Case("two-tools", List.of(new Turn(
                            "订单 C3 签收了没有？顺便看下发票 F100",
                            List.of("invoice_status(F100)", "lookup_order(C3)"),
                            List.of("签收", "开具")))),
                    new Case("not-found", List.of(new Turn("订单号 Z9 到哪了", List.of("lookup_order(Z9)"), List.of()))),
                    new Case("ask-first", List.of(new Turn("查一下我的订单", List.of(), List.of("订单号")))),
                    new Case("follow-up", List.of(
                            new Turn("订单 A1 到哪了", List.of("lookup_order(A1)"), List.of("发货")),
                            new Turn("那 B2 呢", List.of("lookup_order(B2)"), List.of("付款")))));

    record Outcome(String path, String caseId, int turn, List<String> calls, String answer, String error, long millis) {}

    @TempDir Path workspace;

    @Test
    void agentTurnsThroughTheGatewayAndDirect() throws Exception {
        String gatewayKey = System.getenv("AGENTFORGE_MODEL_GATEWAY_API_KEY");
        String dashscopeKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(gatewayKey, "AGENTFORGE_MODEL_GATEWAY_API_KEY must hold a client key allowed the agent route");

        ModelGatewayProperties gatewayProperties = new ModelGatewayProperties();
        gatewayProperties.setEnabled(true);
        gatewayProperties.setBaseUrl(System.getProperty("agentforge.agent.smoke.base-url", "http://localhost:8090/v1"));
        gatewayProperties.setApiKey(gatewayKey);
        Model viaGateway =
                new GatewayChatModel(
                        new ModelGatewayClient(HttpClient.newHttpClient(), JSON, gatewayProperties),
                        "agent",
                        gatewayProperties.getAgentMaxTokens(),
                        gatewayProperties.getAgentRequestTimeout(),
                        JSON);

        String stamp = Instant.now().toString().replace(":", "").substring(0, 15);
        List<Outcome> outcomes = new ArrayList<>(run("gateway", viaGateway, stamp));
        if (dashscopeKey != null && !dashscopeKey.isBlank()) {
            ModelProviderProperties direct = new ModelProviderProperties();
            direct.getDashscope().setApiKey(dashscopeKey);
            direct.getDashscope().setModel("qwen3.8-flash");
            outcomes.addAll(run("direct", new DashScopeModelFactory(direct).createStreamingModel(), stamp));
        }

        String report = report(stamp, outcomes);
        Path out = Path.of("target", "agent-smoke", "report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);
        assertFalse(outcomes.isEmpty());
    }

    private List<Outcome> run(String path, Model model, String stamp) {
        List<String> calls = new CopyOnWriteArrayList<>();
        AgentScopeHarnessRuntime runtime =
                new AgentScopeHarnessRuntime(
                        "smoke-" + path,
                        "企业服务助手",
                        PROMPT,
                        model,
                        List.of(orderTool(calls), invoiceTool(calls)),
                        new InMemoryAgentStateStore(),
                        ToolGovernanceOptions.defaults(),
                        new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                        null,
                        RetrievalBudgetOptions.defaults(),
                        new HarnessMemoryOptions(workspace.resolve(path), 50, 12000, 20, 800));
        List<Outcome> outcomes = new ArrayList<>();
        for (Case c : CASES) {
            for (int t = 0; t < c.turns().size(); t++) {
                calls.clear();
                Turn turn = c.turns().get(t);
                String executionId = "smoke-" + stamp + "-" + path + "-" + c.id() + "-" + t;
                long started = System.nanoTime();
                String error = null;
                StringBuilder answer = new StringBuilder();
                try {
                    List<AgentStreamChunk> chunks =
                            runtime.stream(new AgentRuntimeRequest("smoke-user", "s-" + c.id(), turn.message(), executionId, "api"))
                                    .collectList()
                                    .block(Duration.ofSeconds(120));
                    chunks.stream().filter(ch -> "message".equals(ch.type())).forEach(ch -> answer.append(ch.content()));
                } catch (RuntimeException failure) {
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
                outcomes.add(new Outcome(path, c.id(), t, sorted(calls), answer.toString(), error,
                        (System.nanoTime() - started) / 1_000_000));
            }
        }
        runtime.close();
        return outcomes;
    }

    private static String report(String stamp, List<Outcome> outcomes) {
        StringBuilder r = new StringBuilder("# Agent turns through the model gateway\n\n");
        r.append("- gateway route `agent`, direct DashScope `qwen3.8-flash`; correlation ids `ag-smoke-").append(stamp)
                .append("-<path>-<case>-<turn>`\n");
        r.append("- a turn passes when its tool calls are exactly the expected ones and the answer mentions every expected word\n\n");
        r.append("| path | turns | passed | right tool calls | errors | median ms | max ms |\n|---|---|---|---|---|---|---|\n");
        for (String path : List.of("gateway", "direct")) {
            List<Outcome> mine = outcomes.stream().filter(o -> o.path().equals(path)).toList();
            if (mine.isEmpty()) {
                continue;
            }
            long passed = mine.stream().filter(GatewayAgentSmokeTest::passed).count();
            long calls = mine.stream().filter(o -> o.calls().equals(sorted(turn(o).expectedCalls()))).count();
            long errors = mine.stream().filter(o -> o.error() != null).count();
            List<Long> ms = mine.stream().map(Outcome::millis).sorted().toList();
            r.append(String.format("| %s | %d | %d | %d | %d | %d | %d |%n", path, mine.size(), passed, calls, errors,
                    ms.get(ms.size() / 2), ms.get(ms.size() - 1)));
        }
        r.append("\n| path | case | turn | expected calls | calls | pass | answer |\n|---|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            String answer = o.error() != null ? "**" + o.error() + "**" : o.answer().replace("\n", " ").replace("|", "/");
            if (answer.length() > 80) {
                answer = answer.substring(0, 80) + "…";
            }
            r.append(String.format("| %s | %s | %d | %s | %s | %s | %s |%n", o.path(), o.caseId(), o.turn(),
                    turn(o).expectedCalls(), o.calls(), passed(o) ? "yes" : "**no**", answer));
        }
        return r.toString();
    }

    private static Turn turn(Outcome o) {
        return CASES.stream().filter(c -> c.id().equals(o.caseId())).findFirst().orElseThrow().turns().get(o.turn());
    }

    private static boolean passed(Outcome o) {
        Turn turn = turn(o);
        return o.error() == null
                && o.calls().equals(sorted(turn.expectedCalls()))
                && turn.mustMention().stream().allMatch(o.answer()::contains);
    }

    /** Sorted, duplicates kept: a tool called twice is not the expected single call. */
    private static List<String> sorted(List<String> calls) {
        List<String> sorted = new ArrayList<>(calls);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    private static AgentTool orderTool(List<String> calls) {
        Map<String, String> orders = Map.of("A1", "已发货，预计明天送达", "B2", "待付款", "C3", "已签收");
        return tool("lookup_order", "按订单号查订单状态和物流", "order", "订单号，如 A1", calls, orders, "未找到这个订单");
    }

    private static AgentTool invoiceTool(List<String> calls) {
        Map<String, String> invoices = Map.of("F100", "已开具，可在电子税务局下载", "F200", "审核中，预计两个工作日内开具");
        return tool("invoice_status", "按发票号查开票进度", "invoice_no", "发票号，如 F100", calls, invoices, "未找到这张发票");
    }

    private static AgentTool tool(
            String name, String description, String argument, String argumentDescription,
            List<String> calls, Map<String, String> answers, String notFound) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(argument, Map.of("type", "string", "description", argumentDescription)),
                        "required", List.of(argument));
            }

            @Override
            public ToolResult execute(ToolInvocationContext context, Map<String, Object> arguments) {
                String key = String.valueOf(arguments.get(argument)).trim().toUpperCase();
                calls.add(name + "(" + key + ")");
                return ToolResult.success(key + "：" + answers.getOrDefault(key, notFound), Map.of());
            }
        };
    }
}

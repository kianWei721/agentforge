package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.core.security.PrincipalDirectory;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.rag.KnowledgeAccessPolicy;
import com.agentforge.rag.KnowledgeAccessScope;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeChunk;
import com.agentforge.rag.KnowledgeChunker;
import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeRepository;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.rag.KnowledgeSourceType;
import com.agentforge.rag.KnowledgeVisibility;
import com.agentforge.rag.LexicalKnowledgeRetriever;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.AgentStreamChunk;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.agentforge.runtime.RetrievalBudgetOptions;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.tool.knowledge.KnowledgeSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * How well the WeCom customer agent answers from the knowledge base, through the real model
 * gateway. The agent, its prompt and its one tool are the production ones: {@code search_knowledge}
 * over the lexical retriever (what production uses with hybrid retrieval off) with the real access
 * policy, asking as a customer group. The knowledge base is in memory, so no MySQL, Redis or
 * Elasticsearch. Costs model calls, so off in every build. Run with a running gateway:
 *
 * <pre>
 * AGENTFORGE_MODEL_GATEWAY_API_KEY=gk-... mvn -pl agentforge-infrastructure test \
 *     -Dagentforge.agent.eval=true -Dtest=AgentAnswerEvaluationTest [-Dagentforge.agent.eval.runs=3]
 * </pre>
 *
 * <p>Corpus and cases ({@code src/test/resources/agent-eval/}) were written before any model ran
 * and are graded by rule, not by a model: facts that must appear (any of a group's spellings),
 * words that must not, whether the knowledge base was searched, and whether a question the base
 * cannot answer is said to be unanswered. The report goes to {@code target/agent-eval/report.md}.
 */
@EnabledIfSystemProperty(named = "agentforge.agent.eval", matches = "true")
class AgentAnswerEvaluationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The WeCom agent's prompt as configured for the channel verification of 2026-10-26. */
    static final String PROMPT =
            "你是企业微信群里的客服助手。回答业务问题前先用 search_knowledge 查知识库，只根据查到的内容回答，"
                    + "查不到就说明并建议联系客服同事。回答简洁。";

    /** The group asks: everyone in it reads the answer, so retrieval runs as the group. */
    static final String GROUP = "wecom-group:acme";
    static final String CUSTOMER = "C001";

    /** Saying the knowledge base has no answer, or sending the user to a person. */
    static final Pattern NOT_FOUND =
            Pattern.compile("查不到|没有查到|没查到|未查到|未找到|没有找到|没找到|找不到|没有相关|暂无|没有提到|未提及|"
                    + "没有说明|没有记载|无法确认|无法提供|不在.{0,8}范围|联系.{0,6}(客服|会计|同事)|咨询.{0,6}(客服|会计|同事)");

    record Turn(String message, Boolean search, List<List<String>> must, List<String> mustNot, boolean notFound) {}

    record Case(String id, String split, String category, List<Turn> turns) {}

    record Outcome(int run, String caseId, int turn, List<String> searches, List<String> retrieved,
            String answer, String error, long millis) {}

    @TempDir Path workspace;

    @Test
    void theWeComAgentAnswersFromTheKnowledgeBase() throws Exception {
        String gatewayKey = System.getenv("AGENTFORGE_MODEL_GATEWAY_API_KEY");
        assertNotNull(gatewayKey, "AGENTFORGE_MODEL_GATEWAY_API_KEY must hold a client key allowed the agent route");
        int runs = Integer.getInteger("agentforge.agent.eval.runs", 3);

        ModelGatewayProperties gateway = new ModelGatewayProperties();
        gateway.setEnabled(true);
        gateway.setBaseUrl(System.getProperty("agentforge.agent.eval.base-url", "http://localhost:8090/v1"));
        gateway.setApiKey(gatewayKey);
        Model model =
                new GatewayChatModel(
                        new ModelGatewayClient(HttpClient.newHttpClient(), JSON, gateway),
                        "agent",
                        gateway.getAgentMaxTokens(),
                        gateway.getAgentRequestTimeout(),
                        JSON);

        List<Case> cases = cases();
        InMemoryKnowledge knowledge = InMemoryKnowledge.load();
        PrincipalDirectory principals =
                userId -> GROUP.equals(userId) ? Optional.of(AccessPrincipal.customer(GROUP, CUSTOMER)) : Optional.empty();

        String stamp = Instant.now().toString().replace(":", "").substring(0, 15);
        List<Outcome> outcomes = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            outcomes.addAll(run(run, stamp, model, knowledge, principals, cases));
        }

        String report = report(stamp, runs, cases, outcomes);
        Path out = Path.of("target", "agent-eval", "report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);
        assertEquals(runs * cases.stream().mapToInt(c -> c.turns().size()).sum(), outcomes.size());
    }

    private List<Outcome> run(
            int run, String stamp, Model model, InMemoryKnowledge knowledge, PrincipalDirectory principals, List<Case> cases) {
        List<String> searches = new CopyOnWriteArrayList<>();
        List<String> retrieved = new CopyOnWriteArrayList<>();
        KnowledgeRetriever recording =
                new KnowledgeRetriever() {
                    private final KnowledgeRetriever lexical = new LexicalKnowledgeRetriever(knowledge);

                    @Override
                    public List<KnowledgeSearchHit> search(String query, int topK, AccessPrincipal principal) {
                        searches.add(query);
                        List<KnowledgeSearchHit> hits = lexical.search(query, topK, principal);
                        hits.forEach(hit -> retrieved.add(hit.documentId()));
                        return hits;
                    }

                    @Override
                    public String mode() {
                        return lexical.mode();
                    }
                };
        AgentScopeHarnessRuntime runtime =
                new AgentScopeHarnessRuntime(
                        "eval-" + run,
                        "企业微信客服助手",
                        PROMPT,
                        model,
                        List.of(new KnowledgeSearchTool(recording, principals)),
                        new InMemoryAgentStateStore(),
                        ToolGovernanceOptions.defaults(),
                        new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                        null,
                        RetrievalBudgetOptions.defaults(),
                        new HarnessMemoryOptions(workspace.resolve("run-" + run), 50, 12000, 20, 800));
        List<Outcome> outcomes = new ArrayList<>();
        for (Case c : cases) {
            for (int t = 0; t < c.turns().size(); t++) {
                searches.clear();
                retrieved.clear();
                String executionId = "eval-" + stamp + "-r" + run + "-" + c.id() + "-" + t;
                long started = System.nanoTime();
                String error = null;
                StringBuilder answer = new StringBuilder();
                try {
                    runtime.stream(new AgentRuntimeRequest(GROUP, "eval-r" + run + "-" + c.id(), c.turns().get(t).message(),
                                    executionId, "wecom"))
                            .collectList()
                            .block(Duration.ofSeconds(150))
                            .stream()
                            .filter(chunk -> "message".equals(chunk.type()))
                            .map(AgentStreamChunk::content)
                            .forEach(answer::append);
                } catch (RuntimeException failure) {
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
                outcomes.add(new Outcome(run, c.id(), t, List.copyOf(searches), List.copyOf(retrieved),
                        answer.toString(), error, (System.nanoTime() - started) / 1_000_000));
            }
        }
        runtime.close();
        return outcomes;
    }

    /** Why a turn failed; empty when it passed. */
    static List<String> failures(Turn turn, Outcome outcome) {
        List<String> why = new ArrayList<>();
        if (outcome.error() != null) {
            why.add("error");
            return why;
        }
        boolean searched = !outcome.searches().isEmpty();
        if (turn.search() != null && turn.search() != searched) {
            why.add(searched ? "searched" : "no search");
        }
        String answer = normalize(outcome.answer());
        for (List<String> group : turn.must()) {
            if (group.stream().map(AgentAnswerEvaluationTest::normalize).noneMatch(answer::contains)) {
                why.add("missing " + group.get(0));
            }
        }
        for (String word : turn.mustNot()) {
            if (answer.contains(normalize(word))) {
                why.add("forbidden " + word);
            }
        }
        if (turn.notFound() && !NOT_FOUND.matcher(answer).find()) {
            why.add("not said to be unanswered");
        }
        return why;
    }

    /** Markdown and spacing differ between runs and say nothing about the facts. */
    static String normalize(String text) {
        return text.replaceAll("[\\s*`]", "").replace('：', ':').replace('～', '~');
    }

    private static String report(String stamp, int runs, List<Case> cases, List<Outcome> outcomes) {
        Map<String, Case> byId = new LinkedHashMap<>();
        cases.forEach(c -> byId.put(c.id(), c));
        StringBuilder r = new StringBuilder("# WeCom agent answer quality through the model gateway\n\n");
        r.append("- route `agent`; ").append(runs).append(" runs of ").append(cases.size()).append(" cases (")
                .append(cases.stream().mapToInt(c -> c.turns().size()).sum()).append(" turns each); correlation ids `ag-eval-")
                .append(stamp).append("-r<run>-<case>-<turn>`\n");
        r.append("- asked as `").append(GROUP).append("` (customer ").append(CUSTOMER)
                .append("); a case passes when every turn passes; graded by rule\n\n");

        // A case-run passes when all its turns do.
        Map<String, Boolean> casePass = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            String key = o.run() + "/" + o.caseId();
            boolean ok = failures(byId.get(o.caseId()).turns().get(o.turn()), o).isEmpty();
            casePass.merge(key, ok, Boolean::logicalAnd);
        }
        long passed = casePass.values().stream().filter(b -> b).count();
        r.append("| | passed | total | rate | 95% CI |\n|---|---|---|---|---|\n");
        r.append(row("all case-runs", passed, casePass.size()));
        for (int run = 1; run <= runs; run++) {
            String prefix = run + "/";
            long p = casePass.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getValue()).count();
            long n = casePass.keySet().stream().filter(k -> k.startsWith(prefix)).count();
            r.append(row("run " + run, p, n));
        }
        for (String category : cases.stream().map(Case::category).distinct().toList()) {
            long p = casePass.entrySet().stream()
                    .filter(e -> byId.get(e.getKey().substring(e.getKey().indexOf('/') + 1)).category().equals(category))
                    .filter(Map.Entry::getValue).count();
            long n = casePass.keySet().stream()
                    .filter(k -> byId.get(k.substring(k.indexOf('/') + 1)).category().equals(category)).count();
            r.append(row(category, p, n));
        }

        long errors = outcomes.stream().filter(o -> o.error() != null).count();
        long leakedRetrievals = outcomes.stream()
                .flatMap(o -> o.retrieved().stream())
                .filter(id -> id.startsWith("i") || id.equals("c03"))
                .count();
        long forbiddenAnswers = outcomes.stream()
                .filter(o -> failures(byId.get(o.caseId()).turns().get(o.turn()), o).stream().anyMatch(f -> f.startsWith("forbidden")))
                .count();
        long unflaggedUnanswerable = outcomes.stream()
                .filter(o -> byId.get(o.caseId()).turns().get(o.turn()).notFound())
                .filter(o -> failures(byId.get(o.caseId()).turns().get(o.turn()), o).contains("not said to be unanswered"))
                .count();
        List<Long> ms = outcomes.stream().map(Outcome::millis).sorted().toList();
        r.append("\n| signal | value |\n|---|---|\n");
        r.append("| turns | ").append(outcomes.size()).append(" |\n");
        r.append("| turns failed with an error | ").append(errors).append(" |\n");
        r.append("| other customers' or internal chunks retrieved | ").append(leakedRetrievals).append(" |\n");
        r.append("| answers containing a forbidden word | ").append(forbiddenAnswers).append(" |\n");
        r.append("| unanswerable turns not said to be unanswered | ").append(unflaggedUnanswerable).append(" |\n");
        r.append("| turn latency p50 / p95 / max (ms) | ").append(ms.get(ms.size() / 2)).append(" / ")
                .append(ms.get((int) Math.ceil(ms.size() * 0.95) - 1)).append(" / ").append(ms.get(ms.size() - 1)).append(" |\n");

        r.append("\n## Every turn\n\n| run | case | turn | category | result | searches | retrieved | answer |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            Case c = byId.get(o.caseId());
            List<String> why = failures(c.turns().get(o.turn()), o);
            String answer = o.error() != null ? o.error() : o.answer();
            answer = answer.replace("\n", " ").replace("|", "/");
            if (answer.length() > 120) {
                answer = answer.substring(0, 120) + "…";
            }
            r.append(String.format("| %d | %s | %d | %s | %s | %s | %s | %s |%n", o.run(), o.caseId(), o.turn(), c.category(),
                    why.isEmpty() ? "pass" : "**" + String.join("; ", why) + "**",
                    String.join(" / ", o.searches()).replace("|", "/"), String.join(",", o.retrieved()), answer));
        }
        return r.toString();
    }

    private static String row(String label, long passed, long total) {
        double[] ci = wilson(passed, total);
        return String.format("| %s | %d | %d | %.1f%% | %.1f%%–%.1f%% |%n", label, passed, total,
                total == 0 ? 0 : 100.0 * passed / total, 100 * ci[0], 100 * ci[1]);
    }

    /** Wilson score interval at 95%. */
    static double[] wilson(long passed, long total) {
        if (total == 0) {
            return new double[] {0, 0};
        }
        double z = 1.96;
        double p = (double) passed / total;
        double denominator = 1 + z * z / total;
        double centre = (p + z * z / (2 * total)) / denominator;
        double margin = z * Math.sqrt(p * (1 - p) / total + z * z / (4.0 * total * total)) / denominator;
        return new double[] {Math.max(0, centre - margin), Math.min(1, centre + margin)};
    }

    static List<Case> cases() throws IOException {
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : lines("/agent-eval/cases.jsonl")) {
            List<Turn> turns = new ArrayList<>();
            for (JsonNode t : node.path("turns")) {
                List<List<String>> must = new ArrayList<>();
                t.path("must").forEach(group -> must.add(strings(group)));
                turns.add(new Turn(
                        t.path("message").asText(),
                        t.has("search") ? t.path("search").asBoolean() : null,
                        must,
                        strings(t.path("mustNot")),
                        t.path("notFound").asBoolean(false)));
            }
            cases.add(new Case(node.path("id").asText(), node.path("split").asText(), node.path("category").asText(), turns));
        }
        return cases;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    static List<JsonNode> lines(String resource) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        try (InputStream in = AgentAnswerEvaluationTest.class.getResourceAsStream(resource)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    nodes.add(JSON.readTree(line));
                }
            }
        }
        return nodes;
    }

    /** The corpus, chunked as production chunks it; only search reads it. */
    static final class InMemoryKnowledge implements KnowledgeRepository {
        private final List<KnowledgeCandidate> candidates = new ArrayList<>();
        private final Map<String, KnowledgeAccessScope> scopes = new LinkedHashMap<>();

        static InMemoryKnowledge load() throws IOException {
            InMemoryKnowledge knowledge = new InMemoryKnowledge();
            KnowledgeChunker chunker = new KnowledgeChunker(800, 120);
            for (JsonNode doc : lines("/agent-eval/corpus.jsonl")) {
                String id = doc.path("id").asText();
                KnowledgeVisibility visibility = KnowledgeVisibility.valueOf(doc.path("visibility").asText());
                KnowledgeAccessScope access =
                        visibility == KnowledgeVisibility.CUSTOMER
                                ? KnowledgeAccessScope.customer(doc.path("customerId").asText())
                                : new KnowledgeAccessScope(visibility, null);
                knowledge.scopes.put(id, access);
                for (KnowledgeChunk chunk : chunker.chunk(id, doc.path("content").asText())) {
                    knowledge.candidates.add(new KnowledgeCandidate(id, chunk.id(), chunk.chunkIndex(),
                            doc.path("title").asText(), KnowledgeSourceType.SOP, "eval://" + id, chunk.content(), access));
                }
            }
            return knowledge;
        }

        @Override
        public List<KnowledgeCandidate> findAccessibleCandidates(int maxCandidates, AccessPrincipal principal) {
            return candidates.stream()
                    .filter(candidate -> KnowledgeAccessPolicy.permits(principal, candidate.access()))
                    .limit(maxCandidates)
                    .toList();
        }

        @Override
        public List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates) {
            return candidates.stream().limit(maxCandidates).toList();
        }

        @Override
        public Map<String, KnowledgeAccessScope> findReadableScopes(Collection<String> documentIds) {
            Map<String, KnowledgeAccessScope> readable = new LinkedHashMap<>();
            documentIds.stream().filter(scopes::containsKey).forEach(id -> readable.put(id, scopes.get(id)));
            return readable;
        }

        @Override
        public KnowledgeDocument save(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public Optional<KnowledgeDocument> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<KnowledgeDocument> findAll() {
            return List.of();
        }

        @Override
        public void deleteById(String id) {
            throw new UnsupportedOperationException("read-only");
        }
    }
}

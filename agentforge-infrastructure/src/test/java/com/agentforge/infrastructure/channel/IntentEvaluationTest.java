package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.intent.IntentDecision;
import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentRoutingSettings;
import com.agentforge.channel.intent.IntentSource;
import com.agentforge.infrastructure.channel.IntentDataset.Item;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.WeComChannelProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The shipped intents against a real model, through a real model gateway. Costs money, so it is
 * off in every build. Run with a gateway client key allowed the route:
 *
 * <pre>
 * AGENTFORGE_MODEL_GATEWAY_API_KEY=gk-... mvn -pl agentforge-infrastructure test \
 *     -Dagentforge.intent.eval=true -Dtest=IntentEvaluationTest \
 *     [-Dagentforge.intent.eval.base-url=http://localhost:8090/v1/] [-Dagentforge.intent.eval.runs=3]
 * </pre>
 *
 * The report goes to {@code target/intent-eval/report.md}. Labels were written before the first
 * run; the prompt was not tuned on the test split.
 */
@EnabledIfSystemProperty(named = "agentforge.intent.eval", matches = "true")
class IntentEvaluationTest {

    private static final List<String> INTENTS = List.of("answer", "handoff", "out_of_scope");

    record Outcome(Item item, int run, IntentDecision decision, long millis) {
        boolean correct() {
            return decision.intent().equals(item.expected());
        }
    }

    @Test
    void shippedIntentsAgainstTheRealModel() throws Exception {
        String apiKey = System.getenv("AGENTFORGE_MODEL_GATEWAY_API_KEY");
        assertNotNull(apiKey, "AGENTFORGE_MODEL_GATEWAY_API_KEY must hold a gateway client key");
        int runs = Integer.getInteger("agentforge.intent.eval.runs", 3);

        WeComChannelProperties.Intent shipped = IntentDataset.shipped();
        shipped.setEnabled(true);
        shipped.setModelStage(true);
        String route = System.getProperty("agentforge.intent.eval.route", shipped.getModelRoute());
        IntentRoutingSettings settings = shipped.toSettings();

        ModelGatewayProperties gateway = new ModelGatewayProperties();
        gateway.setEnabled(true);
        gateway.setBaseUrl(System.getProperty("agentforge.intent.eval.base-url", "http://localhost:8090/v1/"));
        gateway.setApiKey(apiKey);
        gateway.setRequestTimeout(settings.timeout());
        ObjectMapper json = new ObjectMapper();
        IntentRouter router =
                new IntentRouter(
                        settings,
                        new GatewayIntentClassifier(
                                new ModelGatewayClient(HttpClient.newHttpClient(), json, gateway), route, json));

        List<Item> items = IntentDataset.load();
        List<Outcome> outcomes = new ArrayList<>();
        String stamp = Instant.now().toString().replace(":", "").substring(0, 15);
        for (int run = 1; run <= runs; run++) {
            int pass = run;
            for (Item item : items) {
                long started = System.nanoTime();
                // The production order: phrases on arrival, the classifier for the rest.
                IntentDecision decision =
                        router.onArrival(item.text())
                                .orElseGet(
                                        () ->
                                                router.classify(
                                                        item.text(),
                                                        "intent-eval-" + stamp + "-" + item.id() + "-r" + pass,
                                                        settings.timeout()));
                outcomes.add(new Outcome(item, run, decision, (System.nanoTime() - started) / 1_000_000));
            }
        }

        String report = report(route, runs, stamp, outcomes);
        Path out = Path.of("target", "intent-eval", "report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);
        assertEquals(runs * items.size(), outcomes.size());
    }

    private static String report(String route, int runs, String stamp, List<Outcome> all) {
        StringBuilder r = new StringBuilder();
        r.append("# Intent routing evaluation\n\n");
        r.append("- route: `").append(route).append("`, prompt: `").append(GatewayIntentClassifier.PROMPT_VERSION)
                .append("`, runs: ").append(runs).append(", correlation ids: `intent-eval-").append(stamp)
                .append("-<id>-r<run>`\n");
        r.append("- pipeline: shipped phrases first, then the classifier (as in production)\n\n");
        for (String split : List.of("test", "dev")) {
            List<Outcome> outcomes = all.stream().filter(o -> o.item().split().equals(split)).toList();
            r.append("## ").append(split).append(" split (").append(outcomes.size() / runs).append(" items x ")
                    .append(runs).append(" runs)\n\n");
            appendSplit(r, outcomes, runs);
        }
        return r.toString();
    }

    private static void appendSplit(StringBuilder r, List<Outcome> outcomes, int runs) {
        long correct = outcomes.stream().filter(Outcome::correct).count();
        double accuracy = (double) correct / outcomes.size();
        double[] ci = wilson(correct, outcomes.size());
        r.append(String.format("- accuracy: %.1f%% (%d/%d), 95%% CI %.1f%%–%.1f%%%n",
                accuracy * 100, correct, outcomes.size(), ci[0] * 100, ci[1] * 100));
        for (int run = 1; run <= runs; run++) {
            int k = run;
            List<Outcome> one = outcomes.stream().filter(o -> o.run() == k).toList();
            r.append(String.format("  - run %d: %.1f%%%n", run,
                    100.0 * one.stream().filter(Outcome::correct).count() / one.size()));
        }
        Map<IntentSource, Long> sources = new LinkedHashMap<>();
        outcomes.forEach(o -> sources.merge(o.decision().source(), 1L, Long::sum));
        r.append("- decided by: ").append(sources).append('\n');
        List<Long> modelMillis =
                outcomes.stream().filter(o -> o.decision().source() != IntentSource.RULE)
                        .map(Outcome::millis).sorted().toList();
        if (!modelMillis.isEmpty()) {
            r.append(String.format("- classifier latency: p50 %d ms, p95 %d ms, max %d ms%n",
                    modelMillis.get(modelMillis.size() / 2),
                    modelMillis.get((int) Math.min(modelMillis.size() - 1, Math.ceil(modelMillis.size() * 0.95) - 1)),
                    modelMillis.get(modelMillis.size() - 1)));
        }
        r.append("\n| expected \\ decided | answer | handoff | out_of_scope | recall |\n|---|---|---|---|---|\n");
        for (String expected : INTENTS) {
            r.append("| ").append(expected);
            long row = outcomes.stream().filter(o -> o.item().expected().equals(expected)).count();
            for (String decided : INTENTS) {
                r.append(" | ").append(outcomes.stream()
                        .filter(o -> o.item().expected().equals(expected) && o.decision().intent().equals(decided))
                        .count());
            }
            long hit = outcomes.stream().filter(o -> o.item().expected().equals(expected) && o.correct()).count();
            r.append(String.format(" | %.0f%% |%n", row == 0 ? 0 : 100.0 * hit / row));
        }
        r.append("| precision");
        for (String decided : INTENTS) {
            long col = outcomes.stream().filter(o -> o.decision().intent().equals(decided)).count();
            long hit = outcomes.stream().filter(o -> o.decision().intent().equals(decided) && o.correct()).count();
            r.append(String.format(" | %.0f%%", col == 0 ? 0 : 100.0 * hit / col));
        }
        r.append(" | |\n\n");

        Map<String, long[]> byCategory = new LinkedHashMap<>();
        outcomes.forEach(o -> {
            long[] c = byCategory.computeIfAbsent(o.item().category(), k -> new long[2]);
            c[0]++;
            if (o.correct()) c[1]++;
        });
        r.append("| category | correct |\n|---|---|\n");
        byCategory.forEach((category, c) -> r.append(String.format("| %s | %d/%d |%n", category, c[1], c[0])));

        r.append("\n**Harmful misroutes** (an answerable question refused or handed off, a request for a person refused):\n\n");
        List<Outcome> harmful =
                outcomes.stream()
                        .filter(o -> !o.correct())
                        .filter(o -> o.item().expected().equals("answer")
                                || (o.item().expected().equals("handoff") && o.decision().intent().equals("out_of_scope")))
                        .toList();
        if (harmful.isEmpty()) {
            r.append("none\n");
        }
        harmful.forEach(o -> r.append(line(o)));
        r.append("\n**Other misroutes:**\n\n");
        List<Outcome> other = outcomes.stream().filter(o -> !o.correct()).filter(o -> !harmful.contains(o)).toList();
        if (other.isEmpty()) {
            r.append("none\n");
        }
        other.forEach(o -> r.append(line(o)));

        Map<String, List<String>> perItem = new LinkedHashMap<>();
        outcomes.forEach(o -> perItem.computeIfAbsent(o.item().id() + " " + o.item().text(), k -> new ArrayList<>())
                .add(o.decision().intent()));
        List<String> unstable =
                perItem.entrySet().stream()
                        .filter(e -> e.getValue().stream().distinct().count() > 1)
                        .map(e -> "- " + e.getKey() + ": " + e.getValue() + "\n")
                        .toList();
        r.append("\n**Unstable across runs:** ").append(unstable.isEmpty() ? "none\n" : "\n\n");
        unstable.forEach(r::append);
        r.append('\n');
    }

    private static String line(Outcome o) {
        IntentDecision d = o.decision();
        return String.format("- %s [%s] \"%s\": expected %s, got %s (%s%s) run %d%n",
                o.item().id(), o.item().category(), o.item().text(), o.item().expected(), d.intent(),
                d.source(), d.confidence() == null ? "" : String.format(", %.2f", d.confidence()), o.run());
    }

    /** 95% Wilson interval: with about a hundred items, only the direction is meaningful. */
    private static double[] wilson(long hits, long n) {
        double z = 1.96, p = (double) hits / n, z2 = z * z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }
}

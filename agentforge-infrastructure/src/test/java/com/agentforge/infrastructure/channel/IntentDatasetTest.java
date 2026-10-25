package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.intent.IntentDecision;
import com.agentforge.channel.intent.IntentRouter;
import com.agentforge.channel.intent.IntentRoutingSettings;
import com.agentforge.channel.intent.IntentSource;
import com.agentforge.infrastructure.channel.IntentDataset.Item;
import com.agentforge.infrastructure.config.WeComChannelProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shipped intents and phrases against the labelled set, deterministically, in every build. */
class IntentDatasetTest {

    @Test
    void theShippedIntentsAreValidAndTheDefaultIsAnswered() throws Exception {
        WeComChannelProperties.Intent shipped = IntentDataset.shipped();
        shipped.setEnabled(true);

        IntentRoutingSettings settings = shipped.toSettings();

        assertEquals("answer", settings.defaultIntent());
        assertEquals(
                List.of("answer", "handoff", "out_of_scope"),
                settings.intents().stream().map(i -> i.name()).toList());
        assertEquals("已登记，请直接联系群里的客服同事。", new IntentRouter(settings, null).intent("handoff").reply());
    }

    @Test
    void theSetIsWellFormed() throws Exception {
        List<Item> items = IntentDataset.load();
        Set<String> intents = Set.of("answer", "handoff", "out_of_scope");
        Set<String> ids = new HashSet<>();
        for (Item item : items) {
            assertTrue(ids.add(item.id()), "duplicate id " + item.id());
            assertTrue(intents.contains(item.expected()), item.id());
            assertTrue(Set.of("dev", "test").contains(item.split()), item.id());
            assertFalse(item.text().isBlank(), item.id());
        }
        assertTrue(items.size() >= 100);
        assertTrue(items.stream().anyMatch(i -> i.split().equals("dev")));
    }

    /**
     * Pins exactly what the phrases decide on the set: every explicit request they should catch,
     * no false hit outside the negations, which are a known limit and counted here.
     */
    @Test
    void thePhrasesDecideExactlyWhatTheSetSays() throws Exception {
        WeComChannelProperties.Intent shipped = IntentDataset.shipped();
        shipped.setEnabled(true);
        shipped.setModelStage(true);
        IntentRouter router = new IntentRouter(shipped.toSettings(), (t, i, c, d) -> null);

        List<String> mismatches = new ArrayList<>();
        int misfires = 0;
        for (Item item : IntentDataset.load()) {
            IntentDecision decision = router.onArrival(item.text()).orElse(null);
            String decided = decision == null ? null : decision.intent();
            if (decision != null) {
                assertEquals(IntentSource.RULE, decision.source());
            }
            if (!java.util.Objects.equals(item.rule(), decided)) {
                mismatches.add(item.id() + " " + item.text() + " -> " + decided);
            }
            if (decided != null && !decided.equals(item.expected())) {
                misfires++;
                assertEquals("negation", item.category(), "only negations may misfire: " + item.text());
            }
        }
        assertEquals(List.of(), mismatches);
        assertEquals(6, misfires, "the six negations, e.g. \"不用转人工\", are handed off by phrase");
    }
}

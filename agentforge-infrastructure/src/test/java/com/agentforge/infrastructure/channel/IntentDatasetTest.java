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
     * and no false hit. The six negations ("不用转人工") were handed off by phrase until V0.14's
     * negation guard; they now go to the classifier.
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
        assertEquals(0, misfires);
    }

    /**
     * The negation guard on samples written before it was: they were not used to choose the
     * negation words. What it gets wrong is listed, not tuned away.
     */
    @Test
    void theNegationGuardOnTheHeldOutSet() throws Exception {
        WeComChannelProperties.Intent shipped = IntentDataset.shipped();
        shipped.setEnabled(true);
        shipped.setModelStage(true);
        IntentRouter router = new IntentRouter(shipped.toSettings(), (t, i, c, d) -> null);

        List<String> wrong = new ArrayList<>();
        for (Item item : IntentDataset.loadNegationHoldout()) {
            String decided = router.onArrival(item.text()).map(IntentDecision::intent).orElse(null);
            boolean handedOff = "handoff".equals(decided);
            if (handedOff != item.expected().equals("handoff")) {
                wrong.add(item.id());
            }
        }
        // 15 of 16. n08 "我不想投诉" is handed off: "不想" is not one of the negation words, and
        // adding it after seeing this sample would make the set a tuning set.
        assertEquals(List.of("n08"), wrong);
    }
}

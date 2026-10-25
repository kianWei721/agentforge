package com.agentforge.channel.intent;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Intent routing for a channel.
 *
 * @param modelStage whether messages no phrase decides go to the classifier; without it they go to
 *     the default intent
 * @param defaultIntent where a message goes when nothing decides otherwise, including every
 *     classifier failure. It must be answered by the agent: were it a fixed reply, an unreachable
 *     model would hand every message to a person, or refuse them all.
 * @param minConfidence only intents other than the default must reach it; the classifier's own
 *     confidence is poorly calibrated, so the asymmetry is what keeps a doubtful message answered
 * @param maxInputChars the question is cut to this before it goes to the classifier
 */
public record IntentRoutingSettings(
        boolean modelStage,
        String defaultIntent,
        Duration timeout,
        double minConfidence,
        int maxInputChars,
        List<IntentDefinition> intents) {

    public IntentRoutingSettings {
        intents = intents == null ? List.of() : List.copyOf(intents);
        if (intents.isEmpty()) {
            throw new IllegalArgumentException("intent routing needs at least one intent");
        }
        Set<String> names = new HashSet<>();
        for (IntentDefinition intent : intents) {
            if (!names.add(intent.name())) {
                throw new IllegalArgumentException("intent '" + intent.name() + "' is defined twice");
            }
        }
        IntentDefinition fallback =
                intents.stream().filter(i -> i.name().equals(defaultIntent)).findFirst().orElse(null);
        if (fallback == null) {
            throw new IllegalArgumentException("default intent '" + defaultIntent + "' is not defined");
        }
        if (fallback.target() != IntentTarget.ANSWER) {
            throw new IllegalArgumentException(
                    "default intent '" + defaultIntent + "' must be answered by the agent");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("intent classification timeout must be positive");
        }
        if (!(minConfidence > 0 && minConfidence <= 1)) {
            throw new IllegalArgumentException("intent min-confidence must be in (0, 1]");
        }
        if (maxInputChars < 20) {
            throw new IllegalArgumentException("intent max-input-chars must be at least 20");
        }
    }
}

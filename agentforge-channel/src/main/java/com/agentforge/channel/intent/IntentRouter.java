package com.agentforge.channel.intent;

import com.agentforge.channel.intent.IntentClassifier.Classification;
import com.agentforge.channel.intent.IntentClassifier.InvalidClassificationException;
import com.agentforge.core.model.ModelCallFailedException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides where a message goes, in two stages.
 *
 * <p>When the message arrives, on the callback thread: configured phrases, matched as substrings
 * in configuration order. A hit decides at once, so "转人工" gets its reply even when the workers
 * are overloaded. Then, on the worker and only if no phrase matched: the classifier. Its answer is
 * checked against the configured intents here, whatever the model was told; anything else, and any
 * failure, sends the message to the default intent, which the agent answers. A misrouted message
 * therefore never reaches more than the channel agent could already do.
 */
public final class IntentRouter {

    /** Routing off: every message goes to the channel agent and no intent is recorded. */
    public static final IntentRouter DISABLED = new IntentRouter();

    /**
     * Less than this and the classifier is not called. The gateway keeps half a second of every
     * timeout as its own margin, so a shorter call would only fail, possibly after being billed.
     */
    static final Duration MIN_CLASSIFY_TIME = Duration.ofSeconds(1);

    /**
     * Punctuation, line breaks, full-width spaces and tildes, which chats use for commas; not the
     * plain space: a phrase may hold one ("write code"). One character class, so splitting cannot
     * backtrack on the callback thread.
     */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[，。！？；、,.!?;\\r\\n\\u3000～~…]+");

    private static final List<String> NEGATIONS = List.of("不用", "不要", "不是", "不需要", "无需", "别", "没有", "暂时不");

    /** Words that hold a negation's characters without negating ("是不是", "特别"). */
    private static final List<String> NOT_NEGATIONS =
            List.of("是不是", "要不要", "用不用", "需不需要", "特别", "区别", "别人", "别的", "级别", "个别", "分别",
                    "类别", "告别");

    /** A negation counts only this close before the phrase: "不行，我要投诉，客服特别慢" still hands off. */
    static final int NEGATION_WINDOW = 4;

    private final IntentRoutingSettings settings;
    private final IntentClassifier classifier;
    private final Map<String, IntentDefinition> byName = new LinkedHashMap<>();

    private IntentRouter() {
        this.settings = null;
        this.classifier = null;
    }

    public IntentRouter(IntentRoutingSettings settings, IntentClassifier classifier) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.modelStage() && classifier == null) {
            throw new IllegalArgumentException("the model stage needs a classifier");
        }
        this.classifier = classifier;
        settings.intents().forEach(intent -> byName.put(intent.name(), intent));
    }

    public boolean enabled() {
        return settings != null;
    }

    public boolean modelStage() {
        return enabled() && settings.modelStage();
    }

    public Duration timeout() {
        return settings.timeout();
    }

    public IntentDefinition intent(String name) {
        return byName.get(name);
    }

    /**
     * When the message arrives: a matching phrase decides; without the model stage the default
     * intent does. Empty when the classifier is to decide.
     */
    public Optional<IntentDecision> onArrival(String text) {
        if (!enabled()) {
            return Optional.empty();
        }
        // A negated phrase ("不用转人工，...") decides nothing: the classifier, or the default intent,
        // gets the message. "不行，我要投诉" still matches: the negation is in another clause.
        String[] clauses = CLAUSE_BREAK.split(text.toLowerCase(Locale.ROOT));
        for (IntentDefinition intent : settings.intents()) {
            for (String clause : clauses) {
                if (matchesUnnegated(intent, clause)) {
                    return Optional.of(decision(intent, IntentSource.RULE, null, null));
                }
            }
        }
        return settings.modelStage()
                ? Optional.empty()
                : Optional.of(fallback(IntentSource.DEFAULT, null));
    }

    /** On the worker. Never throws: every failure is the default intent, with its reason. */
    public IntentDecision classify(String text, String correlationId, Duration timeout) {
        if (timeout == null || timeout.compareTo(MIN_CLASSIFY_TIME) < 0) {
            return fallback(IntentSource.FALLBACK_NO_TIME, null);
        }
        Classification result;
        try {
            result = classifier.classify(cut(text), settings.intents(), correlationId, timeout);
        } catch (InvalidClassificationException invalid) {
            return fallback(IntentSource.FALLBACK_INVALID_OUTPUT, invalid.gatewayRequestId());
        } catch (RuntimeException failed) {
            ModelCallFailedException modelFailure = ModelCallFailedException.find(failed);
            return fallback(
                    IntentSource.FALLBACK_MODEL_ERROR,
                    modelFailure == null ? null : modelFailure.gatewayRequestId());
        }
        IntentDefinition chosen = result == null ? null : byName.get(result.intent());
        if (chosen == null
                || Double.isNaN(result.confidence())
                || result.confidence() < 0
                || result.confidence() > 1) {
            return fallback(
                    IntentSource.FALLBACK_INVALID_OUTPUT,
                    result == null ? null : result.gatewayRequestId());
        }
        if (!chosen.name().equals(settings.defaultIntent())
                && result.confidence() < settings.minConfidence()) {
            return fallback(IntentSource.FALLBACK_LOW_CONFIDENCE, result.gatewayRequestId());
        }
        return decision(chosen, IntentSource.MODEL, result.confidence(), result.gatewayRequestId());
    }

    /** Some occurrence of one of the intent's phrases without a negation just before it. */
    private static boolean matchesUnnegated(IntentDefinition intent, String clause) {
        for (String keyword : intent.keywords()) {
            for (int at = clause.indexOf(keyword); at >= 0; at = clause.indexOf(keyword, at + 1)) {
                if (!negatedBefore(clause, at)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean negatedBefore(String clause, int at) {
        // Read a little further back so a compound reaching into the window is recognised whole.
        String before = clause.substring(Math.max(0, at - NEGATION_WINDOW - 2), at);
        for (String word : NOT_NEGATIONS) {
            before = before.replace(word, "#".repeat(word.length()));
        }
        String window = before.substring(Math.max(0, before.length() - NEGATION_WINDOW));
        for (String negation : NEGATIONS) {
            if (window.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    private String cut(String text) {
        int max = settings.maxInputChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    /** The default intent; a confidence the classifier gave another intent is not kept with it. */
    private IntentDecision fallback(IntentSource source, String gatewayRequestId) {
        return decision(byName.get(settings.defaultIntent()), source, null, gatewayRequestId);
    }

    private static IntentDecision decision(
            IntentDefinition intent, IntentSource source, Double confidence, String gatewayRequestId) {
        return new IntentDecision(intent.name(), intent.target(), source, confidence, gatewayRequestId);
    }
}

package com.agentforge.app.channel;

import com.agentforge.channel.ChannelMetrics;
import com.agentforge.channel.handoff.HandoffStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * WeCom channel admission and turn metrics:
 *
 * <ul>
 *   <li>{@code agentforge.wecom.admission{outcome}}: ACCEPTED, refusals by reason, and the
 *       messages a phrase hands to a person or refuses on arrival (HANDOFF, INTENT_REFUSED)
 *   <li>{@code agentforge.wecom.queue.wait}: from admission to getting a worker
 *   <li>{@code agentforge.wecom.turn.duration}: how long a worker was busy
 *   <li>{@code agentforge.wecom.turn{outcome}}: DONE, the failure code, or HANDOFF and
 *       INTENT_REFUSED when the classifier decided them inside the turn
 *   <li>{@code agentforge.wecom.intent{intent,source}}: every routed message once, however it was
 *       decided; count hand-offs here ({@code intent=handoff}), not under admission or turn alone
 *   <li>{@code agentforge.wecom.handoff.opened{result,source}}: NEW tickets and MERGED messages;
 *       their sum matches {@code intent{intent=handoff}}
 *   <li>{@code agentforge.wecom.handoff.notify{outcome}}, {@code .notify.delay}: staff-group notices
 *   <li>{@code agentforge.wecom.handoff.open{status}}: PENDING and CLAIMED tickets, counted on
 *       every notice tick (so only with a webhook)
 *   <li>{@code agentforge.wecom.handoff.claim.wait}, {@code .resolve.duration{resolution}}: how
 *       long tickets wait; {@code resolution=FALSE_TRIGGER} is the misroute rate
 * </ul>
 *
 * <p>Failure codes include the model gateway's, which this side does not control, so outcome
 * values are capped: past {@value #MAX_OUTCOMES} distinct values, or for anything not shaped like
 * an upper-case code, the tag is {@code OTHER}. The inbound table keeps the original code. Intent
 * names (bounded by configuration) and sources (an enum) are kept out of that cap, so a flood of
 * gateway codes can never hide a fallback.
 */
@Component
@ConditionalOnProperty(
        prefix = "agentforge.channel.wecom",
        name = "enabled",
        havingValue = "true")
public class MicrometerChannelMetrics implements ChannelMetrics {

    static final int MAX_OUTCOMES = 64;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern INTENT = Pattern.compile("[a-z][a-z_]{0,31}");

    private final MeterRegistry registry;
    private final Set<String> outcomes = ConcurrentHashMap.newKeySet();
    private final Map<HandoffStatus, AtomicLong> openHandoffs = new ConcurrentHashMap<>();
    private final Timer queueWait;
    private final Timer turnDuration;

    public MicrometerChannelMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.queueWait = registry.timer("agentforge.wecom.queue.wait");
        this.turnDuration = registry.timer("agentforge.wecom.turn.duration");
    }

    @Override
    public void admission(String outcome) {
        registry.counter("agentforge.wecom.admission", "outcome", bounded(outcome)).increment();
    }

    @Override
    public void queueWait(Duration wait) {
        queueWait.record(wait);
    }

    @Override
    public void turnDuration(Duration duration) {
        turnDuration.record(duration);
    }

    @Override
    public void turnOutcome(String outcome) {
        registry.counter("agentforge.wecom.turn", "outcome", bounded(outcome)).increment();
    }

    @Override
    public void intent(String intent, String source) {
        registry.counter(
                        "agentforge.wecom.intent",
                        "intent",
                        matches(INTENT, intent),
                        "source",
                        matches(CODE, source))
                .increment();
    }

    @Override
    public void handoffOpened(boolean merged, String source) {
        registry.counter(
                        "agentforge.wecom.handoff.opened",
                        "result",
                        merged ? "MERGED" : "NEW",
                        "source",
                        matches(CODE, source))
                .increment();
    }

    @Override
    public void handoffNotify(String outcome) {
        registry.counter("agentforge.wecom.handoff.notify", "outcome", matches(CODE, outcome)).increment();
    }

    @Override
    public void handoffNotifyDelay(Duration delay) {
        registry.timer("agentforge.wecom.handoff.notify.delay").record(delay);
    }

    @Override
    public void openHandoffs(Map<HandoffStatus, Long> counts) {
        counts.forEach(
                (status, count) ->
                        openHandoffs.computeIfAbsent(
                                        status,
                                        key -> registry.gauge(
                                                "agentforge.wecom.handoff.open",
                                                Tags.of("status", key.name()),
                                                new AtomicLong()))
                                .set(count));
    }

    @Override
    public void handoffClaimWait(Duration wait) {
        registry.timer("agentforge.wecom.handoff.claim.wait").record(wait);
    }

    @Override
    public void handoffResolved(String resolution, Duration open) {
        registry.timer("agentforge.wecom.handoff.resolve.duration", "resolution", matches(CODE, resolution))
                .record(open);
    }

    private static String matches(Pattern shape, String value) {
        return value != null && shape.matcher(value).matches() ? value : "OTHER";
    }

    String bounded(String outcome) {
        if (outcome == null || !CODE.matcher(outcome).matches()) {
            return "OTHER";
        }
        if (outcomes.contains(outcome)) {
            return outcome;
        }
        // Racing threads may pass the cap by a few values, which still bounds it.
        if (outcomes.size() >= MAX_OUTCOMES) {
            return "OTHER";
        }
        outcomes.add(outcome);
        return outcome;
    }
}

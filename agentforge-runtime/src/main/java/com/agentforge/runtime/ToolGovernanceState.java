package com.agentforge.runtime;

import com.agentforge.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

final class ToolGovernanceState {

    static final String DECISION_ALLOW = "ALLOW";
    static final String DECISION_BLOCK = "BLOCK";
    static final String REASON_REPEATED_NON_RETRYABLE_FAILURE =
            "repeated_non_retryable_failure";
    static final String FAILURE_REPEATED_NON_RETRYABLE_CALL =
            "REPEATED_NON_RETRYABLE_CALL";

    private final ToolGovernanceOptions options;
    private final ConcurrentMap<String, PriorFailure> nonRetryableFailures =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ToolInvocationTelemetry> telemetryByCallId =
            new ConcurrentHashMap<>();
    private final AtomicInteger recordedInvocations = new AtomicInteger();

    ToolGovernanceState(ToolGovernanceOptions options) {
        this.options = options == null ? ToolGovernanceOptions.defaults() : options;
    }

    ToolGovernanceDecision beforeInvocation(
            String toolCallId, String toolName, Map<String, Object> arguments) {
        String fingerprint = fingerprint(toolName, arguments);
        if (!options.enabled() || !options.blockRepeatedNonRetryableCalls()) {
            return ToolGovernanceDecision.allow(fingerprint);
        }

        PriorFailure previous = nonRetryableFailures.get(fingerprint);
        if (previous == null) {
            return ToolGovernanceDecision.allow(fingerprint);
        }
        return ToolGovernanceDecision.block(
                fingerprint,
                REASON_REPEATED_NON_RETRYABLE_FAILURE,
                previous.toolCallId(),
                previous.failureCode());
    }

    void recordResult(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolResult result,
            long durationMs) {
        if (toolCallId == null || toolCallId.isBlank() || result == null) {
            return;
        }
        if (recordedInvocations.incrementAndGet() > options.maxRecordedInvocations()) {
            return;
        }

        telemetryByCallId.put(
                toolCallId,
                new ToolInvocationTelemetry(
                        Math.max(0L, durationMs),
                        result.success() ? null : result.failureCode(),
                        result.success() ? null : result.retryable(),
                        decision.decision(),
                        decision.reason(),
                        decision.argumentFingerprint(),
                        decision.blockedByToolCallId(),
                        decision.blockedByFailureCode()));

        if (options.enabled()
                && options.blockRepeatedNonRetryableCalls()
                && !result.success()
                && !result.retryable()
                && !FAILURE_REPEATED_NON_RETRYABLE_CALL.equals(result.failureCode())) {
            nonRetryableFailures.putIfAbsent(
                    decision.argumentFingerprint(),
                    new PriorFailure(toolCallId, result.failureCode()));
        }
    }

    void recordException(
            String toolCallId,
            ToolGovernanceDecision decision,
            long durationMs) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        if (recordedInvocations.incrementAndGet() > options.maxRecordedInvocations()) {
            return;
        }
        telemetryByCallId.put(
                toolCallId,
                new ToolInvocationTelemetry(
                        Math.max(0L, durationMs),
                        "UNHANDLED_EXCEPTION",
                        null,
                        decision.decision(),
                        decision.reason(),
                        decision.argumentFingerprint(),
                        decision.blockedByToolCallId(),
                        decision.blockedByFailureCode()));
    }

    ToolInvocationTelemetry telemetry(String toolCallId) {
        return toolCallId == null ? null : telemetryByCallId.get(toolCallId);
    }

    static String fingerprint(String toolName, Map<String, Object> arguments) {
        String canonical =
                (toolName == null ? "" : toolName.trim())
                        + "|"
                        + canonicalValue(arguments == null ? Map.of() : arguments);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(escape(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(canonicalValue(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(canonicalValue(item));
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(canonicalValue(java.lang.reflect.Array.get(value, i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(""", "\\"");
    }

    record ToolGovernanceDecision(
            boolean allowed,
            String decision,
            String reason,
            String argumentFingerprint,
            String blockedByToolCallId,
            String blockedByFailureCode) {

        static ToolGovernanceDecision allow(String fingerprint) {
            return new ToolGovernanceDecision(
                    true, DECISION_ALLOW, "policy_allow", fingerprint, null, null);
        }

        static ToolGovernanceDecision block(
                String fingerprint,
                String reason,
                String blockedByToolCallId,
                String blockedByFailureCode) {
            return new ToolGovernanceDecision(
                    false,
                    DECISION_BLOCK,
                    reason,
                    fingerprint,
                    blockedByToolCallId,
                    blockedByFailureCode);
        }
    }

    record ToolInvocationTelemetry(
            long toolDurationMs,
            String failureCode,
            Boolean retryable,
            String governanceDecision,
            String governanceReason,
            String argumentFingerprint,
            String blockedByToolCallId,
            String blockedByFailureCode) {}

    private record PriorFailure(String toolCallId, String failureCode) {}
}

package com.agentforge.runtime;

import com.agentforge.runtime.confirmation.ToolConfirmation;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.ToolRiskLevel;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
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
        recordResult(
                toolCallId,
                decision,
                ToolPolicyEvaluation.allow("legacy_allow"),
                ToolRiskLevel.LOW,
                true,
                result,
                durationMs,
                null);
    }

    void recordResult(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolResult result,
            long durationMs,
            OutboundHttpTelemetry outbound) {
        recordResult(
                toolCallId,
                decision,
                ToolPolicyEvaluation.allow("legacy_allow"),
                ToolRiskLevel.LOW,
                true,
                result,
                durationMs,
                outbound);
    }

    void recordResult(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolPolicyEvaluation policy,
            ToolRiskLevel riskLevel,
            boolean readOnly,
            ToolResult result,
            long durationMs,
            OutboundHttpTelemetry outbound) {
        recordResult(toolCallId, decision, policy, riskLevel, readOnly, result, durationMs, outbound, null);
    }

    void recordResult(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolPolicyEvaluation policy,
            ToolRiskLevel riskLevel,
            boolean readOnly,
            ToolResult result,
            long durationMs,
            OutboundHttpTelemetry outbound,
            ToolConfirmation confirmation) {
        if (toolCallId == null || toolCallId.isBlank() || result == null) {
            return;
        }
        if (recordedInvocations.incrementAndGet() > options.maxRecordedInvocations()) {
            return;
        }

        ToolPolicyEvaluation effectivePolicy =
                policy == null ? ToolPolicyEvaluation.allow("unspecified") : policy;
        boolean policyAllowsExecution =
                effectivePolicy.decision() == ToolPolicyDecision.ALLOW;

        Integer outboundAttempts;
        String outboundOutcome;
        if (!policyAllowsExecution) {
            outboundAttempts = Integer.valueOf(0);
            outboundOutcome =
                    effectivePolicy.decision() == ToolPolicyDecision.DENY
                            ? "POLICY_DENIED"
                            : "CONFIRMATION_REQUIRED";
        } else if (!decision.allowed()) {
            outboundAttempts = Integer.valueOf(0);
            outboundOutcome = "GOVERNANCE_BLOCKED";
        } else {
            outboundAttempts =
                    outbound == null ? null : Integer.valueOf(outbound.attempts());
            outboundOutcome = outbound == null ? null : outbound.outcome();
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
                        decision.blockedByFailureCode(),
                        outbound == null ? null : outbound.protocol(),
                        outbound == null ? null : outbound.downstreamSystem(),
                        outboundAttempts,
                        outbound == null ? null : outbound.httpStatus(),
                        outbound == null ? null : outbound.businessCode(),
                        outbound == null ? null : outbound.downstreamDurationMs(),
                        outboundOutcome,
                        riskLevel == null ? ToolRiskLevel.HIGH.name() : riskLevel.name(),
                        readOnly,
                        effectivePolicy.decision().name(),
                        effectivePolicy.reason(),
                        confirmation == null ? null : confirmation.id(),
                        confirmation == null ? null : confirmation.status().name()));

        if (policyAllowsExecution
                && decision.allowed()
                && options.enabled()
                && options.blockRepeatedNonRetryableCalls()
                && !result.success()
                && !result.retryable()
                && !FAILURE_REPEATED_NON_RETRYABLE_CALL.equals(result.failureCode())
                && !RetrievalBudgetState.FAILURE_RETRIEVAL_BUDGET_EXHAUSTED.equals(
                        result.failureCode())) {
            nonRetryableFailures.putIfAbsent(
                    decision.argumentFingerprint(),
                    new PriorFailure(toolCallId, result.failureCode()));
        }
    }

    void recordException(
            String toolCallId,
            ToolGovernanceDecision decision,
            long durationMs) {
        recordException(
                toolCallId,
                decision,
                ToolPolicyEvaluation.allow("legacy_allow"),
                ToolRiskLevel.LOW,
                true,
                durationMs,
                null);
    }

    void recordException(
            String toolCallId,
            ToolGovernanceDecision decision,
            long durationMs,
            OutboundHttpTelemetry outbound) {
        recordException(
                toolCallId,
                decision,
                ToolPolicyEvaluation.allow("legacy_allow"),
                ToolRiskLevel.LOW,
                true,
                durationMs,
                outbound);
    }

    void recordException(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolPolicyEvaluation policy,
            ToolRiskLevel riskLevel,
            boolean readOnly,
            long durationMs,
            OutboundHttpTelemetry outbound) {
        recordException(toolCallId, decision, policy, riskLevel, readOnly, durationMs, outbound, null);
    }

    void recordException(
            String toolCallId,
            ToolGovernanceDecision decision,
            ToolPolicyEvaluation policy,
            ToolRiskLevel riskLevel,
            boolean readOnly,
            long durationMs,
            OutboundHttpTelemetry outbound,
            ToolConfirmation confirmation) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        if (recordedInvocations.incrementAndGet() > options.maxRecordedInvocations()) {
            return;
        }
        ToolPolicyEvaluation effectivePolicy =
                policy == null ? ToolPolicyEvaluation.allow("unspecified") : policy;
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
                        decision.blockedByFailureCode(),
                        outbound == null ? null : outbound.protocol(),
                        outbound == null ? null : outbound.downstreamSystem(),
                        outbound == null ? null : outbound.attempts(),
                        outbound == null ? null : outbound.httpStatus(),
                        outbound == null ? null : outbound.businessCode(),
                        outbound == null ? null : outbound.downstreamDurationMs(),
                        outbound == null ? null : outbound.outcome(),
                        riskLevel == null ? ToolRiskLevel.HIGH.name() : riskLevel.name(),
                        readOnly,
                        effectivePolicy.decision().name(),
                        effectivePolicy.reason(),
                        confirmation == null ? null : confirmation.id(),
                        confirmation == null ? null : confirmation.status().name()));
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

        static ToolGovernanceDecision skipped(String fingerprint, String reason) {
            return new ToolGovernanceDecision(
                    true, "SKIP", reason, fingerprint, null, null);
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
            String blockedByFailureCode,
            String outboundProtocol,
            String downstreamSystem,
            Integer outboundAttempts,
            Integer httpStatus,
            Integer businessCode,
            Long downstreamDurationMs,
            String outboundOutcome,
            String toolRiskLevel,
            boolean toolReadOnly,
            String policyDecision,
            String policyReason,
            String confirmationId,
            String confirmationStatus) {}

    private record PriorFailure(String toolCallId, String failureCode) {}
}

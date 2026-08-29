package com.agentforge.runtime;

public record ToolGovernanceOptions(
        boolean enabled,
        boolean blockRepeatedNonRetryableCalls,
        int maxRecordedInvocations) {

    public ToolGovernanceOptions {
        if (maxRecordedInvocations < 1 || maxRecordedInvocations > 4096) {
            throw new IllegalArgumentException(
                    "maxRecordedInvocations must be between 1 and 4096");
        }
    }

    public static ToolGovernanceOptions defaults() {
        return new ToolGovernanceOptions(true, true, 256);
    }
}

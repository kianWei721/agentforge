package com.agentforge.tool.device;

public record LureLampStateSnapshot(
        String deviceId,
        LureLampState state,
        String observedAt,
        String source) {

    public LureLampStateSnapshot {
        state = state == null ? LureLampState.UNKNOWN : state;
        source = source == null || source.isBlank() ? "unknown" : source.trim();
    }
}

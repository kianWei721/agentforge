package com.agentforge.tool.device;

public enum LureLampState {
    ON,
    OFF,
    UNKNOWN;

    public static LureLampState fromBoolean(boolean enabled) {
        return enabled ? ON : OFF;
    }
}

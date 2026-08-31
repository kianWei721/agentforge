package com.agentforge.tool.device;

public record DevicePropertyValue(
        String name, Object value, String observedAt, String source, String shadowStatus) {}

package com.agentforge.tool.device;

public record DeviceEventRecord(
        Long eventId, String eventName, Object data, String occurredAt) {}

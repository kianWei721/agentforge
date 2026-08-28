package com.agentforge.tool;

import java.util.Map;

public record ToolResult(boolean success, String message, Map<String, Object> data) {}

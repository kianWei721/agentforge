package com.agentforge.runtime;

public record AgentStreamChunk(String type, String content, String source, boolean done) {
    public static AgentStreamChunk message(String content, String source) { return new AgentStreamChunk("message", content, source, false); }
    public static AgentStreamChunk done(String source) { return new AgentStreamChunk("done", "", source, true); }
}

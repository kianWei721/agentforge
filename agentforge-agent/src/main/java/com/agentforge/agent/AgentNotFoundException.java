package com.agentforge.agent;

public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String agentId) {
        super("Agent not found: " + agentId);
    }
}

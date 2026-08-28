package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.agent")
public class AgentRuntimeProperties {
    private boolean enabled = false;
    private String name = "agentforge-assistant";
    private String systemPrompt = "你是 AgentForge 企业智能体平台中的助手。回答准确、简洁；不确定时明确说明。";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}

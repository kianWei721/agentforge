package com.agentforge.infrastructure.config;

import com.agentforge.runtime.HarnessMemoryOptions;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.agent")
public class AgentRuntimeProperties {
    private boolean enabled = false;
    private String name = "agentforge-assistant";
    private String systemPrompt = "你是 AgentForge 企业智能体平台中的助手。回答准确、简洁；不确定时明确说明。";
    private final Memory memory = new Memory();
    /** Turns before the agent runtime is replaced, releasing its per-session caches; 0 never. */
    private long runtimeGenerationTurns = 5000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public Memory getMemory() { return memory; }
    public long getRuntimeGenerationTurns() { return runtimeGenerationTurns; }
    public void setRuntimeGenerationTurns(long runtimeGenerationTurns) { this.runtimeGenerationTurns = runtimeGenerationTurns; }

    /** Compaction bounds; conversation content is never written to the workspace. */
    public static class Memory {
        private String workspace;
        private int compactionTriggerMessages = 50;
        private int compactionTriggerTokens = 12000;
        private int compactionKeepMessages = 20;
        private int summaryMaxChars = 800;
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public int getCompactionTriggerMessages() { return compactionTriggerMessages; }
        public void setCompactionTriggerMessages(int value) { this.compactionTriggerMessages = value; }
        public int getCompactionTriggerTokens() { return compactionTriggerTokens; }
        public void setCompactionTriggerTokens(int value) { this.compactionTriggerTokens = value; }
        public int getCompactionKeepMessages() { return compactionKeepMessages; }
        public void setCompactionKeepMessages(int value) { this.compactionKeepMessages = value; }
        public int getSummaryMaxChars() { return summaryMaxChars; }
        public void setSummaryMaxChars(int summaryMaxChars) { this.summaryMaxChars = summaryMaxChars; }

        /** Fails startup on an inconsistent configuration. */
        public HarnessMemoryOptions toOptions() {
            Path root = StringUtils.hasText(workspace)
                    ? Path.of(workspace.trim())
                    : HarnessMemoryOptions.defaults().workspace();
            try {
                return new HarnessMemoryOptions(
                        root, compactionTriggerMessages, compactionTriggerTokens,
                        compactionKeepMessages, summaryMaxChars);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("Invalid agentforge.agent.memory: " + invalid.getMessage(), invalid);
            }
        }
    }
}

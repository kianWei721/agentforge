package com.agentforge.runtime;

import java.nio.file.Path;

/**
 * How the AgentScope harness keeps a long conversation in bounds.
 *
 * <p>Compaction stays on: session state lives for a day, and without it the context of a busy
 * group would grow until it no longer fits the model. What it must not do is write conversation
 * content to local disk, which AgentScope does by default before compacting (flush and offload),
 * when persisting sessions, and when evicting large tool results. All three are switched off, so
 * {@link #workspace()} only exists because the harness needs a root; nothing is written there.
 *
 * @param compactionTriggerMessages compact once the context holds this many messages
 * @param compactionTriggerTokens or once it holds roughly this many tokens, whichever comes first
 * @param compactionKeepMessages recent messages kept verbatim after a compaction
 * @param summaryMaxChars length the summary is asked to stay within
 */
public record HarnessMemoryOptions(
        Path workspace,
        int compactionTriggerMessages,
        int compactionTriggerTokens,
        int compactionKeepMessages,
        int summaryMaxChars) {

    private static final String SUMMARY_PROMPT =
            """
            你在为一段助手对话压缩上下文，你的输出会替换下面这段历史。
            只保留继续对话需要的信息：用户的诉求、已经确认的事实和结论、还没解决的问题。
            不要写历史里没有的内容，不要保留寒暄，总长度不超过 {maxChars} 个字。
            只输出摘要本身。

            <messages>
            {messages}
            </messages>
            """;

    public HarnessMemoryOptions {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        if (compactionTriggerMessages < 4 || compactionKeepMessages < 1
                || compactionKeepMessages >= compactionTriggerMessages) {
            throw new IllegalArgumentException(
                    "compaction must keep fewer messages than it is triggered at");
        }
        if (compactionTriggerTokens < 1000 || summaryMaxChars < 100) {
            throw new IllegalArgumentException(
                    "compaction trigger tokens must be >= 1000 and summary max chars >= 100");
        }
    }

    public static HarnessMemoryOptions defaults() {
        return new HarnessMemoryOptions(
                Path.of(System.getProperty("java.io.tmpdir"), "agentforge", "agentscope-workspace"),
                50,
                12000,
                20,
                800);
    }

    public String summaryPrompt() {
        return SUMMARY_PROMPT.replace("{maxChars}", Integer.toString(summaryMaxChars));
    }
}

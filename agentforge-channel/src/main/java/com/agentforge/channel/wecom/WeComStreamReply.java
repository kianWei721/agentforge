package com.agentforge.channel.wecom;

/**
 * A stream reply. WeCom replaces the shown text with {@code content} on every reply, so this is
 * always the full answer so far, never a delta.
 */
public record WeComStreamReply(String streamId, String content, boolean finished) {}

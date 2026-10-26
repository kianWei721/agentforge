package com.agentforge.channel.handoff;

/** @param merged the session already had an open ticket; it took the message, no new notice */
public record HandoffOpened(String handoffId, boolean merged) {}

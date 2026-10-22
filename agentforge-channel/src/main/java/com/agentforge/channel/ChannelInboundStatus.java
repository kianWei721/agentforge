package com.agentforge.channel;

public enum ChannelInboundStatus {
    /** Accepted and waiting for, or running, its agent turn. */
    RECEIVED,
    DONE,
    FAILED,
    /** Refused before any agent ran: unknown user, unbound group, overload, ... */
    REJECTED
}

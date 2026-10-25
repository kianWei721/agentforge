package com.agentforge.channel;

public enum ChannelInboundStatus {
    /** Accepted and waiting for, or running, its agent turn. */
    RECEIVED,
    DONE,
    FAILED,
    /** Refused before any agent ran: unknown user, unbound group, overload, out of scope, ... */
    REJECTED,
    /** A person has to handle it; the user was told so and no agent ran. */
    HANDOFF
}

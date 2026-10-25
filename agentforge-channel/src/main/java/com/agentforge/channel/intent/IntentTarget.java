package com.agentforge.channel.intent;

/** Where a message goes once its intent is known. */
public enum IntentTarget {
    /** The channel agent answers it. */
    ANSWER,
    /** A person has to handle it: a fixed reply, recorded as HANDOFF, no agent turn. */
    HANDOFF,
    /** Out of scope: a fixed reply, no agent turn. */
    REFUSE
}

package com.agentforge.channel.handoff;

/** How staff closed a hand-off. FALSE_TRIGGER counts messages that never needed a person. */
public enum HandoffResolution {
    HANDLED,
    NO_ACTION,
    FALSE_TRIGGER
}

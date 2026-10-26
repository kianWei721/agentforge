package com.agentforge.channel.handoff;

/** Someone else got there first: the message says who and where the ticket is now. */
public class ChannelHandoffInvalidStateException extends RuntimeException {

    public ChannelHandoffInvalidStateException(ChannelHandoff current, String action) {
        super("Cannot " + action + " hand-off " + current.id() + ": it is " + current.status()
                + (current.claimedBy() == null ? "" : ", claimed by " + current.claimedBy()));
    }
}

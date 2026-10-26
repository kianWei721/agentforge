package com.agentforge.channel.handoff;

public class ChannelHandoffNotFoundException extends RuntimeException {

    public ChannelHandoffNotFoundException(String id) {
        super("Hand-off not found: " + id);
    }
}

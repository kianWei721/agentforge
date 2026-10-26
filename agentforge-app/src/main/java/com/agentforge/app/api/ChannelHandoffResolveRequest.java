package com.agentforge.app.api;

/** @param resolution HANDLED, NO_ACTION or FALSE_TRIGGER */
public record ChannelHandoffResolveRequest(String resolution, String note) {}

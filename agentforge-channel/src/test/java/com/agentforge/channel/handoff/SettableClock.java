package com.agentforge.channel.handoff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class SettableClock extends Clock {
    private Instant now;

    SettableClock(Instant now) {
        this.now = now;
    }

    void set(Instant now) {
        this.now = now;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}

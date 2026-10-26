package com.agentforge.channel.handoff;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.ChannelMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelHandoffServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-26T06:00:00Z");

    private final SettableClock clock = new SettableClock(T0.plusSeconds(90));
    private final List<String> recorded = new ArrayList<>();
    private final Single repository = new Single();
    private final ChannelHandoffService service =
            new ChannelHandoffService(
                    repository,
                    clock,
                    new ChannelMetrics() {
                        @Override
                        public void handoffClaimWait(Duration wait) {
                            recorded.add("claim " + wait.toSeconds());
                        }

                        @Override
                        public void handoffResolved(String resolution, Duration open) {
                            recorded.add(resolution + " " + open.toSeconds());
                        }
                    });

    @Test
    void claimingAndResolvingAreTimedFromWhenTheTicketOpened() {
        assertEquals(HandoffStatus.CLAIMED, service.claim("t1", "kefu-1").status());
        clock.set(T0.plusSeconds(600));
        assertEquals(HandoffStatus.RESOLVED, service.resolve("t1", "kefu-1", HandoffResolution.HANDLED, " 已回电 ").status());

        assertEquals(List.of("claim 90", "HANDLED 600"), recorded);
        assertEquals("已回电", repository.note);
    }

    @Test
    void losingARaceSaysWhoHasIt() {
        repository.state = HandoffStatus.CLAIMED;
        repository.claimedBy = "kefu-2";

        ChannelHandoffInvalidStateException conflict =
                assertThrows(ChannelHandoffInvalidStateException.class, () -> service.claim("t1", "kefu-1"));
        assertTrue(conflict.getMessage().contains("kefu-2"), conflict.getMessage());
        assertThrows(
                ChannelHandoffInvalidStateException.class,
                () -> service.resolve("t1", "kefu-1", HandoffResolution.NO_ACTION, null));
        assertTrue(recorded.isEmpty());
    }

    @Test
    void anUnknownTicketIsNotFound() {
        assertThrows(ChannelHandoffNotFoundException.class, () -> service.claim("nope", "kefu-1"));
        assertThrows(ChannelHandoffNotFoundException.class, () -> service.get("nope"));
    }

    @Test
    void requestsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> service.list(HandoffStatus.PENDING, null, 101));
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve("t1", "kefu-1", HandoffResolution.HANDLED, "长".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("t1", "kefu-1", null, null));
    }

    /** One ticket, "t1", with the repository's conditional transitions. */
    private static final class Single extends HandoffNotificationDispatcherTest.Tickets {
        HandoffStatus state = HandoffStatus.PENDING;
        String claimedBy;
        String note;

        @Override
        public Optional<ChannelHandoff> find(String id) {
            if (!"t1".equals(id)) {
                return Optional.empty();
            }
            return Optional.of(
                    new ChannelHandoff(
                            "t1", "wecom", "bot-1", "GROUP", "chat", "s", "wecom-group:acme", "C001", "zhangsan",
                            "in-1", "in-1", 1, "handoff", "RULE", null, null, state, claimedBy, null, null, null,
                            null, note, NotifyStatus.SENT, 0, null, null, null, T0, T0, T0));
        }

        @Override
        public boolean claim(String id, String staffUserId, Instant at) {
            if (!"t1".equals(id) || state != HandoffStatus.PENDING) {
                return false;
            }
            state = HandoffStatus.CLAIMED;
            claimedBy = staffUserId;
            return true;
        }

        @Override
        public boolean resolve(String id, String staffUserId, HandoffResolution resolution, String note, Instant at) {
            boolean mine = state == HandoffStatus.PENDING
                    || (state == HandoffStatus.CLAIMED && staffUserId.equals(claimedBy));
            if (!"t1".equals(id) || !mine) {
                return false;
            }
            state = HandoffStatus.RESOLVED;
            this.note = note;
            return true;
        }
    }
}

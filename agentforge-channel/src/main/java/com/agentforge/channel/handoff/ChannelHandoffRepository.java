package com.agentforge.channel.handoff;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChannelHandoffRepository {

    /** Opens the session's ticket, or merges into the one open. */
    HandoffOpened open(HandoffOpening opening);

    Optional<ChannelHandoff> find(String id);

    /** Oldest first: the longest waiting is handled first. */
    List<ChannelHandoff> list(HandoffStatus status, Instant createdAfter, int limit);

    /** @return false unless it was PENDING */
    boolean claim(String id, String staffUserId, Instant at);

    /** @return false unless it was PENDING, or CLAIMED by this staff member */
    boolean resolve(String id, String staffUserId, HandoffResolution resolution, String note, Instant at);

    /** Notices still to send whose retry time has come, oldest first. */
    List<ChannelHandoff> dueForNotification(Instant now, int limit);

    void markNotified(List<String> ids, Instant at);

    void markNotifyRetry(String id, int attempts, Instant nextAt, String error, Instant at);

    void markNotifyFailed(String id, int attempts, String error, Instant at);

    /** PENDING and CLAIMED counts. */
    Map<HandoffStatus, Long> countOpen();
}

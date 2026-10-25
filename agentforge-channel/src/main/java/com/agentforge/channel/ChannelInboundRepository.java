package com.agentforge.channel;

import java.time.Instant;
import java.util.Optional;

public interface ChannelInboundRepository {

    /** @return false when the message is already recorded, i.e. this is a redelivery */
    boolean insertIfAbsent(ChannelInbound inbound);

    void complete(
            String id,
            ChannelInboundStatus status,
            String executionId,
            String errorCode,
            Instant updatedAt);

    /** Records the intent the classifier decided, once the turn has run it. */
    void recordIntent(String id, ChannelIntent intent);

    Optional<ChannelInbound> find(String channel, String botId, String msgId);
}

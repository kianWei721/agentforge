package com.agentforge.channel;

import com.agentforge.channel.handoff.HandoffOpened;
import com.agentforge.channel.handoff.HandoffOpening;
import java.time.Instant;
import java.util.Optional;

public interface ChannelInboundRepository {

    /** @return false when the message is already recorded, i.e. this is a redelivery */
    boolean insertIfAbsent(ChannelInbound inbound);

    /**
     * Records a message a phrase handed to a person and opens (or merges into) the session's
     * ticket, in one transaction: there is never a HANDOFF row without its ticket.
     *
     * @return empty when the message is a redelivery: no second ticket, no second count
     */
    Optional<HandoffOpened> insertHandoff(ChannelInbound inbound, HandoffOpening handoff);

    void complete(
            String id,
            ChannelInboundStatus status,
            String executionId,
            String errorCode,
            Instant updatedAt);

    /** The classifier handed the message to a person: completes it as HANDOFF with its ticket, together. */
    HandoffOpened completeAsHandoff(String id, HandoffOpening handoff, Instant updatedAt);

    /** Records the intent the classifier decided, once the turn has run it. */
    void recordIntent(String id, ChannelIntent intent);

    Optional<ChannelInbound> find(String channel, String botId, String msgId);
}

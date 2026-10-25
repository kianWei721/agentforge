package com.agentforge.channel;

import com.agentforge.channel.intent.IntentDecision;

/**
 * How an inbound message was routed, as recorded with it.
 *
 * @param source RULE, MODEL, DEFAULT or a FALLBACK_... reason
 * @param confidence the classifier's, when it decided; otherwise null
 * @param gatewayRequestId the classifier call in the model gateway's ledger, when there was one
 */
public record ChannelIntent(String intent, String source, Double confidence, String gatewayRequestId) {

    public static ChannelIntent of(IntentDecision decision) {
        return new ChannelIntent(
                decision.intent(),
                decision.source().name(),
                decision.confidence(),
                decision.gatewayRequestId());
    }
}

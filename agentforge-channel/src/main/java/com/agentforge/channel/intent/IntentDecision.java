package com.agentforge.channel.intent;

/**
 * The intent a message was routed by.
 *
 * @param confidence what the classifier reported for this intent; null when the classifier did not
 *     decide it, including every fallback, which never keeps the confidence of the intent it overruled
 * @param gatewayRequestId the classifier call in the model gateway's ledger; null without one
 */
public record IntentDecision(
        String intent,
        IntentTarget target,
        IntentSource source,
        Double confidence,
        String gatewayRequestId) {}

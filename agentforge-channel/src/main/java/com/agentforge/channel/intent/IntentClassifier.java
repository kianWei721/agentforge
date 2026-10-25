package com.agentforge.channel.intent;

import java.time.Duration;
import java.util.List;

/** Chooses one of the configured intents for a message; in production, a model gateway call. */
@FunctionalInterface
public interface IntentClassifier {

    /**
     * @param correlationId ties the call to the message in the gateway's ledger
     * @throws InvalidClassificationException when the answer names no configured intent
     * @throws RuntimeException when the call fails or times out
     */
    Classification classify(
            String text, List<IntentDefinition> intents, String correlationId, Duration timeout);

    /** @param confidence 0 to 1, as the model reports it; poorly calibrated, so only a threshold */
    record Classification(String intent, double confidence, String gatewayRequestId) {}

    /** The model answered, but not with one of the intents it was given. */
    final class InvalidClassificationException extends RuntimeException {
        private final String gatewayRequestId;

        public InvalidClassificationException(String message, String gatewayRequestId) {
            super(message);
            this.gatewayRequestId = gatewayRequestId;
        }

        public String gatewayRequestId() {
            return gatewayRequestId;
        }
    }
}

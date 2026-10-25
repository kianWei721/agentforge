package com.agentforge.channel.intent;

/** How an intent was decided; recorded with the message and counted in metrics. */
public enum IntentSource {
    /** A configured phrase matched when the message arrived. */
    RULE,
    /** The classifier chose it with enough confidence. */
    MODEL,
    /** No phrase matched and the model stage is off. */
    DEFAULT,
    /** The classifier chose another intent below the confidence threshold. */
    FALLBACK_LOW_CONFIDENCE,
    /** The classifier call failed or timed out. */
    FALLBACK_MODEL_ERROR,
    /** The classifier answered with something other than a configured intent. */
    FALLBACK_INVALID_OUTPUT,
    /** Too little of the reply window was left to classify and still answer (under a second). */
    FALLBACK_NO_TIME
}

package com.agentforge.runtime;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * The model compaction summarizes with, held to the summary's length.
 *
 * <p>The summary prompt asks for at most {@code maxChars} characters, but a model may not keep to
 * it, and the conversation it summarizes may tell it otherwise. The summary stands in for the older
 * messages in every later prompt of the session, so the bound is enforced on the output: text past
 * it is dropped and the call ends. The compactor builds the summary from the text blocks only.
 */
final class SummaryLengthLimitedModel implements Model {

    private final Model delegate;
    private final int maxChars;

    SummaryLengthLimitedModel(Model delegate, int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(
                () -> {
                    int[] used = {0};
                    // Once the summary is full the rest is not wanted: stop the call.
                    return delegate.stream(messages, tools, options)
                            .takeWhile(response -> used[0] < maxChars)
                            .map(response -> cut(response, used));
                });
    }

    private ChatResponse cut(ChatResponse response, int[] used) {
        if (response.getContent() == null) {
            return response;
        }
        List<ContentBlock> kept = new ArrayList<>();
        boolean changed = false;
        for (ContentBlock block : response.getContent()) {
            if (!(block instanceof TextBlock text) || text.getText() == null) {
                kept.add(block);
                continue;
            }
            String value = text.getText();
            int room = maxChars - used[0];
            if (value.length() <= room) {
                used[0] += value.length();
                kept.add(block);
                continue;
            }
            changed = true;
            if (room > 0) {
                // Never split a surrogate pair.
                int end = Character.isHighSurrogate(value.charAt(room - 1)) ? room - 1 : room;
                kept.add(TextBlock.builder().text(value.substring(0, end)).build());
            }
            used[0] = maxChars;
        }
        return changed
                ? new ChatResponse(
                        response.getId(),
                        kept,
                        response.getUsage(),
                        response.getMetadata(),
                        response.getFinishReason())
                : response;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}

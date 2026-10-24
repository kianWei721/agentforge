package com.agentforge.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SummaryLengthLimitedModelTest {

    @Test
    void aSummaryThatIgnoresTheLimitIsCutAndTheCallStopped() {
        ChunkedModel model = new ChunkedModel("摘要内容".repeat(25), 50);

        String summary = summarize(new SummaryLengthLimitedModel(model, 800));

        assertEquals(800, summary.length());
        assertTrue(model.cancelled.get(), "the rest of the answer is not read");
        assertTrue(model.emitted.get() < 50);
    }

    @Test
    void aSummaryWithinTheLimitIsUnchanged() {
        String answer = "群里问了发票抬头和报销流程。";
        assertEquals(answer, summarize(new SummaryLengthLimitedModel(new ChunkedModel(answer, 1), 800)));
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        // "😀" is two chars; a limit of 4 falls inside the second one.
        String summary = summarize(new SummaryLengthLimitedModel(new ChunkedModel("abc😀def", 1), 4));

        assertEquals("abc", summary);
    }

    /** What the compactor does with the answer: concatenate the text blocks. */
    private static String summarize(Model model) {
        return model.stream(List.of(), List.of(), GenerateOptions.builder().build())
                .reduce(
                        new StringBuilder(),
                        (text, response) -> {
                            for (ContentBlock block : response.getContent()) {
                                if (block instanceof TextBlock textBlock) {
                                    text.append(textBlock.getText());
                                }
                            }
                            return text;
                        })
                .block()
                .toString();
    }

    /** Streams the same chunk {@code times} times. */
    private static final class ChunkedModel implements Model {
        private final String chunk;
        private final int times;
        final AtomicInteger emitted = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();

        ChunkedModel(String chunk, int times) {
            this.chunk = chunk;
            this.times = times;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.range(0, times)
                    .map(
                            i ->
                                    ChatResponse.builder()
                                            .id("r-" + i)
                                            .content(List.<ContentBlock>of(TextBlock.builder().text(chunk).build()))
                                            .build())
                    .doOnNext(response -> emitted.incrementAndGet())
                    .doOnCancel(() -> cancelled.set(true));
        }

        @Override
        public String getModelName() {
            return "chunked";
        }
    }
}

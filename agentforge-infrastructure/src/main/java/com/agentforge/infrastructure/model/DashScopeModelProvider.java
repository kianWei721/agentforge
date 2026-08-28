package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.model.ModelChunk;
import com.agentforge.model.ModelProvider;
import com.agentforge.model.ModelRequest;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.List;

@Component
public class DashScopeModelProvider implements ModelProvider {
    private static final String PROVIDER = "dashscope";
    private final ModelProviderProperties properties;
    private final DashScopeModelFactory modelFactory;
    private volatile DashScopeChatModel chatModel;
    public DashScopeModelProvider(ModelProviderProperties properties, DashScopeModelFactory modelFactory) { this.properties = properties; this.modelFactory = modelFactory; }
    @Override public String provider() { return PROVIDER; }
    @Override public String model() { return properties.getDashscope().getModel(); }
    @Override public Flux<ModelChunk> stream(ModelRequest request) {
        return Flux.defer(() -> {
            DashScopeChatModel chatModel = getOrCreateModel();
            Flux<ModelChunk> content = chatModel.stream(List.of(new UserMessage(request.message())), List.of(), GenerateOptions.builder().build())
                    .concatMap(response -> Flux.fromIterable(response.getContent()))
                    .filter(TextBlock.class::isInstance).cast(TextBlock.class).map(TextBlock::getText)
                    .filter(text -> text != null && !text.isEmpty())
                    .map(text -> new ModelChunk(text, false, provider(), model()));
            return content.concatWithValues(new ModelChunk("", true, provider(), model()));
        });
    }
    private DashScopeChatModel getOrCreateModel() {
        DashScopeChatModel current = chatModel;
        if (current != null) return current;
        synchronized (this) { if (chatModel == null) chatModel = modelFactory.createStreamingModel(); return chatModel; }
    }
}

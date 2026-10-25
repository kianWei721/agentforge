package com.agentforge.infrastructure.channel;

import com.agentforge.infrastructure.config.WeComChannelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * The labelled intent set and the intents as shipped. Labels were written before any model ran;
 * {@code rule} is what the shipped phrases alone decide, null for nothing.
 */
final class IntentDataset {

    record Item(String id, String split, String category, String text, String expected, String rule) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private IntentDataset() {}

    static List<Item> load() throws IOException {
        List<Item> items = new ArrayList<>();
        try (InputStream in = IntentDataset.class.getResourceAsStream("/intent/intent-eval.jsonl")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                items.add(
                        new Item(
                                node.path("id").asText(),
                                node.path("split").asText(),
                                node.path("category").asText(),
                                node.path("text").asText(),
                                node.path("expected").asText(),
                                node.path("rule").isNull() ? null : node.path("rule").asText()));
            }
        }
        return items;
    }

    /** {@code agentforge.channel.wecom.intent} from the application's own application.yml. */
    static WeComChannelProperties.Intent shipped() throws IOException {
        // Tests run in the module directory; the application module is its sibling.
        Path yml = Path.of("..", "agentforge-app", "src", "main", "resources", "application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        return new Binder(
                        ConfigurationPropertySources.from(sources),
                        new PropertySourcesPlaceholdersResolver(sources))
                .bind("agentforge.channel.wecom.intent", WeComChannelProperties.Intent.class)
                .get();
    }
}

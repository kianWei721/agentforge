package com.agentforge.infrastructure.gateway;

import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A model gateway stand-in: records each request and answers with whatever it was told to. */
public final class FakeGateway implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = completion("{}");
    private volatile Map<String, String> headers = Map.of();
    private volatile long delayMillis;

    private FakeGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.add(
                            new Recorded(
                                    exchange.getRequestHeaders(),
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    if (delayMillis > 0) {
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            exchange.close();
                            return;
                        }
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    headers.forEach(exchange.getResponseHeaders()::set);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    public static FakeGateway start() throws IOException {
        return new FakeGateway();
    }

    public FakeGateway respond(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers;
        return this;
    }

    public FakeGateway delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    public ModelGatewayProperties properties() {
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.setApiKey("gk-test");
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    public ModelGatewayClient client() {
        return new ModelGatewayClient(HttpClient.newHttpClient(), JSON, properties());
    }

    public List<Recorded> requests() {
        return requests;
    }

    public Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    /** An OpenAI-format response whose first choice says {@code content}. */
    public static String completion(String content) {
        try {
            return JSON.writeValueAsString(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The gateway's error envelope. */
    public static String error(String code) {
        return "{\"error\":{\"type\":\"gateway_error\",\"code\":\""
                + code
                + "\",\"message\":\"test failure\"}}";
    }

    @Override
    public void close() {
        executor.shutdownNow();
        server.stop(0);
    }

    public record Recorded(Headers headers, String body) {
        public String header(String name) {
            return headers.getFirst(name);
        }
    }
}

package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsDeepSeekCompatibleJsonRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        AiClient client = new AiClient(properties(), objectMapper);

        assertEquals("ok", client.chat("system", "user"));
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", body.path("model").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertFalse(body.path("messages").isEmpty());
    }

    @Test
    void mapsProviderRateLimitToStableErrorCode() throws Exception {
        startServer(exchange -> respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> new AiClient(properties(), objectMapper).chat("system", "user")
        );

        assertEquals("AI_RATE_LIMITED", exception.getCode());
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("deepseek-v4-flash");
        properties.setTimeoutSeconds(5);
        properties.setMockEnabled(false);
        return properties;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaModelGatewayTest {
    @Test
    void requestsJsonModeAndExtractsUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"content\":\"ok\"},\"prompt_eval_count\":12,\"eval_count\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new EvaluationPlan.ModelSpec("m", "ollama", "test:1", "test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 100, 16_384,
                    0.1, 10, 0, 0, 0.0, 0.0);
            var result = new OllamaModelGateway(model, new ObjectMapper())
                    .call(new ModelPrompt("r", "system", "user", true));
            assertEquals("ok", result.text());
            assertEquals(15, result.usage().totalTokens());
            assertEquals(0.0, result.usage().estimatedCostUsd());
            assertTrue(body.get().contains("\"format\":\"json\""));
            assertTrue(body.get().contains("\"think\":false"));
            assertTrue(body.get().contains("\"num_ctx\":16384"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsBlankFullBudgetOutputAsNonRetryableExhaustion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] response = ("{\"message\":{\"content\":\"\"},"
                    + "\"prompt_eval_count\":12,\"eval_count\":100}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new EvaluationPlan.ModelSpec("m", "ollama", "test:1", "test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 100, 16_384,
                    0.1, 10, 3, 0, 0.0, 0.0);

            ModelGatewayException failure = assertThrows(ModelGatewayException.class,
                    () -> new OllamaModelGateway(model, new ObjectMapper())
                            .call(new ModelPrompt("r", "system", "user", true)));

            assertEquals("OUTPUT_EXHAUSTED", failure.category());
            assertEquals(false, failure.retryable());
            assertEquals(112, failure.usage().totalTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesThatConfiguredModelIsInstalledWithoutGenerating() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] response = "{\"models\":[{\"name\":\"test:1\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new EvaluationPlan.ModelSpec("m", "ollama", "test:1", "test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 100, null,
                    0.1, 10, 0, 0, 0.0, 0.0);
            new OllamaModelGateway(model, new ObjectMapper()).validateAvailable();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnUnavailableConfiguredModelDuringPreflight() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] response = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new EvaluationPlan.ModelSpec("m", "ollama", "missing:1", "test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 100, null,
                    0.1, 10, 0, 0, 0.0, 0.0);
            assertThrows(ModelGatewayException.class,
                    () -> new OllamaModelGateway(model, new ObjectMapper()).validateAvailable());
        } finally {
            server.stop(0);
        }
    }
}

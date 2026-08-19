package com.debopam.llmcouncil.evaluation.council;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCouncilApiGatewayTest {
    @Test
    void createsAndRunsSessionThroughPublicApi() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> createBody = new AtomicReference<>();
        server.createContext("/api/council/sessions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/run")) respond(exchange, 200, "{\"status\":\"COMPLETED\",\"answer\":\"four\"}");
            else {
                createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 201, "{\"sessionId\":\"session-1\"}");
            }
        });
        server.start();
        try {
            EvaluationPlan original = TestFixtures.plan(Path.of("out"));
            EvaluationPlan plan = new EvaluationPlan(original.version(), original.id(), original.description(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), original.dataset(), original.rubric(),
                    original.outputDirectory(), original.seed(), original.repetitions(), original.execution(),
                    original.models(), original.variants(), original.comparisons(), original.judges());
            EvaluationPlan.VariantSpec variant = plan.variants().get(1);
            var result = new HttpCouncilApiGateway(new ObjectMapper()).run(plan, variant, TestFixtures.evalCase());
            assertEquals("four", result.path("answer").asText());
            assertTrue(createBody.get().contains("\"profileId\":\"local\""));
            assertTrue(createBody.get().contains("\"depthMode\":\"BALANCED\""));
        } finally {
            server.stop(0);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct non-streaming Ollama /api/chat adapter with token usage extraction. */
public class OllamaModelGateway implements ModelGateway {
    private final EvaluationPlan.ModelSpec model;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final URI tagsEndpoint;

    public OllamaModelGateway(EvaluationPlan.ModelSpec model, ObjectMapper mapper) {
        this(model, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    OllamaModelGateway(EvaluationPlan.ModelSpec model, ObjectMapper mapper, HttpClient client) {
        this.model = model;
        this.mapper = mapper;
        this.client = client;
        String base = model.baseUrl() == null || model.baseUrl().isBlank()
                ? "http://127.0.0.1:11434" : model.baseUrl().strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = URI.create(base.endsWith("/api") ? base + "/chat" : base + "/api/chat");
        this.tagsEndpoint = URI.create(base.endsWith("/api") ? base + "/tags" : base + "/api/tags");
    }

    /** Read-only Ollama preflight used by the plan command. */
    public void validateAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tagsEndpoint)
                    .timeout(Duration.ofSeconds(Math.min(model.timeoutSeconds(), 30)))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelGatewayException("PROVIDER_UNAVAILABLE",
                        "Ollama model-list HTTP " + response.statusCode() + ": " + truncate(response.body()),
                        0, false);
            }
            JsonNode models = mapper.readTree(response.body()).path("models");
            for (JsonNode candidate : models) {
                String name = candidate.path("name").asText(candidate.path("model").asText());
                if (model.providerModelId().equals(name)) return;
            }
            throw new ModelGatewayException("MODEL_NOT_FOUND",
                    "Ollama model '" + model.providerModelId() + "' is not installed at " + tagsEndpoint,
                    0, false);
        } catch (ModelGatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ModelGatewayException("PROVIDER_UNAVAILABLE",
                    "Ollama preflight failed: " + root(ex).getMessage(), ex, 0, false);
        }
    }

    @Override
    public ModelResponse call(ModelPrompt prompt) {
        Instant started = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(model.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload(prompt))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean retryable = response.statusCode() == 408 || response.statusCode() == 429
                        || response.statusCode() >= 500;
                String category = response.statusCode() == 404 ? "MODEL_NOT_FOUND"
                        : retryable ? "PROVIDER_UNAVAILABLE" : "MODEL_CALL_FAILED";
                throw new ModelGatewayException(category,
                        "Ollama HTTP " + response.statusCode() + ": " + truncate(response.body()), 1, retryable);
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                throw new ModelGatewayException("MODEL_CALL_FAILED", root.get("error").asText(), 1, false);
            }
            String text = root.path("message").path("content").asText(root.path("response").asText(""));
            Long promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").longValue() : null;
            Long completionTokens = root.has("eval_count") ? root.get("eval_count").longValue() : null;
            return new ModelResponse(text, Duration.between(started, Instant.now()).toMillis(),
                    usage(promptTokens, completionTokens));
        } catch (ModelGatewayException ex) {
            throw ex;
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new ModelGatewayException("MODEL_TIMEOUT", "Ollama call timed out", ex, 1, true);
        } catch (Exception ex) {
            boolean retryable = root(ex) instanceof ConnectException || ex instanceof java.io.IOException;
            throw new ModelGatewayException(retryable ? "PROVIDER_UNAVAILABLE" : "MODEL_CALL_FAILED",
                    "Ollama call failed: " + root(ex).getMessage(), ex, 1, retryable);
        }
    }

    private Map<String, Object> payload(ModelPrompt prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.providerModelId());
        payload.put("stream", false);
        if (prompt.jsonMode()) payload.put("format", "json");
        payload.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system()),
                Map.of("role", "user", "content", prompt.user())));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", model.temperature());
        options.put("num_predict", model.maxOutputTokens());
        if (model.contextWindowTokens() != null) {
            options.put("num_ctx", model.contextWindowTokens());
        }
        payload.put("options", options);
        return payload;
    }

    private UsageMetrics usage(Long prompt, Long completion) {
        long input = prompt == null ? 0 : prompt;
        long output = completion == null ? 0 : completion;
        boolean inputPriced = model.costPer1kInputTokens() != null;
        boolean outputPriced = model.costPer1kOutputTokens() != null;
        boolean priced = inputPriced || outputPriced;
        Double cost = priced ? round(input / 1000.0 * value(model.costPer1kInputTokens())
                + output / 1000.0 * value(model.costPer1kOutputTokens())) : null;
        boolean partiallyPriced = inputPriced != outputPriced
                || (prompt == null && inputPriced)
                || (completion == null && outputPriced);
        return new UsageMetrics(1, input, output, cost, prompt == null || completion == null, partiallyPriced);
    }

    private double value(Double value) { return value == null ? 0 : value; }
    private double round(double value) { return Math.round(value * 1_000_000.0) / 1_000_000.0; }
    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(1000, value.length())); }
    private Throwable root(Throwable value) { while (value.getCause() != null) value = value.getCause(); return value; }
}

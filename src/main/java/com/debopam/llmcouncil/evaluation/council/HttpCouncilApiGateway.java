package com.debopam.llmcouncil.evaluation.council;

import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Black-box client for the stable llm-council REST surface. */
@Component
public class HttpCouncilApiGateway implements CouncilApiGateway {
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public HttpCouncilApiGateway(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    HttpCouncilApiGateway(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public JsonNode catalog(EvaluationPlan plan) {
        return send(plan, "GET", "/api/council/catalog?include=profiles,policies,models,protocols,providers", null);
    }

    @Override
    public JsonNode health(EvaluationPlan plan, EvaluationPlan.VariantSpec variant) {
        return send(plan, "GET", "/api/council/profiles/" + encode(variant.profileId())
                + "/health?depthMode=" + encode(variant.depthMode()), null);
    }

    @Override
    public JsonNode run(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                        EvaluationDataset.EvaluationCase evalCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", evalCase.question());
        if (evalCase.context() != null && !evalCase.context().isBlank()) body.put("context", evalCase.context());
        body.put("depthMode", variant.depthMode());
        body.put("profileId", variant.profileId());
        JsonNode session = send(plan, "POST", "/api/council/sessions", body);
        String sessionId = session.path("sessionId").asText();
        if (sessionId.isBlank()) throw new CouncilApiException(0, "Council create-session response had no sessionId");
        return send(plan, "POST", "/api/council/sessions/" + encode(sessionId) + "/run", Map.of());
    }

    private JsonNode send(EvaluationPlan plan, String method, String path, Object body) {
        try {
            String base = plan.councilBaseUrl().strip();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(plan.execution().councilRequestTimeoutSeconds()))
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CouncilApiException(response.statusCode(),
                        "Council API " + method + " " + path + " returned " + response.statusCode()
                                + ": " + truncate(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (CouncilApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CouncilApiException("Council API call failed for " + path + ": " + safe(ex), ex);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(1000, value.length())); }
    private String safe(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}

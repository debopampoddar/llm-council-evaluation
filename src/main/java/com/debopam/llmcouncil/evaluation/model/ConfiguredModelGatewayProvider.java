package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Resolves plan model definitions to local or Spring AI provider clients. */
@Component
public class ConfiguredModelGatewayProvider implements ModelGatewayProvider {
    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "unused-development-placeholder", "placeholder", "changeme", "none", "test");

    private final ObjectMapper mapper;
    private final OpenAiChatModel openAi;
    private final AnthropicChatModel anthropic;
    private final VertexAiGeminiChatModel gemini;
    private final String openAiKey;
    private final String anthropicKey;
    private final String geminiProject;

    public ConfiguredModelGatewayProvider(
            ObjectMapper mapper,
            ObjectProvider<OpenAiChatModel> openAi,
            ObjectProvider<AnthropicChatModel> anthropic,
            ObjectProvider<VertexAiGeminiChatModel> gemini,
            @Value("${spring.ai.openai.api-key:}") String openAiKey,
            @Value("${spring.ai.anthropic.api-key:}") String anthropicKey,
            @Value("${spring.ai.vertex.ai.gemini.project-id:}") String geminiProject) {
        this.mapper = mapper;
        this.openAi = openAi.getIfAvailable();
        this.anthropic = anthropic.getIfAvailable();
        this.gemini = gemini.getIfAvailable();
        this.openAiKey = openAiKey;
        this.anthropicKey = anthropicKey;
        this.geminiProject = geminiProject;
    }

    @Override
    public ModelGateway gateway(EvaluationPlan.ModelSpec model) {
        ModelGateway raw = switch (model.provider().toLowerCase(Locale.ROOT)) {
            case "ollama" -> new OllamaModelGateway(model, mapper);
            case "openai" -> new SpringAiModelGateway(model,
                    ChatClient.create(require(openAi, openAiKey, "SPRING_AI_OPENAI_API_KEY", "OpenAI")), true);
            case "anthropic" -> new SpringAiModelGateway(model,
                    ChatClient.create(require(anthropic, anthropicKey, "SPRING_AI_ANTHROPIC_API_KEY", "Anthropic")), false);
            case "gemini" -> new SpringAiModelGateway(model,
                    ChatClient.create(require(gemini, geminiProject, "GOOGLE_CLOUD_PROJECT and ADC", "Gemini")), true);
            case "mock" -> new MockModelGateway(model.id());
            default -> throw new ModelGatewayException("CONFIGURATION_ERROR",
                    "Unsupported provider: " + model.provider(), 0, false);
        };
        int retries = model.retryMaxAttempts() == null ? 1 : model.retryMaxAttempts();
        long delay = model.retryBaseDelayMs() == null ? 500L : model.retryBaseDelayMs();
        return retries == 0 ? raw : new RetryingModelGateway(raw, retries, delay);
    }

    @Override
    public void validate(EvaluationPlan.ModelSpec model) {
        if ("ollama".equalsIgnoreCase(model.provider())) {
            new OllamaModelGateway(model, mapper).validateAvailable();
            return;
        }
        gateway(model); // Validates provider bean and credentials without generating text.
    }

    private <T> T require(T bean, String credential, String guidance, String provider) {
        String normalized = credential == null ? "" : credential.trim().toLowerCase(Locale.ROOT);
        if (bean == null || PLACEHOLDERS.contains(normalized)) {
            throw new ModelGatewayException("CONFIGURATION_ERROR",
                    provider + " is not configured. Set " + guidance + ".", 0, false);
        }
        return bean;
    }
}

package com.debopam.llmcouncil.evaluation.config;

import com.google.cloud.vertexai.VertexAI;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/** Creates Vertex AI only when a project is configured, keeping local runs credential-free. */
@Configuration
@ConditionalOnExpression("!'${spring.ai.vertex.ai.gemini.project-id:}'.isBlank()")
public class GeminiConfiguration {

    @Bean
    VertexAI vertexAi(@Value("${spring.ai.vertex.ai.gemini.project-id}") String projectId,
                      @Value("${spring.ai.vertex.ai.gemini.location:us-central1}") String location) {
        return new VertexAI.Builder().setProjectId(projectId).setLocation(location).build();
    }

    @Bean
    VertexAiGeminiChatModel vertexAiGeminiChatModel(
            VertexAI vertexAi,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        var builder = VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAi)
                .defaultOptions(VertexAiGeminiChatOptions.builder()
                        .model("gemini-2.5-flash").temperature(0.2).build())
                .retryTemplate(RetryTemplate.defaultInstance());
        ToolCallingManager manager = toolCallingManager.getIfAvailable();
        if (manager != null) builder.toolCallingManager(manager);
        ObservationRegistry registry = observationRegistry.getIfAvailable();
        if (registry != null) builder.observationRegistry(registry);
        return builder.build();
    }
}

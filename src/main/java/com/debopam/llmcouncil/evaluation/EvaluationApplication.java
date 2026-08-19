package com.debopam.llmcouncil.evaluation;

import com.debopam.llmcouncil.evaluation.cli.EvaluationCli;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiMultiModalEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.gemini.VertexAiGeminiChatAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Entry point for the non-web evaluation CLI. */
@SpringBootApplication(exclude = {
        VertexAiGeminiChatAutoConfiguration.class,
        VertexAiTextEmbeddingAutoConfiguration.class,
        VertexAiMultiModalEmbeddingAutoConfiguration.class
})
public class EvaluationApplication {

    public static void main(String[] args) {
        int exitCode;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EvaluationApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            exitCode = context.getBean(EvaluationCli.class).execute(args);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}

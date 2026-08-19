package com.debopam.llmcouncil.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class EvaluationApplicationTest {
    @Test
    void bootsWithoutCloudCredentialsOrWebServer() {
        try (var context = new SpringApplicationBuilder(EvaluationApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF")
                .run()) {
            assertNotNull(context.getBean(com.debopam.llmcouncil.evaluation.cli.EvaluationCli.class));
        }
    }
}

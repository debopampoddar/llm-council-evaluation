package com.debopam.llmcouncil.evaluation.model;

@FunctionalInterface
public interface ModelGateway {
    ModelResponse call(ModelPrompt prompt);
}

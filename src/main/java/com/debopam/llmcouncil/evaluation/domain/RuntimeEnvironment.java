package com.debopam.llmcouncil.evaluation.domain;

/** Runtime execution settings that affect performance but not the experiment run id. */
public record RuntimeEnvironment(
        int candidateConcurrency,
        int judgmentConcurrency,
        String ollamaNumParallel
) {
    public RuntimeEnvironment {
        if (candidateConcurrency < 1) throw new IllegalArgumentException("candidateConcurrency must be positive");
        if (judgmentConcurrency < 1) throw new IllegalArgumentException("judgmentConcurrency must be positive");
        if (ollamaNumParallel != null && ollamaNumParallel.isBlank()) ollamaNumParallel = null;
    }

    /** Capture the effective harness settings for a newly created run. */
    public static RuntimeEnvironment capture(int judgmentConcurrency) {
        return new RuntimeEnvironment(1, judgmentConcurrency, System.getenv("OLLAMA_NUM_PARALLEL"));
    }
}

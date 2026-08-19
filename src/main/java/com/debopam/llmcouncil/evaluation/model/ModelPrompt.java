package com.debopam.llmcouncil.evaluation.model;

/** Provider-neutral prompt used for baselines, ensemble reduction, and judging. */
public record ModelPrompt(String requestId, String system, String user, boolean jsonMode) {}

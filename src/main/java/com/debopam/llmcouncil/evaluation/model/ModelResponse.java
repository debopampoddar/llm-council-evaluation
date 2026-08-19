package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;

/** Normalized provider response. */
public record ModelResponse(String text, long durationMs, UsageMetrics usage) {}

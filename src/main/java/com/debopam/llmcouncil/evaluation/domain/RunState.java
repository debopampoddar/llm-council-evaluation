package com.debopam.llmcouncil.evaluation.domain;

import java.time.Instant;

/** Mutable run lifecycle is separated from the immutable manifest. */
public record RunState(String status, Instant updatedAt, String detail) {}

package com.debopam.llmcouncil.evaluation.domain;

/** Outcome of one deterministic assertion against one answer. */
public record CheckResult(
        String unitId,
        int checkIndex,
        String type,
        Status status,
        String detail
) {
    public enum Status { PASS, FAIL, ERROR }
}

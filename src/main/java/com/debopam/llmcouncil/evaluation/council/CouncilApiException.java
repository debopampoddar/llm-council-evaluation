package com.debopam.llmcouncil.evaluation.council;

public class CouncilApiException extends RuntimeException {
    private final int statusCode;

    public CouncilApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public CouncilApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int statusCode() { return statusCode; }
}

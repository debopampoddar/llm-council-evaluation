package com.debopam.llmcouncil.evaluation.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hashing {
    private Hashing() {}

    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

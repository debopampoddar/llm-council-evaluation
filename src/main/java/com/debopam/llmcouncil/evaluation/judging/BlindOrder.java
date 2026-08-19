package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.config.Hashing;

/** Stable per-pair randomization; orientation two is always the exact mirror. */
public final class BlindOrder {
    private BlindOrder() {}

    public static boolean leftFirst(long seed, String pairId) {
        String hash = Hashing.sha256(seed + ":" + pairId);
        return Character.digit(hash.charAt(0), 16) % 2 == 0;
    }
}

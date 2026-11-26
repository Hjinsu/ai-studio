package com.aistudio.production.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.aistudio.production.contract.ProductionCostPort.PricedRequest;
import com.aistudio.production.contract.ProductionStage;

/**
 * Normalized SHA-256 of a priced effect request. Attempts, lease epochs and restarts never change it, so a
 * re-presented effect keeps the same key and hash; a changed price or scenario is a different request.
 */
final class EffectRequestHash {
    private EffectRequestHash() {}

    static String of(ProductionStage stage, String targetKey, int inputGeneration, PricedRequest request) {
        if (request == null || request.currency() == null || request.maximumAmount() == null) {
            throw new IllegalArgumentException("A priced request needs currency and maximum amount");
        }
        String scenario = request.fakeScenario() == null ? "" : request.fakeScenario();
        String canonical = String.join("\n", "v1", stage.name(), targetKey.length() + ":" + targetKey,
                Integer.toString(inputGeneration), request.currency(),
                request.maximumAmount().stripTrailingZeros().toPlainString(), scenario.length() + ":" + scenario);
        return sha256(canonical);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

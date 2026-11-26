package com.aistudio.production.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EffectKey {
    private EffectKey() {}

    public static String forInput(long requestId, ProductionStage stage, String targetKey, int generation) {
        if (requestId < 1 || stage == null || targetKey == null || targetKey.isBlank() || generation < 1) {
            throw new IllegalArgumentException("Invalid logical effect identity");
        }
        String identity = requestId + ":" + stage.name() + ":" + targetKey.length() + ":" + targetKey + ":" + generation;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

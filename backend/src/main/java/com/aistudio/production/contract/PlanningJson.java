package com.aistudio.production.contract;

import java.nio.charset.StandardCharsets;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionPlanning.ResultRef;
import tools.jackson.databind.json.JsonMapper;

public final class PlanningJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PlanningJson() {}

    public record Encoded(String json, String hash) {}

    public static Encoded encode(Object dto) {
        String canonical = CanonicalJson.write(JSON.readValue(JSON.writeValueAsString(dto), Object.class));
        return new Encoded(canonical, CanonicalJson.sha256Hex(canonical));
    }

    public static Encoded body(Object dto) {
        Encoded encoded = encode(dto);
        if (encoded.json().getBytes(StandardCharsets.UTF_8).length > ProductionPlanning.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Planning result exceeds 128 KiB");
        }
        return encoded;
    }

    public static boolean matches(String json, String hash) {
        return json != null && hash != null && hash.equals(CanonicalJson.sha256Hex(json));
    }

    public static boolean matches(ResultRef ref, long operationId, ProductionStage stage, String body) {
        return ref != null && ref.operationId() == operationId && ref.stage() == stage
                && matches(body, ref.bodyHash());
    }
}

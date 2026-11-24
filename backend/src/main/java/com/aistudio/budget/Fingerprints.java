package com.aistudio.budget;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * 멱등 키 내용 비교용 SHA-256 지문(ADR-026). 키 순서 고정 JSON, 금액은 trailing zero 제거 표기.
 */
final class Fingerprints {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Fingerprints() {
    }

    static Map<String, Object> fields() {
        return new LinkedHashMap<>();
    }

    static String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static String of(Map<String, Object> orderedFields) {
        String canonical = JSON.writeValueAsString(orderedFields);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

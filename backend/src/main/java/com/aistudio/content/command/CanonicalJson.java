package com.aistudio.content.command;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * 결정적 JSON 정규화와 SHA-256(revision hash v1, 명령 지문). 공백 없는 JSON, 객체 키는 유니코드 코드포인트 순,
 * null은 명시 출력, 숫자는 정수만 허용한다. 문자열은 값 그대로 쓴다(줄바꿈 통일은 입력 단계 책임).
 * 예산 Fingerprints는 변경하지 않는다(T03-B 범위 밖). 공용 shared 이동은 통합 담당자와 조율한다.
 */
public final class CanonicalJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** String.compareTo는 UTF-16 코드 단위 순이므로 보조 평면 문자를 위해 코드포인트로 비교한다. */
    static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private CanonicalJson() {
    }

    /** 정규화된 JSON 문자열. 허용 값: null, String, Boolean, 정수(Integer/Long/Short/Byte/BigInteger), Map, List. */
    public static String write(Object value) {
        return JSON.writeValueAsString(canonical(value));
    }

    public static String sha256(Object value) {
        return sha256Hex(write(value));
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 정수가 아닌 숫자 등 허용되지 않는 값이 있으면 IllegalArgumentException. */
    static Object canonical(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>(CODE_POINT_ORDER);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("object keys must be strings");
                }
                sorted.put(key, canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(canonical(item));
            }
            return copy;
        }
        throw new IllegalArgumentException("only strings, booleans, integers, null, objects and arrays are allowed");
    }
}

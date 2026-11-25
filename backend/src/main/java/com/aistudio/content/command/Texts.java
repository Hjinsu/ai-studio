package com.aistudio.content.command;

import com.aistudio.shared.api.ApiException;

/**
 * 텍스트 입력 경계(RV-B-05). 길이는 유니코드 코드포인트로 센다 — OpenAPI(JSON Schema) maxLength와 MariaDB utf8mb4
 * VARCHAR(n)의 문자 수와 같은 단위다(Java String.length()의 UTF-16 단위가 아님). 짝이 없는 surrogate는 utf8mb4로
 * 저장할 수 없으므로 400으로 거절한다.
 */
public final class Texts {

    private Texts() {
    }

    public static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** 짝이 맞지 않는 UTF-16 surrogate가 없으면 true. */
    public static boolean wellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    public static void requireWellFormed(String field, String value) {
        if (value != null && !wellFormed(value)) {
            throw ApiException.validation(field, "must be well-formed Unicode text");
        }
    }

    /** null이 아니면 잘 구성된 텍스트이고 코드포인트 길이가 min~max인지 확인한다. */
    public static void requireLength(String field, String value, int min, int max) {
        requireWellFormed(field, value);
        int length = value == null ? 0 : length(value);
        if (length < min || length > max) {
            throw ApiException.validation(field, "must be " + min + " to " + max + " characters");
        }
    }
}

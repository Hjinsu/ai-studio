package com.aistudio.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 관리자 비밀번호·로그인 규칙 (ADR-031). BCrypt 72바이트 한계를 넘는 비밀번호는 잘라서 해시하지 않고 거부한다.
 */
public final class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_UTF8_BYTES = 72;
    private static final Pattern LOGIN = Pattern.compile("^[a-z0-9._-]{3,64}$");

    private PasswordPolicy() {
    }

    /** 위반 사유를 반환하고, 문제없으면 null. 비밀번호 값은 메시지에 넣지 않는다. */
    public static String violation(char[] password) {
        if (password == null || password.length == 0) {
            return "비밀번호가 비어 있습니다.";
        }
        int codePoints = Character.codePointCount(password, 0, password.length);
        if (codePoints < MIN_CODE_POINTS) {
            return "비밀번호는 최소 " + MIN_CODE_POINTS + "자여야 합니다.";
        }
        if (utf8Length(password) > MAX_UTF8_BYTES) {
            return "비밀번호는 UTF-8 기준 " + MAX_UTF8_BYTES + "바이트를 넘을 수 없습니다(BCrypt 한계, 잘라서 저장하지 않음).";
        }
        return null;
    }

    static int utf8Length(char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        int length = encoded.remaining();
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return length;
    }

    public static String normalizeLogin(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidLogin(String normalizedLogin) {
        return LOGIN.matcher(normalizedLogin).matches();
    }
}

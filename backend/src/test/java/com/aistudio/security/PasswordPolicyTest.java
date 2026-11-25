package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-031: 최소 12자, UTF-8 72바이트 초과 거부(조용한 잘림 금지). */
class PasswordPolicyTest {

    @Test
    void requiresTwelveCodePoints() {
        assertThat(PasswordPolicy.violation("short-pass1".toCharArray())).contains("12");
        assertThat(PasswordPolicy.violation("exactly-12ch".toCharArray())).isNull();
    }

    @Test
    void rejectsMoreThan72Utf8BytesIncludingMultiByte() {
        assertThat(PasswordPolicy.violation("a".repeat(72).toCharArray())).isNull();
        assertThat(PasswordPolicy.violation("a".repeat(73).toCharArray())).contains("72");
        // 한글 1자 = 3바이트: 24자 = 72바이트 허용, 25자 = 75바이트 거부
        assertThat(PasswordPolicy.violation("가".repeat(24).toCharArray())).isNull();
        assertThat(PasswordPolicy.violation("가".repeat(25).toCharArray())).contains("72");
        // 이모지(4바이트, 서로게이트 쌍)는 1자로 센다
        assertThat(PasswordPolicy.violation("😀".repeat(12).toCharArray())).isNull();
        assertThat(PasswordPolicy.violation("😀".repeat(19).toCharArray())).contains("72");
    }

    @Test
    void normalizesLoginToLowercase() {
        assertThat(PasswordPolicy.normalizeLogin("  Admin.One ")).isEqualTo("admin.one");
        assertThat(PasswordPolicy.isValidLogin("admin.one")).isTrue();
        assertThat(PasswordPolicy.isValidLogin("ad")).isFalse();
        assertThat(PasswordPolicy.isValidLogin("admin one")).isFalse();
    }
}

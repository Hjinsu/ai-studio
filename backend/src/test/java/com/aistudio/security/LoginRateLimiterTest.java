package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aistudio.MutableClock;

/** ADR-036: login+IP 5회, login 10회, IP 30회 / 15분 → 15분 차단. */
class LoginRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, 10_000);

    private void fail(String login, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(login, ip);
        }
    }

    @Test
    void blocksLoginAndIpAfterFiveFailures() {
        fail("admin", "10.0.0.1", 4);
        assertThat(limiter.blockedFor("admin", "10.0.0.1")).isEmpty();
        LoginRateLimiter.FailureResult fifth = limiter.recordFailure("admin", "10.0.0.1");
        assertThat(fifth.newlyBlockedKeys()).containsExactly("login+ip");
        assertThat(limiter.blockedFor("admin", "10.0.0.1")).hasValueSatisfying(
                remaining -> assertThat(remaining).isEqualTo(Duration.ofMinutes(15)));
        assertThat(limiter.blockedFor("admin", "10.0.0.2")).isEmpty();
        // 차단 중 반복 실패는 새 차단으로 보고하지 않는다(감사 로그 폭증 방지)
        assertThat(limiter.recordFailure("admin", "10.0.0.1").newlyBlockedKeys()).isEmpty();
    }

    @Test
    void blocksLoginAcrossIpsAfterTenFailures() {
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("admin", "10.0.1." + i);
        }
        assertThat(limiter.blockedFor("admin", "10.9.9.9")).isPresent();
        assertThat(limiter.blockedFor("other", "10.9.9.9")).isEmpty();
    }

    @Test
    void blocksIpAcrossLoginsAfterThirtyFailures() {
        for (int i = 0; i < 30; i++) {
            limiter.recordFailure("user" + i, "10.0.0.9");
        }
        assertThat(limiter.blockedFor("fresh-user", "10.0.0.9")).isPresent();
    }

    @Test
    void blockExpiresAfterFifteenMinutesAndWindowResets() {
        fail("admin", "10.0.0.1", 5);
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(limiter.blockedFor("admin", "10.0.0.1")).isEmpty();
        fail("admin", "10.0.0.1", 4);
        assertThat(limiter.blockedFor("admin", "10.0.0.1")).isEmpty();
    }

    @Test
    void successResetsOnlyLoginAndIpCounter() {
        fail("admin", "10.0.0.1", 4);
        limiter.recordSuccess("admin", "10.0.0.1");
        fail("admin", "10.0.0.1", 4);
        assertThat(limiter.blockedFor("admin", "10.0.0.1")).isEmpty();
        // login 전체 카운터는 유지: 이전 4 + 4 + 2 = 10 → login 차단
        fail("admin", "10.0.0.2", 2);
        assertThat(limiter.blockedFor("admin", "10.0.0.3")).isPresent();
    }

    @Test
    void maxEntriesBoundsMemory() {
        LoginRateLimiter small = new LoginRateLimiter(clock, 10);
        for (int i = 0; i < 1_000; i++) {
            small.recordFailure("user" + i, "10.1." + (i / 250) + "." + (i % 250));
        }
        // 오래된 항목은 크기 제한으로 제거되므로 첫 사용자는 더 이상 추적되지 않는다
        assertThat(small.blockedFor("user0", "10.1.0.0")).isEmpty();
    }
}

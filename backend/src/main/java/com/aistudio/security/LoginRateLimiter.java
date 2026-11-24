package com.aistudio.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

/**
 * 로그인 실패 제한(ADR-036): login+IP 5회, login 10회, IP 30회 / 15분 → 15분 차단.
 * 단일 인스턴스 메모리(Caffeine, 최대 항목 수 제한). 재기동 시 초기화되는 것은 알려진 한계.
 */
@Component
public class LoginRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** 차단이 새로 시작된 키(감사 로그는 차단 창마다 1건만 남긴다, ADR-051). */
    public record FailureResult(List<String> newlyBlockedKeys) {
    }

    private static final class Counter {
        Instant windowStart;
        int failures;
        Instant blockedUntil;
    }

    private final Clock clock;
    private final List<Limit> limits;

    private record Limit(String name, int maxFailures, Cache<String, Counter> cache) {
    }

    public LoginRateLimiter(Clock clock, @Value("${ai-studio.security.login-limit.max-entries:10000}") long maxEntries) {
        this.clock = clock;
        this.limits = List.of(new Limit("login+ip", 5, cache(maxEntries)), new Limit("login", 10, cache(maxEntries)),
                new Limit("ip", 30, cache(maxEntries)));
    }

    private static Cache<String, Counter> cache(long maxEntries) {
        return Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(WINDOW.plus(BLOCK))
                .removalListener((String key, Counter value, RemovalCause cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        log.warn("Login rate limit entry evicted by size limit");
                    }
                })
                .build();
    }

    private List<String> keys(String login, String ip) {
        return List.of(login + "|" + ip, login, ip);
    }

    /** 차단 중이면 남은 시간. */
    public Optional<Duration> blockedFor(String login, String ip) {
        Instant now = clock.instant();
        List<String> keys = keys(login, ip);
        Duration longest = null;
        for (int i = 0; i < limits.size(); i++) {
            Counter counter = limits.get(i).cache().getIfPresent(keys.get(i));
            if (counter != null) {
                synchronized (counter) {
                    if (counter.blockedUntil != null && counter.blockedUntil.isAfter(now)) {
                        Duration remaining = Duration.between(now, counter.blockedUntil);
                        longest = longest == null || remaining.compareTo(longest) > 0 ? remaining : longest;
                    }
                }
            }
        }
        return Optional.ofNullable(longest);
    }

    public FailureResult recordFailure(String login, String ip) {
        Instant now = clock.instant();
        List<String> keys = keys(login, ip);
        List<String> newlyBlocked = new java.util.ArrayList<>();
        for (int i = 0; i < limits.size(); i++) {
            Limit limit = limits.get(i);
            Counter counter = limit.cache().get(keys.get(i), key -> new Counter());
            synchronized (counter) {
                if (counter.windowStart == null || !now.isBefore(counter.windowStart.plus(WINDOW))) {
                    counter.windowStart = now;
                    counter.failures = 0;
                }
                counter.failures++;
                boolean alreadyBlocked = counter.blockedUntil != null && counter.blockedUntil.isAfter(now);
                if (!alreadyBlocked && counter.failures >= limit.maxFailures()) {
                    counter.blockedUntil = now.plus(BLOCK);
                    newlyBlocked.add(limit.name());
                }
            }
            limit.cache().put(keys.get(i), counter);
        }
        return new FailureResult(newlyBlocked);
    }

    /** 성공 시 login+IP 카운터만 초기화(login·IP 전체 카운터는 분산 공격 대비로 유지). */
    public void recordSuccess(String login, String ip) {
        limits.get(0).cache().invalidate(keys(login, ip).get(0));
    }
}

package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed-stage executor settings (V2-01). Disabled by default: the executor needs both common ports, and real
 * implementations are wired by the cost/selection owner. One worker by default, at most two valid leases.
 */
@ConfigurationProperties(prefix = "ai-studio.production.executor")
public record ExecutionProperties(
        boolean enabled,
        Boolean autoStart,
        Integer workers,
        Integer maxLeases,
        Duration leaseDuration,
        Duration pollInterval,
        Duration recoveryRecheck,
        Duration retryBase,
        Duration retryCap,
        BigDecimal fakeEffectMaxKrw) {

    public static final int LEASE_CEILING = 2;
    public static final int MAX_ATTEMPTS = 3;

    public ExecutionProperties {
        autoStart = autoStart == null ? Boolean.TRUE : autoStart;
        workers = workers == null ? 1 : workers;
        maxLeases = maxLeases == null ? LEASE_CEILING : maxLeases;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
        pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
        recoveryRecheck = recoveryRecheck == null ? Duration.ofMinutes(5) : recoveryRecheck;
        retryBase = retryBase == null ? Duration.ofSeconds(5) : retryBase;
        retryCap = retryCap == null ? Duration.ofMinutes(5) : retryCap;
        fakeEffectMaxKrw = fakeEffectMaxKrw == null ? BigDecimal.ONE : fakeEffectMaxKrw;
        if (workers < 1 || workers > LEASE_CEILING) {
            throw new IllegalArgumentException("workers must be between 1 and " + LEASE_CEILING);
        }
        if (maxLeases < 1 || maxLeases > LEASE_CEILING) {
            throw new IllegalArgumentException("maxLeases must be between 1 and " + LEASE_CEILING);
        }
        if (leaseDuration.toMillis() < 1000) {
            throw new IllegalArgumentException("leaseDuration must be at least one second");
        }
        requirePositive(pollInterval, "pollInterval");
        requirePositive(recoveryRecheck, "recoveryRecheck");
        requirePositive(retryBase, "retryBase");
        if (retryCap.compareTo(retryBase) < 0) {
            throw new IllegalArgumentException("retryCap must not be shorter than retryBase");
        }
        if (fakeEffectMaxKrw.signum() < 0) {
            throw new IllegalArgumentException("fakeEffectMaxKrw must not be negative");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static ExecutionProperties defaults() {
        return new ExecutionProperties(false, null, null, null, null, null, null, null, null, null);
    }
}

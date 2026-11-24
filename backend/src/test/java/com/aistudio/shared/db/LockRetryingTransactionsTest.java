package com.aistudio.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/** ADR-043: 잠금 실패만 최초 실행 외 최대 3회 재시도, 비즈니스 오류는 재시도 금지. */
class LockRetryingTransactionsTest {

    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final LockRetryingTransactions transactions;

    LockRetryingTransactionsTest() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        transactions = new LockRetryingTransactions(manager);
    }

    @Test
    void retriesLockFailuresThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = transactions.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("deadlock");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void givesUpAfterThreeRetries() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("deadlock");
        })).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(attempts).hasValue(1 + LockRetryingTransactions.MAX_RETRIES);
    }

    @Test
    void doesNotRetryBusinessErrors() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            throw new ApiException(ErrorCode.BUDGET_EXCEEDED);
        })).isInstanceOf(ApiException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void backoffGrowsWithJitter() {
        for (int attempt = 0; attempt < 3; attempt++) {
            long base = 50L << attempt;
            assertThat(LockRetryingTransactions.backoffMillis(attempt)).isBetween(base, base + base / 2);
        }
    }
}

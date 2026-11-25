package com.aistudio.shared.db;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예산·원장 트랜잭션 실행기 (ADR-043). READ COMMITTED, 데드락(1213)·잠금 대기 초과(1205)만
 * 전체 롤백 후 최초 실행 외 최대 3회 재시도(지수 백오프 + jitter). MariaDB 스냅샷 충돌(1020)도 재시도한다.
 * 비즈니스 오류는 재시도하지 않는다.
 */
@Component
public class LockRetryingTransactions {

    static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MILLIS = 50;
    private static final Logger log = LoggerFactory.getLogger(LockRetryingTransactions.class);

    private final TransactionTemplate template;

    public LockRetryingTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Starts the top-level transaction for each attempt so rollback finishes before a retry. */
    public <T> T execute(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Budget lock retry cannot run inside an active transaction");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (org.springframework.dao.DataAccessException e) {
                boolean retryable = e instanceof PessimisticLockingFailureException
                        || DatabaseErrors.isSnapshotConflict(e);
                if (!retryable || attempt >= MAX_RETRIES) {
                    throw e;
                }
                long backoff = backoffMillis(attempt);
                log.warn("Lock conflict, retrying transaction (attempt {} of {}) after {} ms", attempt + 1, MAX_RETRIES,
                        backoff);
                sleep(backoff);
            }
        }
    }

    public void run(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }

    static long backoffMillis(int attempt) {
        long base = BASE_BACKOFF_MILLIS << attempt;
        return base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}

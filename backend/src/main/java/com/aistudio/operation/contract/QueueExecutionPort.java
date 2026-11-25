package com.aistudio.operation.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aistudio.operation.contract.OperationCostPort.JobOwner;
import com.aistudio.operation.contract.OperationCostPort.Receipt;

/** C supplies the queue-side implementation. */
public interface QueueExecutionPort {
    record AttemptFence(JobOwner owner, long attemptId, int attemptNo, UUID leaseToken) {}

    record LockedQueueState(boolean dispatchEnabled, boolean cancelRequested, boolean failFast,
            String jobStatus, Long currentAttemptId, Integer currentAttemptNo, UUID leaseToken,
            Instant leaseExpiresAt, Instant executionDeadlineAt, Instant databaseNow) {}

    enum ApplyResult { APPLIED, ALREADY_APPLIED }

    /**
     * Acquire the queue prefix in the caller's top-level READ COMMITTED TX4.
     * Reject calls without that transaction. Do not start, suspend or independently retry it.
     */
    Optional<LockedQueueState> lockForDispatch(AttemptFence fence);

    /**
     * Entry point for an independent TX7. Verify the receipt against durable evidence
     * and commit the job transition and receipt application marker atomically.
     */
    ApplyResult applyReceipt(Receipt receipt);
}

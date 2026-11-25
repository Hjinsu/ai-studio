package com.aistudio.operation.contract;

import java.util.Optional;
import java.util.UUID;

import com.aistudio.operation.contract.OperationCostPort.JobOwner;
import com.aistudio.operation.contract.QueueExecutionPort.AttemptFence;

/** D owns the operation write boundary. Calls enter without an active queue transaction. */
public interface OperationLifecyclePort {
    record Prepare(AttemptFence fence, String effectSlot, int payloadVersion, String canonicalPayload) {}

    record Prepared(JobOwner owner, UUID operationId, String reservationRequestKey) {}

    record DispatchPermit(JobOwner owner, UUID operationId, UUID dispatchId, long issuedToAttemptId) {}

    enum BillingOutcome { CHARGED, NOT_CHARGED, UNKNOWN }

    enum ArtifactOutcome { VERIFIED, FAILED, UNKNOWN }

    record Evidence(UUID dispatchId, long attemptId, String digest, BillingOutcome billingOutcome,
            ArtifactOutcome artifactOutcome, String artifactRef, String artifactHash, String canonicalMetadata) {}

    /**
     * Create or replay the same logical effect under the Run lock shared with R10.
     * Refuse a new effect on a terminal Run. The server owns the keys and fingerprint.
     */
    Prepared prepare(Prepare command);

    /** Drive TX1b/TX2/TX3 using the already assigned request key. */
    void reserveAndLink(JobOwner owner, UUID operationId);

    /** Only a directly acknowledged TX4 commit can return a permit. No permit replay. */
    Optional<DispatchPermit> authorizeDispatch(AttemptFence fence, UUID operationId);

    /** Append immutable evidence; this grants no operation or job state-write authority. */
    void recordEvidence(JobOwner owner, UUID operationId, Evidence evidence);

    /** Start or replay the recovery path for an operation that has no dispatch. */
    void requestUnsentReservationClose(JobOwner owner, UUID operationId);
}

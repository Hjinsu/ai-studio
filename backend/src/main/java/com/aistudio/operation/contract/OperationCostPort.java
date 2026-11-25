package com.aistudio.operation.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** D-owned read boundary for queue recovery and P12. */
public interface OperationCostPort {
    enum CostPhase { PRE, INFLIGHT, NONE }

    enum Outcome { EXECUTED_RESULT_ADOPTED, NOT_EXECUTED, EXECUTED_FAILED }

    record JobOwner(long channelId, long contentId, long runId, long jobId) {}

    /** The terminal receipt uses operationId as its stable identity. */
    record Receipt(JobOwner owner, UUID operationId, long operationVersion, Outcome outcome,
            Integer terminalEventSequence, Instant committedAt) {}

    record RunCostState(boolean unresolvedCostOrOperation, List<UUID> unappliedReceiptIds) {
        public RunCostState {
            unappliedReceiptIds = List.copyOf(unappliedReceiptIds);
        }
    }

    /** Empty means the phase could not be established; NONE requires positive evidence. */
    Optional<CostPhase> phase(JobOwner owner);

    /** Empty is an unresolved lookup and is not evidence of absence of cost or dispatch. */
    Optional<Receipt> terminalReceipt(JobOwner owner, UUID operationId);

    /** R10 calls this after locking the run; an unknown result must block termination. */
    RunCostState runCostState(long contentId, long runId);
}

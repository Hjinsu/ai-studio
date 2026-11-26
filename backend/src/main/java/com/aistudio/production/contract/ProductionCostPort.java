package com.aistudio.production.contract;

import java.math.BigDecimal;
import java.util.List;

/** Owns reservation and dispatch. The executor must never send the provider request itself. */
public interface ProductionCostPort {
    record StepOwner(long requestId, long stepId, String leaseOwner, long leaseEpoch) {}

    record PricedRequest(String currency, BigDecimal maximumAmount, String fakeScenario) {}

    enum Disposition { RECEIPT, WAITING, REJECTED, RECOVERY_REQUIRED }

    enum Outcome { RESULT_ADOPTED, EXECUTED_FAILED, NOT_EXECUTED }

    record Evidence(Outcome outcome, String externalRef, String currency, BigDecimal amount,
            String resultRef, String resultHash, String proof) {}

    record Receipt(long operationId, Outcome outcome, String resultRef, String resultHash, long ledgerEventId) {}

    record EffectResult(Disposition disposition, Receipt receipt) {}

    record CostState(boolean unresolved, boolean spendingBlocked, List<Receipt> unappliedReceipts) {
        public CostState {
            unappliedReceipts = List.copyOf(unappliedReceipts);
        }
    }

    EffectResult executeEffect(StepOwner owner, String effectKey, String requestHash, PricedRequest request);

    CostState readCostState(long requestId);

    /** Joins the executor transaction that updates the step. Requires current ownership. */
    void markReceiptApplied(StepOwner owner, long operationId);

    Receipt reconcile(long operationId, String evidenceKey, Evidence evidence);
}

package com.aistudio.production.execution;

import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;

/**
 * What each fixed stage does. The executor owns ordering, leases, attempts and state; this only describes the
 * work. LOCAL work must be free of external effects. EFFECT work only prices a request: the cost port owns
 * reservation and dispatch. SELECT links the chosen topic through the selection port.
 */
public interface StageWork {
    enum Kind { LOCAL, EFFECT, SELECT }

    record StepContext(long requestId, long stepId, ProductionStage stage, String targetKey, int inputGeneration,
            int attempt, long channelId, String contentType, Long contentId, Long runId) {}

    Kind kind(ProductionStage stage);

    /** Local effect-free work. Returns a result reference. Throw {@link StepFailure} to classify a failure. */
    String runLocal(StepContext context);

    /** Must be deterministic for the same step identity: it feeds the persistent request hash. */
    ProductionCostPort.PricedRequest price(StepContext context);

    /** Must be deterministic for the same request: replays must produce the same selection fingerprint. */
    ProductionSelectionPort.Selection select(StepContext context);

    static void validate(StageWork work) {
        for (ProductionStage stage : ProductionStage.values()) {
            Kind kind = work.kind(stage);
            if (kind == null || (stage == ProductionStage.SELECT) != (kind == Kind.SELECT)) {
                throw new IllegalStateException("SELECT, and only SELECT, must use the selection port: " + stage);
            }
        }
    }
}

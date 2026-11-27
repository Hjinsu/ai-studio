package com.aistudio.production.contract;

import java.math.BigDecimal;
import java.time.Instant;

import com.aistudio.production.contract.MediaEffectPort.ProviderResult;

/** Supplier boundary. Only a committed server permit may be passed to submit. */
public interface ProductionMediaSupplier {
    enum Output { NARRATION, IMAGE, IMAGE_TO_VIDEO, TEXT_TO_VIDEO }
    enum JobState { PENDING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

    record Capability(String namespace, String sku, Output output, boolean asynchronous,
            boolean cancellationSupported, int maxDurationMs, int maxWidth, int maxHeight) {}

    record Quote(String sku, String priceVersion, String currency, BigDecimal maximumAmount, Instant validUntil) {}

    record Permit(long operationId, String effectKey, String inputSha256, Quote quote) {}

    record JobId(String namespace, String value) {}

    sealed interface Submission permits Accepted, Completed, Unknown {}
    record Accepted(JobId jobId) implements Submission {}
    record Completed(ProviderResult result) implements Submission {}
    record Unknown() implements Submission {}

    record Observation(JobState state, Instant nextPollAt) {}

    Capability capability();

    Submission submit(Permit permit, String canonicalInput);

    Observation poll(JobId job);

    /** Retrieves evidence and bounded artifact streams for the same job without submitting another effect. */
    ProviderResult retrieve(JobId job);
}

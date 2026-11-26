package com.aistudio.production.contract;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;

public interface MediaEffectPort {
    record Item(StepOwner owner, long itemId) {}

    record ExecutionProof(String providerRequestId, String providerResultId, BigDecimal amount, String currency,
            String evidenceReference) {}

    @FunctionalInterface
    interface OpenPayload {
        InputStream open() throws IOException;
    }

    record Payload(Role role, String mimeType, long byteLimit, String lineageJson, String rightsJson,
            OpenPayload content) {}

    record ProviderResult(ExecutionProof proof, List<Payload> payloads) {}

    @FunctionalInterface
    interface FakeProvider {
        ProviderResult send(long operationId);
    }

    /** Execute at most one simulated provider call, then persist and settle the owned item. */
    EffectResult execute(Item item, BigDecimal maximumKrw, FakeProvider provider);

    /** Replay an already-started item using persisted proof, files and receipt. */
    EffectResult recover(Item item);

    /** Apply a settled receipt in the fenced item transaction, without closing the parent step. */
    void apply(Item item, long operationId);
}

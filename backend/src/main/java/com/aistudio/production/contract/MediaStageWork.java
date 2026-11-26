package com.aistudio.production.contract;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.aistudio.production.contract.ProductionCostPort.StepOwner;

public interface MediaStageWork {
    enum Outcome { APPLIED, RETRY, BLOCKED, RECOVERY_REQUIRED }

    record Item(long id, int ordinal, String inputJson, String inputHash, String state, Long operationId) {}

    record Context(StepOwner owner, long channelId, long contentId, long runId, long revisionId, int attemptNo,
            ProductionStage stage, String profileJson, String profileHash, List<Item> items,
            BooleanSupplier cancelled, boolean recoveryOnly) {
        public Context {
            items = List.copyOf(items);
        }
    }

    record Result(Outcome outcome, String code) {}

    Result execute(Context context);

    Result recover(Context context);
}

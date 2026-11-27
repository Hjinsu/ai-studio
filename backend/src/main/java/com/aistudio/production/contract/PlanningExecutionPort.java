package com.aistudio.production.contract;

import java.math.BigDecimal;

import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Validation;

/** Executor boundary. Apply joins the fenced transaction and marks the receipt applied. */
public interface PlanningExecutionPort {
    /** Joins the current Worker adoption transaction with a validated planning body. */
    default void applyWorker(StepOwner owner, long workerId, ProductionStage stage,
            String fixedInput, String body, Validation validation) {
        throw new UnsupportedOperationException("Worker planning adoption is unavailable");
    }

    record CandidateResult(ProductionStage stage, Object body) {}

    ResearchInput researchInput(long requestId);

    ScriptInput scriptInput(long requestId);

    ScenesInput scenesInput(long requestId);

    EffectResult research(StepOwner owner, ResearchInput input, BigDecimal maximumKrw);

    EffectResult script(StepOwner owner, ScriptInput input, BigDecimal maximumKrw);

    // The adapter derives narration and subtitle text from the fixed script before storing Scenes.
    EffectResult scenes(StepOwner owner, ScenesInput input, BigDecimal maximumKrw);

    CandidateResult response(StepOwner owner, Receipt receipt);

    void apply(StepOwner owner, Receipt receipt, Validation validation);
}

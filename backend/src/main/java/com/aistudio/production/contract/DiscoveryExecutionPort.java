package com.aistudio.production.contract;

import java.util.List;

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionDiscovery.Response;

/** Workflow seam for the fixed-stage executor and the discovery persistence/cost adapter. */
public interface DiscoveryExecutionPort {
    record SelectionBatch(Mode mode, int version, Integer chosenCandidateId,
            List<EvaluatedCandidate> candidates, String responseHash) {
        public boolean permitsManualChoice(EvaluatedCandidate refreshed) {
            return mode == Mode.MANUAL && chosenCandidateId != null && chosenCandidateId == refreshed.candidateId()
                    && candidates.stream().anyMatch(previous -> previous.candidateId() == refreshed.candidateId()
                            && previous.permitsManualRecheck(refreshed));
        }
    }

    /** Adopts an evaluated Worker response in the current fenced transaction. */
    default void applyWorker(StepOwner owner, long exchangeId, String body, String hash,
            List<EvaluatedCandidate> evaluations) {
        throw new UnsupportedOperationException("Worker discovery adoption is unavailable");
    }

    boolean manualMode(long requestId);

    Input input(long requestId);

    /** Executes the DISCOVER logical effect through the shared V2-01 cost boundary. */
    EffectResult discover(StepOwner owner, Input input);

    /** Verifies ownership of the persisted response and its hash against the receipt. */
    Response response(StepOwner owner, Receipt receipt);

    /** Includes exact Topic matches for the supplied candidates and bounded related Content. */
    List<CatalogEntry> catalog(long requestId, List<ProductionDiscovery.Candidate> candidates);

    /** Stores evaluation and applies the receipt in the same fenced transaction as the step update. */
    void applyEvaluation(StepOwner owner, Receipt receipt, List<EvaluatedCandidate> evaluations);

    /** Joins the request-locked SELECT transaction and acquires the channel lock for re-evaluation and linking. */
    SelectionBatch lockForSelection(StepOwner owner);

    /** Persists the selection in the same fenced transaction as the link and SELECT completion. */
    void recordSelection(StepOwner owner, int version, int candidateId, String rationale);

    /** Also preserves the current catalog evaluation that justifies the link. */
    default void recordSelection(StepOwner owner, int version, int candidateId, String rationale,
            List<EvaluatedCandidate> rechecked) {
        recordSelection(owner, version, candidateId, rationale);
    }

    /** Records re-evaluation and clears a stale manual choice under the same selection lock. */
    void invalidateManualChoice(StepOwner owner, int version, List<EvaluatedCandidate> evaluations);

    /** Preserves the latest catalog reasons when AUTO has no eligible candidate. */
    default void recordExhaustedSelection(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        throw new UnsupportedOperationException("Discovery recheck persistence is required.");
    }
}

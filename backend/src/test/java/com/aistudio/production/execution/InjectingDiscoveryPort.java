package com.aistudio.production.execution;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;

/**
 * Delegates every call to the real discovery persistence/cost adapter on the same DB. Hooks run in the caller's
 * transaction just before the delegated side effect, so a test can inject a failure or a concurrent catalog change
 * without replacing storage, cost or selection behaviour.
 */
final class InjectingDiscoveryPort implements DiscoveryExecutionPort {
    private final DiscoveryExecutionPort real;

    volatile Consumer<Long> beforeApply = id -> {};
    volatile Consumer<Long> beforeLock = id -> {};
    volatile Consumer<Long> beforeRecord = id -> {};
    final AtomicInteger invalidations = new AtomicInteger();
    final AtomicInteger exhausted = new AtomicInteger();

    InjectingDiscoveryPort(DiscoveryExecutionPort real) {
        this.real = real;
    }

    void reset() {
        beforeApply = id -> {};
        beforeLock = id -> {};
        beforeRecord = id -> {};
        invalidations.set(0);
        exhausted.set(0);
    }

    @Override
    public boolean manualMode(long requestId) {
        return real.manualMode(requestId);
    }

    @Override
    public Input input(long requestId) {
        return real.input(requestId);
    }

    @Override
    public EffectResult discover(StepOwner owner, Input input) {
        return real.discover(owner, input);
    }

    @Override
    public Response response(StepOwner owner, Receipt receipt) {
        return real.response(owner, receipt);
    }

    @Override
    public List<CatalogEntry> catalog(long requestId, List<Candidate> candidates) {
        return real.catalog(requestId, candidates);
    }

    @Override
    public void applyEvaluation(StepOwner owner, Receipt receipt, List<EvaluatedCandidate> evaluations) {
        beforeApply.accept(owner.requestId());
        real.applyEvaluation(owner, receipt, evaluations);
    }

    @Override
    public SelectionBatch lockForSelection(StepOwner owner) {
        beforeLock.accept(owner.requestId());
        return real.lockForSelection(owner);
    }

    @Override
    public void recordSelection(StepOwner owner, int version, int candidateId, String rationale) {
        throw new AssertionError("the executor must preserve the SELECT re-check with the five-argument call");
    }

    @Override
    public void recordSelection(StepOwner owner, int version, int candidateId, String rationale,
            List<EvaluatedCandidate> rechecked) {
        beforeRecord.accept(owner.requestId());
        real.recordSelection(owner, version, candidateId, rationale, rechecked);
    }

    @Override
    public void invalidateManualChoice(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        invalidations.incrementAndGet();
        real.invalidateManualChoice(owner, version, evaluations);
    }

    @Override
    public void recordExhaustedSelection(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        exhausted.incrementAndGet();
        real.recordExhaustedSelection(owner, version, evaluations);
    }
}

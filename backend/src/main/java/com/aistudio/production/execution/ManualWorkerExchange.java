package com.aistudio.production.execution;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.RealMediaProfile;
import com.aistudio.production.contract.WorkerExchange;
import com.aistudio.production.contract.WorkerExchange.Identity;
import com.aistudio.production.contract.WorkerExchange.Request;
import com.aistudio.content.command.CanonicalJson;

/** Owns the durable envelope. The stage caller supplies its existing result validator and DB adopter. */
@Service
public class ManualWorkerExchange {
    private final ExecutionMapper requests;
    private final WorkerExchangeMapper exchanges;

    ManualWorkerExchange(ExecutionMapper requests, WorkerExchangeMapper exchanges) {
        this.requests = requests;
        this.exchanges = exchanges;
    }

    @Transactional
    public Request issue(StepOwner owner, ProductionStage stage, String canonicalInput) {
        lockCurrent(owner);
        var job = exchanges.latest(owner);
        if (job == null) {
            if (exchanges.issue(owner, canonicalInput, CanonicalJson.sha256Hex(canonicalInput)) != 1) {
                throw new IllegalStateException("Worker request could not be issued");
            }
            job = exchanges.latest(owner);
        }
        return new Request(identity(owner, stage, job), job.inputJson());
    }

    /**
     * The adopter must validate and apply the stage result using only the current transaction.
     * File IO and CLI execution belong outside this transaction.
     */
    @Transactional
    public boolean adopt(StepOwner owner, ProductionStage stage, WorkerExchange.Draft draft,
            WorkerExchange.Review review, Consumer<String> adopter) {
        lockCurrent(owner);
        var job = exchanges.latest(owner);
        Identity expected = identity(owner, stage, job);
        WorkerExchange.verifyLineage(expected, draft, review);
        if (job.applied()) {
            if (!Objects.equals(job.draftSha256(), draft.payloadSha256())
                    || !Objects.equals(job.resultSha256(), review.payloadSha256())) {
                throw new IllegalArgumentException("Conflicting worker replay");
            }
            return false;
        }

        if (exchanges.apply(owner, job.id(), draft.payloadSha256(), review.payloadSha256()) != 1) {
            throw new IllegalStateException("Worker adoption lost current ownership");
        }
        adopter.accept(review.payload());
        return true;
    }

    private void lockCurrent(StepOwner owner) {
        var request = requests.lock(owner.requestId());
        if (request == null || !RealMediaProfile.PIPELINE.equals(request.pipelineVersion())
                || !request.leaseValid() || request.cancelRequested()
                || request.leaseEpoch() != owner.leaseEpoch()
                || !Objects.equals(request.leaseOwner(), owner.leaseOwner())) {
            throw new IllegalStateException("Worker operation requires the current request lease");
        }
    }

    private static Identity identity(StepOwner owner, ProductionStage stage, WorkerExchangeMapper.Exchange job) {
        if (job == null || job.stage() != stage) {
            throw new IllegalArgumentException("Worker job does not belong to this stage");
        }
        return new Identity(1, job.id(), owner.requestId(), owner.stepId(), stage, job.attemptNo(),
                job.issuedEpoch(), job.inputSha256(), job.selectionSha256());
    }
}

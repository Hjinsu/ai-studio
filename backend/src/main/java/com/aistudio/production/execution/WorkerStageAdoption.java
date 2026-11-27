package com.aistudio.production.execution;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.production.contract.DiscoveryEvaluator;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.StepStatus;
import com.aistudio.production.contract.WorkerDiscoveryRequest;
import com.aistudio.production.contract.WorkerExchange;
import com.aistudio.production.discovery.DiscoveryInputs;
import com.aistudio.production.discovery.DiscoveryResponseValidator;
import com.aistudio.production.stage.PlanningReview;

import tools.jackson.databind.json.JsonMapper;

/** Common worker adoption. No CLI, filesystem or network activity occurs in the transaction. */
@Service
public class WorkerStageAdoption {
    private final ManualWorkerExchange exchanges;
    private final ExecutionMapper requests;
    private final DiscoveryExecutionPort discovery;
    private final DiscoveryEvaluator evaluator;
    private final PlanningExecutionPort planning;
    private final ObjectProvider<PlanningReview> review;
    private final JsonMapper json;

    WorkerStageAdoption(ManualWorkerExchange exchanges, ExecutionMapper requests,
            DiscoveryExecutionPort discovery, DiscoveryEvaluator evaluator, PlanningExecutionPort planning,
            ObjectProvider<PlanningReview> review, JsonMapper json) {
        this.exchanges = exchanges;
        this.requests = requests;
        this.discovery = discovery;
        this.evaluator = evaluator;
        this.planning = planning;
        this.review = review;
        this.json = json;
    }

    @Transactional
    public boolean adopt(StepOwner owner, String fixedInput, WorkerExchange.Draft draft, WorkerExchange.Review review) {
        var identity = review.identity();
        if (!PlanningJson.matches(fixedInput, identity.inputSha256())) {
            throw new IllegalArgumentException("Worker input does not match the issued input");
        }
        return exchanges.adopt(owner, identity.stage(), draft, review, body -> {
            boolean eligible = identity.stage() == ProductionStage.DISCOVER
                    ? adoptDiscovery(owner, identity.jobId(), fixedInput, body, review.payloadSha256())
                    : adoptPlanning(owner, identity, fixedInput, body);
            int changed = eligible ? requests.succeedStep(owner.requestId(), owner.stepId(), "worker:" + identity.jobId())
                    : requests.endStep(owner.requestId(), owner.stepId(), StepStatus.FAILED, "DISCOVERY_NO_ELIGIBLE");
            if (changed != 1) {
                throw new IllegalStateException("Worker stage could not finish");
            }
        });
    }

    private boolean adoptDiscovery(StepOwner owner, long jobId, String fixedInput, String body, String hash) {
        var input = readCanonical(fixedInput, WorkerDiscoveryRequest.class);
        if (!Objects.equals(input.discovery(), discovery.input(owner.requestId()))) {
            throw new IllegalArgumentException("Worker discovery input does not match the request");
        }
        DiscoveryInputs.validate(input.discovery());
        var response = readCanonical(body, Response.class);
        DiscoveryResponseValidator.validate(input.discovery(), response);
        var candidates = evaluator.evaluate(input.discovery(), response,
                discovery.catalog(owner.requestId(), response.candidates()));
        discovery.applyWorker(owner, jobId, body, hash, candidates);
        boolean manual = discovery.manualMode(owner.requestId());
        return candidates.stream().anyMatch(candidate -> manual ? candidate.manuallySelectable() : candidate.eligible());
    }

    private boolean adoptPlanning(StepOwner owner, WorkerExchange.Identity identity, String fixedInput, String body) {
        ProductionStage stage = identity.stage();
        Object input = switch (stage) {
            case RESEARCH -> planning.researchInput(owner.requestId());
            case SCRIPT -> planning.scriptInput(owner.requestId());
            case SCENES -> planning.scenesInput(owner.requestId());
            default -> throw new IllegalArgumentException("Invalid Worker planning stage");
        };
        if (!PlanningJson.encode(input).json().equals(fixedInput)) {
            throw new IllegalArgumentException("Worker planning input no longer matches the selected revision");
        }
        Object result = switch (stage) {
            case RESEARCH -> readCanonical(body, Research.class);
            case SCRIPT -> readCanonical(body, Script.class);
            case SCENES -> workerScenes(body);
            default -> throw new IllegalArgumentException("Invalid Worker planning stage");
        };
        var validation = review.getObject().review(stage, input, result);
        if (validation.blocked()) {
            throw new IllegalArgumentException(ProductionPlanning.BLOCKED);
        }
        planning.applyWorker(owner, identity.jobId(), stage, fixedInput, body, validation);
        return true;
    }

    private Scenes workerScenes(String body) {
        Scenes result = readCanonical(body, Scenes.class);
        if (result.dynamicPlan() == null || result.scenes() == null
                || result.dynamicPlan().scenes().size() != result.scenes().size()) {
            throw new IllegalArgumentException("Worker scenes require an aligned dynamic plan");
        }
        for (int i = 0; i < result.scenes().size(); i++) {
            var scene = result.scenes().get(i);
            var intent = result.dynamicPlan().scenes().get(i);
            if (!intent.id().equals(scene.id()) || !intent.segmentIds().equals(scene.segmentIds())) {
                throw new IllegalArgumentException("Dynamic plan must follow the reviewed scene range");
            }
        }
        return result;
    }

    private <T> T readCanonical(String body, Class<T> type) {
        T value = json.readValue(body, type);
        if (!PlanningJson.encode(value).json().equals(body)) {
            throw new IllegalArgumentException("Worker payload must match its canonical stage schema");
        }
        return value;
    }
}

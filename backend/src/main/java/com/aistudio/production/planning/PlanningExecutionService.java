package com.aistudio.production.planning;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ProductionCostPort.Disposition;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.Comparison;
import com.aistudio.production.contract.ProductionPlanning.InputVersion;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.ResultRef;
import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.ScenePlan;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Snapshot;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.cost.ProductionCostService;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

@Service
class PlanningExecutionService implements PlanningExecutionPort {
    private static final InputVersion VERSION = new InputVersion("planning-prompt-1", "planning-rules-1",
            "fake-planning-1");

    private final PlanningResultMapper results;
    private final ProductionDiscoveryMapper discovery;
    private final ProductionCostService cost;
    private final ObjectProvider<PlanningProvider> provider;
    private final JsonMapper json;

    PlanningExecutionService(PlanningResultMapper results, ProductionDiscoveryMapper discovery,
            ProductionCostService cost, ObjectProvider<PlanningProvider> provider, JsonMapper json) {
        this.results = results;
        this.discovery = discovery;
        this.cost = cost;
        this.provider = provider;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public ResearchInput researchInput(long requestId) {
        var fixed = fixed(requestId, ProductionStage.RESEARCH, ResearchInput.class);
        if (fixed != null) {
            return fixed;
        }
        var snapshot = snapshot(requestId);
        var batch = discovery.read(requestId);
        var response = discovery.generated(requestId);
        if (batch == null || batch.selectedCandidateId() == null || response == null
                || !PlanningJson.matches(response.responseJson(), response.responseHash())
                || !cost.appliedDiscoveryReceipt(requestId, response.operationId(), response.responseHash())) {
            throw invalid();
        }
        int candidateId = batch.selectedCandidateId();
        var candidates = json.readValue(response.responseJson(), Response.class).candidates();
        if (candidateId < 1 || candidateId > candidates.size()) {
            throw invalid();
        }
        var candidate = candidates.get(candidateId - 1);
        return new ResearchInput(snapshot, VERSION, candidateId, response.responseHash(), candidate.centralQuestion(),
                candidate.angle(), candidate.outline(), candidate.evidencePlan(),
                candidate.assertions().stream().map(claim -> claim.assertion()).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ScriptInput scriptInput(long requestId) {
        var fixed = fixed(requestId, ProductionStage.SCRIPT, ScriptInput.class);
        if (fixed != null) {
            return fixed;
        }
        var research = accepted(requestId, ProductionStage.RESEARCH);
        var comparisons = results.recentScripts(requestId, ProductionPlanning.SCRIPT_COMPARISON_LIMIT)
                .stream().map(this::comparison).toList();
        return new ScriptInput(snapshot(requestId), VERSION, ref(research),
                json.readValue(research.bodyJson(), Research.class), comparisons);
    }

    @Override
    @Transactional(readOnly = true)
    public ScenesInput scenesInput(long requestId) {
        var fixed = fixed(requestId, ProductionStage.SCENES, ScenesInput.class);
        if (fixed != null) {
            return fixed;
        }
        var script = accepted(requestId, ProductionStage.SCRIPT);
        return new ScenesInput(snapshot(requestId), VERSION, ref(script), json.readValue(script.bodyJson(), Script.class));
    }

    @Override
    public EffectResult research(StepOwner owner, ResearchInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.RESEARCH, input, researchInput(owner.requestId()),
                ResearchInput.class, maximumKrw, PlanningProvider::research);
    }

    @Override
    public EffectResult script(StepOwner owner, ScriptInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.SCRIPT, input, scriptInput(owner.requestId()),
                ScriptInput.class, maximumKrw, PlanningProvider::writeScript);
    }

    @Override
    public EffectResult scenes(StepOwner owner, ScenesInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.SCENES, input, scenesInput(owner.requestId()),
                ScenesInput.class, maximumKrw,
                (adapter, fixed) -> materialize(fixed.script(), adapter.planScenes(fixed)));
    }

    private <T> EffectResult execute(StepOwner owner, ProductionStage stage, T input, T expected,
            Class<T> type, BigDecimal maximumKrw, BiFunction<PlanningProvider, T, Object> generate) {
        if (!Objects.equals(input, expected)) {
            throw invalid();
        }
        var adapter = provider.getIfAvailable();
        if (adapter == null) {
            return new EffectResult(Disposition.REJECTED, null);
        }
        PlanningJson.Encoded encoded = PlanningJson.encode(expected);
        T fixed = json.readValue(encoded.json(), type);
        return cost.executePlanning(owner, stage, encoded, maximumKrw, () -> generate.apply(adapter, fixed));
    }

    @Override
    public CandidateResult response(StepOwner owner, Receipt receipt) {
        var stored = cost.planningResponse(owner, receipt);
        var stage = results.stage(owner.requestId(), stored.stepId());
        Object body = switch (stage) {
            case RESEARCH -> json.readValue(stored.bodyJson(), Research.class);
            case SCRIPT -> json.readValue(stored.bodyJson(), Script.class);
            case SCENES -> json.readValue(stored.bodyJson(), Scenes.class);
            default -> throw invalid();
        };
        return new CandidateResult(stage, body);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(StepOwner owner, Receipt receipt, Validation validation) {
        cost.markReceiptApplied(owner, receipt.operationId());
        var stored = cost.planningResponse(owner, receipt);
        String encoded = PlanningJson.body(validation).json();
        if (results.saveValidation(receipt.operationId(), encoded) != 1
                && !encoded.equals(stored.validationJson())) {
            throw invalid();
        }
    }

    private Snapshot snapshot(long requestId) {
        var snapshot = results.snapshot(requestId);
        if (snapshot == null) {
            throw invalid();
        }
        return snapshot;
    }

    private <T> T fixed(long requestId, ProductionStage stage, Class<T> type) {
        var fixed = results.fixedInput(requestId, stage);
        if (fixed == null) {
            return null;
        }
        if (fixed.schemaVersion() != ProductionPlanning.VERSION
                || !PlanningJson.matches(fixed.inputJson(), fixed.inputHash())) {
            throw invalid();
        }
        return json.readValue(fixed.inputJson(), type);
    }

    private PlanningResultMapper.Applied accepted(long requestId, ProductionStage stage) {
        var result = results.accepted(requestId, stage);
        checkAccepted(result);
        return result;
    }

    private void checkAccepted(PlanningResultMapper.Applied result) {
        if (result == null || !PlanningJson.matches(result.bodyJson(), result.bodyHash())
                || json.readValue(result.validationJson(), Validation.class).blocked()) {
            throw invalid();
        }
    }

    private Comparison comparison(PlanningResultMapper.Applied result) {
        checkAccepted(result);
        var script = json.readValue(result.bodyJson(), Script.class);
        var segments = script.segments();
        String hook = segments.stream().filter(segment -> script.hookSegmentIds().contains(segment.id()))
                .map(Segment::narration).collect(java.util.stream.Collectors.joining(" "));
        return new Comparison(ref(result), excerpt(hook), excerpt(script.editorialIntent()),
                excerpt(segments.stream().map(Segment::narration).collect(java.util.stream.Collectors.joining(" "))),
                excerpt(segments.getLast().narration()));
    }

    private static String excerpt(String text) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), 600)));
    }

    private static ResultRef ref(PlanningResultMapper.Applied result) {
        return new ResultRef(result.operationId(), result.stage(), result.bodyHash());
    }

    private static Scenes materialize(Script script, ScenePlan plan) {
        if (plan == null || plan.scenes() == null) {
            return null;
        }
        Map<String, String> narration = new LinkedHashMap<>();
        for (Segment segment : script.segments()) {
            narration.put(segment.id(), segment.narration());
        }
        List<Scene> scenes = plan.scenes().stream().map(draft -> {
            String words = draft.segmentIds().stream().map(id -> narration.getOrDefault(id, ""))
                    .collect(java.util.stream.Collectors.joining(" "));
            return new Scene(draft.id(), draft.order(), draft.segmentIds(), draft.estimatedDurationMs(), words,
                    draft.visualDescription(), draft.imagePrompt(), draft.direction(), draft.transition(), words);
        }).toList();
        return new Scenes(plan.visualStyle(), scenes);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Planning input or result integrity check failed.");
    }
}

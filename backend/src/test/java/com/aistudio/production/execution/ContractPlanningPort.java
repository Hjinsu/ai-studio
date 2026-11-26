package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.Disposition;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.PricedRequest;
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
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper;
import com.aistudio.production.planning.PlanningResultMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * Contract double of {@link PlanningExecutionPort} for the product branch, which does not contain the storage and
 * cost adapter (Codex, v2/03-safety). It follows the port contract the executor relies on: inputs are built from
 * the applied SELECT and previous step and fixed at the first presentation; each step's effect goes through the
 * real V2-01 cost port (one dispatch per effect key, re-presentation returns the stored receipt); the response is
 * stored once in V12 {@code production_stage_result}; {@link #apply} joins the caller's fenced transaction, marks the
 * receipt applied and stores the review. Tests can corrupt a provider response or inject failures.
 *
 * <p>It is not the real adapter: settlement-and-storage atomicity, reconciliation and replay are verified in the
 * safety branch and in combined verification.
 */
@SuppressWarnings("unchecked")
class ContractPlanningPort implements PlanningExecutionPort {
    static final InputVersion VERSION = new InputVersion("planning-prompt-1", "planning-rules-1", "fake-planning-1");

    private final PlanningResultMapper results;
    private final ProductionDiscoveryMapper discovery;
    private final ProductionCostPort cost;
    private final PlanningProvider provider;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Map<Long, Object> fixedInputs = new ConcurrentHashMap<>();
    final Map<ProductionStage, AtomicInteger> generations = new ConcurrentHashMap<>();
    volatile Map<ProductionStage, UnaryOperator<Object>> corrupt = Map.of();
    volatile Consumer<StepOwner> beforeResponse = owner -> {};
    volatile Consumer<Long> beforeApply = operation -> {};

    ContractPlanningPort(PlanningResultMapper results, ProductionDiscoveryMapper discovery, ProductionCostPort cost,
            PlanningProvider provider, JdbcTemplate jdbc, JsonMapper json) {
        this.results = results;
        this.discovery = discovery;
        this.cost = cost;
        this.provider = provider;
        this.jdbc = jdbc;
        this.json = json;
    }

    void reset() {
        fixedInputs.clear();
        generations.clear();
        corrupt = Map.of();
        beforeResponse = owner -> {};
        beforeApply = operation -> {};
    }

    int generated(ProductionStage stage) {
        return generations.getOrDefault(stage, new AtomicInteger()).get();
    }

    // ------------------------------------------------------------------ fixed inputs

    @Override
    public ResearchInput researchInput(long requestId) {
        return fixed(requestId, ProductionStage.RESEARCH, () -> {
            var snapshot = Objects.requireNonNull(results.snapshot(requestId), "planning snapshot");
            var batch = discovery.read(requestId);
            var generated = discovery.generated(requestId);
            var candidate = json.readValue(generated.responseJson(), Response.class).candidates()
                    .get(batch.selectedCandidateId() - 1);
            return new ResearchInput(snapshot, VERSION, batch.selectedCandidateId(), generated.responseHash(),
                    candidate.centralQuestion(), candidate.angle(), candidate.outline(), candidate.evidencePlan(),
                    candidate.assertions().stream().map(claim -> claim.assertion()).toList());
        });
    }

    @Override
    public ScriptInput scriptInput(long requestId) {
        return fixed(requestId, ProductionStage.SCRIPT, () -> {
            var research = accepted(requestId, ProductionStage.RESEARCH);
            return new ScriptInput(results.snapshot(requestId), VERSION, ref(research),
                    json.readValue(research.bodyJson(), Research.class), recent(requestId));
        });
    }

    @Override
    public ScenesInput scenesInput(long requestId) {
        return fixed(requestId, ProductionStage.SCENES, () -> {
            var script = accepted(requestId, ProductionStage.SCRIPT);
            return new ScenesInput(results.snapshot(requestId), VERSION, ref(script),
                    json.readValue(script.bodyJson(), Script.class));
        });
    }

    private <T> T fixed(long requestId, ProductionStage stage, java.util.function.Supplier<T> build) {
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = ?",
                Long.class, requestId, stage.name());
        return (T) fixedInputs.computeIfAbsent(step, id -> build.get());
    }

    private PlanningResultMapper.Applied accepted(long requestId, ProductionStage stage) {
        var applied = results.accepted(requestId, stage);
        if (applied == null || json.readValue(applied.validationJson(), Validation.class).blocked()) {
            throw new IllegalStateException("No accepted " + stage + " for request " + requestId);
        }
        return applied;
    }

    private List<Comparison> recent(long requestId) {
        List<Long> operations = jdbc.queryForList("""
                SELECT r.operation_id FROM production_request current_request
                JOIN production_request p ON p.channel_id = current_request.channel_id AND p.id <> current_request.id
                JOIN production_step s ON s.production_request_id = p.id AND s.stage = 'SCRIPT'
                JOIN production_stage_result r ON r.step_id = s.id AND r.request_id = p.id
                JOIN provider_operation o ON o.id = r.operation_id
                WHERE current_request.id = ? AND s.status = 'SUCCEEDED' AND o.applied_at IS NOT NULL
                  AND r.validation_json IS NOT NULL
                ORDER BY o.applied_at DESC, o.id DESC LIMIT 20
                """, Long.class, requestId);
        return operations.stream().map(id -> {
            var stored = results.get(id);
            Script script = json.readValue(stored.bodyJson(), Script.class);
            String hook = script.segments().stream().filter(s -> script.hookSegmentIds().contains(s.id()))
                    .map(Segment::narration).collect(Collectors.joining(" "));
            String all = script.segments().stream().map(Segment::narration).collect(Collectors.joining(" "));
            return new Comparison(new ResultRef(id, ProductionStage.SCRIPT, stored.bodyHash()), hook,
                    script.editorialIntent(), all, script.segments().getLast().narration());
        }).toList();
    }

    private static ResultRef ref(PlanningResultMapper.Applied applied) {
        return new ResultRef(applied.operationId(), applied.stage(), applied.bodyHash());
    }

    // ------------------------------------------------------------------ effects

    @Override
    public EffectResult research(StepOwner owner, ResearchInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.RESEARCH, input, researchInput(owner.requestId()), maximumKrw,
                fixed -> provider.research((ResearchInput) fixed));
    }

    @Override
    public EffectResult script(StepOwner owner, ScriptInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.SCRIPT, input, scriptInput(owner.requestId()), maximumKrw,
                fixed -> provider.writeScript((ScriptInput) fixed));
    }

    @Override
    public EffectResult scenes(StepOwner owner, ScenesInput input, BigDecimal maximumKrw) {
        return execute(owner, ProductionStage.SCENES, input, scenesInput(owner.requestId()), maximumKrw,
                fixed -> materialize(((ScenesInput) fixed).script(), provider.planScenes((ScenesInput) fixed)));
    }

    private EffectResult execute(StepOwner owner, ProductionStage stage, Object input, Object expected,
            BigDecimal maximumKrw, Function<Object, Object> generate) {
        if (!Objects.equals(input, expected)) {
            throw new IllegalStateException("Presented input differs from the fixed input");
        }
        PlanningJson.Encoded encoded = PlanningJson.encode(expected);
        String key = EffectKey.forInput(owner.requestId(), stage, "video", 1);
        EffectResult result = cost.executeEffect(owner, key, encoded.hash(), new PricedRequest("KRW", maximumKrw,
                null));
        if (result.disposition() != Disposition.RECEIPT
                || result.receipt().outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
            return result;
        }
        long operation = result.receipt().operationId();
        if (results.get(operation) == null) {
            generations.computeIfAbsent(stage, s -> new AtomicInteger()).incrementAndGet();
            Object body = corrupt.getOrDefault(stage, UnaryOperator.identity()).apply(generate.apply(expected));
            PlanningJson.Encoded stored = PlanningJson.body(body);
            results.begin(operation, owner.requestId(), owner.stepId(), ProductionPlanning.VERSION, encoded.hash(),
                    encoded.json());
            results.saveBody(operation, stored.hash(), stored.json());
        }
        return result;
    }

    private static Scenes materialize(Script script, ScenePlan plan) {
        Map<String, String> narration = script.segments().stream()
                .collect(Collectors.toMap(Segment::id, Segment::narration));
        return new Scenes(plan.visualStyle(), plan.scenes().stream().map(draft -> {
            String words = draft.segmentIds().stream().map(id -> narration.getOrDefault(id, ""))
                    .collect(Collectors.joining(" "));
            return new Scene(draft.id(), draft.order(), draft.segmentIds(), draft.estimatedDurationMs(), words,
                    draft.visualDescription(), draft.imagePrompt(), draft.direction(), draft.transition(), words);
        }).toList());
    }

    @Override
    public CandidateResult response(StepOwner owner, Receipt receipt) {
        beforeResponse.accept(owner);
        var stored = results.get(receipt.operationId());
        if (stored == null || stored.stepId() != owner.stepId() || !PlanningJson.matches(stored.bodyJson(),
                stored.bodyHash())) {
            throw new IllegalStateException("No stored planning response for " + receipt.operationId());
        }
        ProductionStage stage = ProductionStage.valueOf(jdbc.queryForObject(
                "SELECT stage FROM production_step WHERE id = ?", String.class, stored.stepId()));
        Object body = switch (stage) {
            case RESEARCH -> json.readValue(stored.bodyJson(), Research.class);
            case SCRIPT -> json.readValue(stored.bodyJson(), Script.class);
            case SCENES -> json.readValue(stored.bodyJson(), Scenes.class);
            default -> throw new IllegalStateException();
        };
        return new CandidateResult(stage, body);
    }

    @Override
    public void apply(StepOwner owner, Receipt receipt, Validation validation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("apply must join the fenced transaction");
        }
        beforeApply.accept(receipt.operationId());
        cost.markReceiptApplied(owner, receipt.operationId());
        String encoded = PlanningJson.body(validation).json();
        if (results.saveValidation(receipt.operationId(), encoded) != 1
                && !encoded.equals(results.get(receipt.operationId()).validationJson())) {
            throw new IllegalStateException("A different review is already stored");
        }
    }
}

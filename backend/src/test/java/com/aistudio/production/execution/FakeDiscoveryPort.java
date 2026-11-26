package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.discovery.DiscoveryInputs;
import com.aistudio.production.discovery.DiscoveryResponseCodec;
import com.aistudio.production.discovery.FakeDiscoveryProvider;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product-branch stand-in for the Codex discovery persistence/cost adapter. It follows the seam contract over the
 * real V10 table: DISCOVER goes through the supplied cost port (one dispatch per effect key), the response is
 * stored once per operation, evaluation and receipt application join the executor transaction, and SELECT takes
 * the discovery row and then the channel row exclusively. The catalog is every Topic of the channel with its
 * current Content revision. Hooks let tests inject failures and catalog changes.
 */
final class FakeDiscoveryPort implements DiscoveryExecutionPort {
    private static final TypeReference<List<EvaluatedCandidate>> EVALUATIONS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ProductionCostPort cost;
    private final JsonMapper json;
    private final FakeDiscoveryProvider provider = new FakeDiscoveryProvider();

    volatile Function<Input, Response> responses = provider::generate;
    volatile String scenario = "SUCCESS";
    volatile Consumer<Long> beforeApply = id -> {};
    volatile Consumer<Long> beforeLock = id -> {};
    volatile Consumer<Long> beforeRecord = id -> {};
    final AtomicInteger generations = new AtomicInteger();
    final AtomicInteger invalidations = new AtomicInteger();

    FakeDiscoveryPort(JdbcTemplate jdbc, ProductionCostPort cost, JsonMapper json) {
        this.jdbc = jdbc;
        this.cost = cost;
        this.json = json;
    }

    void reset() {
        responses = provider::generate;
        scenario = "SUCCESS";
        beforeApply = id -> {};
        beforeLock = id -> {};
        beforeRecord = id -> {};
        generations.set(0);
        invalidations.set(0);
    }

    @Override
    public Input input(long requestId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT d.candidate_count, d.language, d.rules_version, r.channel_config_id, c.shorts_min_seconds,
                       c.shorts_max_seconds, c.content_strategy, c.target_audience, c.provider_options_json
                FROM production_discovery d JOIN production_request r ON r.id = d.production_request_id
                JOIN channel_config c ON c.id = r.channel_config_id
                WHERE d.production_request_id = ?
                """, requestId);
        List<String> prohibited = new ArrayList<>();
        Object options = row.get("provider_options_json");
        if (options != null) {
            Map<String, Object> map = json.readValue(options.toString(), new TypeReference<Map<String, Object>>() {});
            if (map.get("discovery") instanceof Map<?, ?> discovery
                    && discovery.get("prohibitedTopics") instanceof List<?> list) {
                list.forEach(item -> prohibited.add(String.valueOf(item)));
            }
        }
        return new Input(requestId, ((Number) row.get("channel_config_id")).longValue(), (String) row.get("language"),
                ((Number) row.get("candidate_count")).intValue(), ((Number) row.get("shorts_min_seconds")).intValue(),
                ((Number) row.get("shorts_max_seconds")).intValue(), (String) row.get("content_strategy"),
                (String) row.get("target_audience"), List.copyOf(prohibited),
                ((Number) row.get("rules_version")).intValue());
    }

    @Override
    public EffectResult discover(StepOwner owner, Input input) {
        String key = EffectKey.forInput(owner.requestId(), ProductionStage.DISCOVER, "video", 1);
        String hash = CanonicalJson.sha256(Map.of("discovery", DiscoveryInputs.sha256(input)));
        EffectResult result = cost.executeEffect(owner, key, hash,
                new ProductionCostPort.PricedRequest("KRW", BigDecimal.ONE, scenario));
        if (result != null && result.receipt() != null
                && result.receipt().outcome() == ProductionCostPort.Outcome.RESULT_ADOPTED) {
            store(owner.requestId(), result.receipt().operationId(), input);
        }
        return result;
    }

    /** Written once per operation, never overwritten: a replay keeps the first stored body and hash. */
    private void store(long requestId, long operationId, Input input) {
        Integer stored = jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_discovery WHERE production_request_id = ? AND response_json IS NOT NULL
                """, Integer.class, requestId);
        if (stored != null && stored > 0) {
            return;
        }
        Response response = responses.apply(input);
        generations.incrementAndGet();
        jdbc.update("""
                UPDATE production_discovery SET provider_operation_id = ?, response_json = ?, response_hash = ?,
                    generated_at = UTC_TIMESTAMP(6)
                WHERE production_request_id = ? AND response_json IS NULL
                """, operationId, DiscoveryResponseCodec.canonicalJson(response), DiscoveryResponseCodec.sha256(response),
                requestId);
    }

    @Override
    public Response response(StepOwner owner, Receipt receipt) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT response_json, response_hash FROM production_discovery
                WHERE production_request_id = ? AND provider_operation_id = ?
                """, owner.requestId(), receipt.operationId());
        Response response = DiscoveryResponseCodec.parse(row.get("response_json").toString());
        if (!DiscoveryResponseCodec.sha256(response).equals(row.get("response_hash"))) {
            throw new IllegalStateException("stored response hash mismatch");
        }
        return response;
    }

    @Override
    public List<CatalogEntry> catalog(long requestId, List<Candidate> candidates) {
        return jdbc.query("""
                SELECT t.id AS topic_id, c.id AS content_id, v.id AS revision_id, t.normalized_hash,
                       COALESCE(v.working_title, t.title) AS title, v.brief
                FROM production_request p JOIN topic t ON t.channel_id = p.channel_id
                LEFT JOIN content c ON c.topic_id = t.id
                LEFT JOIN content_revision v ON v.id = c.current_revision_id
                WHERE p.id = ? ORDER BY t.id DESC LIMIT 200
                """, (rs, n) -> new CatalogEntry(rs.getString("topic_id"), rs.getString("content_id"),
                rs.getString("revision_id"), rs.getString("normalized_hash"), rs.getString("title"),
                rs.getString("brief")), requestId);
    }

    @Override
    public void applyEvaluation(StepOwner owner, Receipt receipt, List<EvaluatedCandidate> evaluations) {
        requireTransaction();
        beforeApply.accept(owner.requestId());
        if (jdbc.update("""
                UPDATE production_discovery SET evaluation_json = ?, evaluated_at = UTC_TIMESTAMP(6),
                    version = version + 1
                WHERE production_request_id = ? AND provider_operation_id = ? AND evaluation_json IS NULL
                """, json.writeValueAsString(evaluations), owner.requestId(), receipt.operationId()) != 1) {
            throw new IllegalStateException("evaluation already stored or operation mismatch");
        }
        cost.markReceiptApplied(owner, receipt.operationId());
    }

    @Override
    public SelectionBatch lockForSelection(StepOwner owner) {
        requireTransaction();
        beforeLock.accept(owner.requestId());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT selection_mode, version, chosen_candidate_id, evaluation_json FROM production_discovery
                WHERE production_request_id = ? FOR UPDATE
                """, owner.requestId());
        jdbc.queryForList("""
                SELECT c.id FROM channel c JOIN production_request p ON p.channel_id = c.id WHERE p.id = ?
                FOR UPDATE
                """, owner.requestId());
        Object chosen = row.get("chosen_candidate_id");
        return new SelectionBatch(Mode.valueOf((String) row.get("selection_mode")),
                ((Number) row.get("version")).intValue(), chosen == null ? null : ((Number) chosen).intValue(),
                json.readValue(row.get("evaluation_json").toString(), EVALUATIONS));
    }

    @Override
    public void recordSelection(StepOwner owner, int version, int candidateId, String rationale) {
        requireTransaction();
        beforeRecord.accept(owner.requestId());
        if (jdbc.update("""
                UPDATE production_discovery SET selected_candidate_id = ?, selection_reason = ?,
                    selected_at = UTC_TIMESTAMP(6), version = version + 1
                WHERE production_request_id = ? AND version = ? AND selected_candidate_id IS NULL
                """, candidateId, rationale, owner.requestId(), version) != 1) {
            throw new IllegalStateException("stale discovery batch");
        }
    }

    @Override
    public void invalidateManualChoice(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        requireTransaction();
        invalidations.incrementAndGet();
        if (jdbc.update("""
                UPDATE production_discovery SET evaluation_json = ?, evaluated_at = UTC_TIMESTAMP(6),
                    chosen_candidate_id = NULL, chosen_by = NULL, chosen_at = NULL, choice_version = NULL,
                    version = version + 1
                WHERE production_request_id = ? AND version = ? AND selection_mode = 'MANUAL'
                """, json.writeValueAsString(evaluations), owner.requestId(), version) != 1) {
            throw new IllegalStateException("stale discovery batch");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("must join the executor transaction");
        }
    }
}

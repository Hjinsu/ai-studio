package com.aistudio.production.cost;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionStage;

@Mapper
public interface ProductionCostMapper {
    record Request(long id, long channelId, long channelConfigId, long requestedBy, String contentType,
            String pipelineVersion, BigDecimal limitKrw, String status, boolean spendingBlocked,
            boolean cancelled, String selectionHash) {}

    record Step(long id, ProductionStage stage, String targetKey, int inputGeneration, String status) {}

    record Operation(long id, long productionRequestId, long productionStepId, String requestHash,
            long reservationId, String status, String evidenceHash, String evidenceJson,
            String receiptOutcome, Long receiptEventId, boolean applied, boolean dispatched,
            String originalCurrency, BigDecimal estimateOriginal, boolean reservationOpen) {}

    String OPERATION = """
            SELECT o.id, o.production_request_id, o.production_step_id, o.request_hash, o.reservation_id,
                   o.status, o.evidence_hash, o.evidence_json, o.receipt_outcome, o.receipt_event_id,
                   o.applied_at IS NOT NULL AS applied, o.dispatched_at IS NOT NULL AS dispatched,
                   r.original_currency, r.estimate_original,
                   (r.status NOT IN ('SETTLED', 'RELEASED') OR r.reserved_remaining_krw <> 0) AS reservation_open
            FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id
            """;

    @Select("""
            SELECT id, channel_id, channel_config_id, requested_by, content_type, pipeline_version,
                   limit_krw, status, spending_blocked, cancel_requested_at IS NOT NULL AS cancelled, selection_hash
            FROM production_request WHERE id = #{id} FOR UPDATE
            """)
    Request lockRequest(long id);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM production_request WHERE id = #{id}
                AND BINARY lease_owner = BINARY #{owner} AND lease_epoch = #{epoch}
                AND lease_until > UTC_TIMESTAMP(6) AND status IN ('RUNNING', 'RECOVERY_REQUIRED'))
            """)
    boolean owns(long id, String owner, long epoch);

    @Select("""
            SELECT id, stage, target_key, input_generation, status FROM production_step
            WHERE production_request_id = #{requestId} AND id = #{id}
            """)
    Step step(long requestId, long id);

    @Select(OPERATION + " WHERE o.effect_key = #{key}")
    Operation byEffect(String key);

    @Select(OPERATION + " WHERE o.id = #{id}")
    Operation operation(long id);

    @Select("SELECT production_request_id FROM provider_operation WHERE id = #{id}")
    Long requestId(long id);

    @Select(OPERATION + """
            WHERE o.production_request_id = #{id}
              AND (o.status <> 'SETTLED' OR o.applied_at IS NULL
                   OR r.status NOT IN ('SETTLED', 'RELEASED') OR r.reserved_remaining_krw <> 0)
            ORDER BY o.id
            """)
    List<Operation> outstanding(long id);

    @Select("""
            SELECT COALESCE(SUM(r.reserved_remaining_krw + r.recognized_cost_krw), 0)
            FROM provider_operation o JOIN budget_reservation r ON o.reservation_id = r.id
            WHERE o.production_request_id = #{id}
            """)
    BigDecimal committedKrw(long id);

    @Select("SELECT spending_blocked FROM production_request WHERE id = #{id}")
    Boolean spendingBlocked(long id);

    @Select("SELECT estimate_krw FROM budget_reservation WHERE id = #{id}")
    BigDecimal estimateKrw(long id);

    @Select("""
            SELECT MIN(r.limit_krw)
            FROM budget_policy p JOIN budget_policy_revision r
                ON r.policy_id = p.id AND r.revision_no = p.current_revision_no
            WHERE p.scope = 'CONTENT_TYPE' AND p.status = 'ACTIVE' AND p.content_type = #{contentType}
              AND (p.channel_id IS NULL OR p.channel_id = #{channelId})
            """)
    BigDecimal typeLimit(long channelId, String contentType);

    @Select("""
            SELECT COUNT(*) FROM provider_operation o JOIN production_step s
                ON s.id = o.production_step_id
            WHERE o.production_request_id = #{requestId} AND s.stage = #{stage} AND s.target_key = #{target}
                AND s.input_generation < #{generation}
                AND o.status = 'SETTLED' AND o.receipt_outcome = 'NOT_EXECUTED' AND o.applied_at IS NOT NULL
            """)
    int releasedGenerations(long requestId, ProductionStage stage, String target, int generation);

    @Insert("""
            INSERT INTO provider_operation (production_request_id, production_step_id, effect_key,
                request_hash, reservation_id, status)
            VALUES (#{requestId}, #{stepId}, #{effectKey}, #{hash}, #{reservationId}, 'RESERVED')
            """)
    void reserve(long requestId, long stepId, String effectKey, String hash, long reservationId);

    @Update("""
            UPDATE provider_operation o SET o.status = 'DISPATCHED', o.dispatched_at = UTC_TIMESTAMP(6)
            WHERE o.id = #{id} AND o.status = 'RESERVED' AND o.evidence_hash IS NULL
              AND EXISTS (SELECT 1 FROM production_request r WHERE r.id = o.production_request_id
                AND BINARY r.lease_owner = BINARY #{owner} AND r.lease_epoch = #{epoch}
                AND r.lease_until > UTC_TIMESTAMP(6) AND r.cancel_requested_at IS NULL AND NOT r.spending_blocked)
            """)
    int dispatch(long id, String owner, long epoch);

    @Update("UPDATE provider_operation SET status = 'UNKNOWN' WHERE id = #{id} AND status = 'DISPATCHED'")
    void unknown(long id);

    @Update("""
            UPDATE provider_operation SET evidence_hash = #{hash}, evidence_json = #{json}
            WHERE id = #{id} AND evidence_hash IS NULL
            """)
    void saveEvidence(long id, String hash, String json);

    @Update("""
            UPDATE provider_operation SET status = 'SETTLED', receipt_outcome = #{outcome},
                receipt_event_id = #{eventId}, receipt_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND receipt_at IS NULL
            """)
    void settle(long id, String outcome, long eventId);

    @Update("""
            UPDATE provider_operation SET applied_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND status = 'SETTLED' AND applied_at IS NULL
            """)
    void apply(long id);

    @Update("UPDATE production_request SET spending_blocked = TRUE WHERE id = #{id}")
    void blockSpending(long id);
}

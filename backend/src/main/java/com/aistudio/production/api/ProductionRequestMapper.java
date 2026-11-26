package com.aistudio.production.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.production.contract.StepStatus;

@Mapper
interface ProductionRequestMapper {
    record Request(long id, long channelId, long requestedBy, String contentType, String requestHash,
            BigDecimal limitKrw, ProductionStatus status, Long contentId, Long runId, Instant cancelRequestedAt) {}

    record Step(ProductionStage stage, String targetKey, int inputGeneration, StepStatus status, int attempts) {}

    record Usage(BigDecimal reservedKrw, BigDecimal recognizedKrw) {}

    String COLUMNS = "id, channel_id, requested_by, content_type, request_hash, limit_krw, status, content_id, "
            + "run_id, cancel_requested_at";

    @Select("SELECT " + COLUMNS + " FROM production_request WHERE request_key = #{key}")
    Request byKey(String key);

    @Select("SELECT " + COLUMNS + " FROM production_request WHERE id = #{id}")
    Request byId(long id);

    @Select("SELECT " + COLUMNS + " FROM production_request WHERE id = #{id} FOR UPDATE")
    Request lock(long id);

    @Select("""
            SELECT MIN(r.limit_krw)
            FROM budget_policy p JOIN budget_policy_revision r
                ON r.policy_id = p.id AND r.revision_no = p.current_revision_no
            WHERE p.scope = 'CONTENT_TYPE' AND p.status = 'ACTIVE' AND p.content_type = #{contentType}
              AND (p.channel_id IS NULL OR p.channel_id = #{channelId})
            """)
    BigDecimal typeLimit(long channelId, String contentType);

    @Insert("""
            INSERT INTO production_request (channel_id, channel_config_id, requested_by, content_type,
                pipeline_version, request_key, request_hash, limit_krw, created_at)
            VALUES (#{channelId}, #{configId}, #{actorId}, #{contentType},
                'v2-fixed-1', #{key}, #{hash}, #{limit}, UTC_TIMESTAMP(6))
            """)
    void insert(long channelId, long configId, long actorId, String contentType,
            String key, String hash, BigDecimal limit);

    @Insert("""
            INSERT INTO production_step (production_request_id, stage) VALUES (#{id}, #{stage})
            """)
    void addStep(long id, ProductionStage stage);

    @Select("""
            SELECT stage, target_key, input_generation, status, attempts
            FROM production_step WHERE production_request_id = #{id} ORDER BY id
            """)
    List<Step> steps(long id);

    @Select("""
            SELECT COALESCE(SUM(r.reserved_remaining_krw), 0) reserved_krw,
                   COALESCE(SUM(r.recognized_cost_krw), 0) recognized_krw
            FROM provider_operation o JOIN budget_reservation r ON o.reservation_id = r.id
            WHERE o.production_request_id = #{id}
            """)
    Usage usage(long id);

    @Update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = #{id}")
    void requestCancel(long id);
}

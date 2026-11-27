package com.aistudio.production.planning;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionPlanning.Snapshot;

@Mapper
public interface PlanningResultMapper {
    record Stored(Long operationId, long requestId, long stepId, int schemaVersion, String inputHash,
            String inputJson, String bodyHash, String bodyJson, String validationJson) {}
    record Applied(Long operationId, Long workerExchangeId, ProductionStage stage, String bodyHash, String bodyJson,
            String validationJson) {}

    @Insert("""
            INSERT INTO production_stage_result
                (operation_id, request_id, step_id, schema_version, input_hash, input_json, created_at)
            VALUES (#{operationId}, #{requestId}, #{stepId}, #{version}, #{hash}, #{json}, UTC_TIMESTAMP(6))
            """)
    void begin(long operationId, long requestId, long stepId, int version, String hash, String json);

    @Insert("""
            INSERT INTO production_stage_result
                (worker_exchange_id, request_id, step_id, schema_version, input_hash, input_json,
                    body_hash, body_json, validation_json, created_at)
            VALUES (#{workerId}, #{requestId}, #{stepId}, #{version}, #{inputHash}, #{inputJson}, #{bodyHash},
                    #{body}, #{validation}, UTC_TIMESTAMP(6))
            """)
    int adoptWorker(long workerId, long requestId, long stepId, int version, String inputHash, String inputJson,
            String bodyHash, String body, String validation);

    @Select("""
            SELECT operation_id, request_id, step_id, schema_version, input_hash, input_json,
                   body_hash, body_json, validation_json
            FROM production_stage_result WHERE operation_id = #{operationId}
            """)
    Stored get(long operationId);

    @Update("""
            UPDATE production_stage_result SET body_hash = #{hash}, body_json = #{json}
            WHERE operation_id = #{operationId} AND body_hash IS NULL
            """)
    int saveBody(long operationId, String hash, String json);

    @Update("""
            UPDATE production_stage_result SET validation_json = #{json}
            WHERE operation_id = #{operationId} AND validation_json IS NULL
            """)
    int saveValidation(long operationId, String json);

    @Select("""
            SELECT r.operation_id, r.worker_exchange_id, s.stage, r.body_hash, r.body_json, r.validation_json
            FROM production_stage_result r
            LEFT JOIN provider_operation o ON o.id = r.operation_id
            LEFT JOIN production_worker_exchange w ON w.id = r.worker_exchange_id AND w.production_request_id = r.request_id
                AND w.production_step_id = r.step_id
            JOIN production_step s ON s.id = r.step_id AND s.production_request_id = r.request_id
            WHERE r.request_id = #{requestId} AND o.status = 'SETTLED'
                AND o.receipt_outcome = 'RESULT_ADOPTED' AND o.applied_at IS NOT NULL
                AND s.status IN ('SUCCEEDED', 'FAILED') AND r.validation_json IS NOT NULL
            ORDER BY s.id, r.operation_id
            """)
    List<Applied> applied(long requestId);

    @Select("""
            SELECT p.id AS request_id, p.run_id, v.id AS revision_id, v.revision_hash, p.channel_config_id,
                   p.selection_hash, v.language, c.content_strategy AS strategy, c.target_audience AS audience,
                   c.narration_style, c.visual_style, v.target_duration_seconds AS target_seconds
            FROM production_request p
            JOIN content_run r ON r.id = p.run_id AND r.content_id = p.content_id
            JOIN content_revision v ON v.id = r.content_revision_id AND v.content_id = r.content_id
            JOIN channel_config c ON c.id = p.channel_config_id AND c.channel_id = p.channel_id
            WHERE p.id = #{requestId} AND p.pipeline_version IN ('v2-planning-1', 'v2-media-1', 'v2-media-2')
            """)
    Snapshot snapshot(long requestId);

    @Select("""
            SELECT r.operation_id, r.worker_exchange_id, s.stage, r.body_hash, r.body_json, r.validation_json
            FROM production_stage_result r
            LEFT JOIN provider_operation o ON o.id = r.operation_id
            LEFT JOIN production_worker_exchange w ON w.id = r.worker_exchange_id AND w.production_request_id = r.request_id
                AND w.production_step_id = r.step_id
            JOIN production_step s ON s.id = r.step_id AND s.production_request_id = r.request_id
            WHERE r.request_id = #{requestId} AND s.stage = #{stage}
                AND s.status = 'SUCCEEDED' AND r.validation_json IS NOT NULL
                AND ((s.result_ref = CONCAT('planning:', o.id) AND o.status = 'SETTLED'
                    AND o.receipt_outcome = 'RESULT_ADOPTED' AND o.applied_at IS NOT NULL)
                 OR (s.result_ref = CONCAT('worker:', w.id) AND w.applied_at IS NOT NULL
                    AND w.result_sha256 = r.body_hash))
            """)
    Applied accepted(long requestId, ProductionStage stage);

    @Select("""
            SELECT r.operation_id, r.request_id, r.step_id, r.schema_version, r.input_hash, r.input_json,
                   r.body_hash, r.body_json, r.validation_json
            FROM production_stage_result r
            JOIN production_step s ON s.id = r.step_id AND s.production_request_id = r.request_id
            WHERE r.request_id = #{requestId} AND s.stage = #{stage} AND s.target_key = 'video'
            ORDER BY s.input_generation DESC LIMIT 1
            """)
    Stored fixedInput(long requestId, ProductionStage stage);

    @Select("SELECT stage FROM production_step WHERE production_request_id = #{requestId} AND id = #{stepId}")
    ProductionStage stage(long requestId, long stepId);

    @Select("""
            SELECT r.operation_id, r.worker_exchange_id, s.stage, r.body_hash, r.body_json, r.validation_json
            FROM production_request current_request
            JOIN production_request p ON p.channel_id = current_request.channel_id
                AND p.id != current_request.id
            JOIN production_step s ON s.production_request_id = p.id AND s.stage = 'SCRIPT'
            JOIN production_stage_result r ON r.step_id = s.id AND r.request_id = p.id
            LEFT JOIN provider_operation o ON o.id = r.operation_id
            LEFT JOIN production_worker_exchange w ON w.id = r.worker_exchange_id AND w.production_request_id = r.request_id
                AND w.production_step_id = r.step_id
            WHERE current_request.id = #{requestId} AND s.status = 'SUCCEEDED'
                AND r.validation_json IS NOT NULL
                AND ((s.result_ref = CONCAT('planning:', o.id) AND o.status = 'SETTLED'
                    AND o.receipt_outcome = 'RESULT_ADOPTED' AND o.applied_at IS NOT NULL)
                 OR (s.result_ref = CONCAT('worker:', w.id) AND w.applied_at IS NOT NULL
                    AND w.result_sha256 = r.body_hash))
            ORDER BY COALESCE(o.applied_at, w.applied_at) DESC, r.id DESC LIMIT #{limit}
            """)
    List<Applied> recentScripts(long requestId, int limit);
}

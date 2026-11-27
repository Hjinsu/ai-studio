package com.aistudio.production.execution;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionStage;

@Mapper
interface WorkerExchangeMapper {
    record Exchange(long id, ProductionStage stage, int attemptNo, long issuedEpoch, String inputJson, String inputSha256, String selectionSha256,
            String draftSha256, String resultSha256, boolean applied) {}

    @Select("""
            SELECT w.id, s.stage, w.attempt_no, w.issued_epoch, w.input_json, w.input_sha256, w.selection_sha256,
                w.draft_sha256, w.result_sha256, w.applied_at IS NOT NULL AS applied
            FROM production_worker_exchange w JOIN production_step s ON s.id = w.production_step_id
            WHERE w.production_step_id = #{owner.stepId} AND w.production_request_id = #{owner.requestId}
                AND s.attempts = w.attempt_no
            """)
    Exchange latest(@Param("owner") StepOwner owner);

    @Insert("""
            INSERT INTO production_worker_exchange (production_request_id, production_step_id,
                attempt_no, issued_epoch, schema_version, input_json, input_sha256, selection_sha256)
            SELECT r.id, s.id, s.attempts, r.lease_epoch, 1, #{inputJson}, #{inputHash}, r.selection_hash
            FROM production_request r JOIN production_step s ON s.production_request_id = r.id
            WHERE r.id = #{owner.requestId} AND s.id = #{owner.stepId}
                AND r.lease_owner = #{owner.leaseOwner} AND r.lease_epoch = #{owner.leaseEpoch}
                AND r.lease_until > UTC_TIMESTAMP(6) AND r.cancel_requested_at IS NULL
            """)
    int issue(@Param("owner") StepOwner owner, @Param("inputJson") String inputJson,
            @Param("inputHash") String inputHash);

    /** Called after stage validation, in the same request-locked transaction as domain result application. */
    @Update("""
            UPDATE production_worker_exchange w
            JOIN production_request r ON r.id = w.production_request_id
            JOIN production_step s ON s.id = w.production_step_id AND s.production_request_id = r.id
            SET w.draft_sha256 = #{draftHash}, w.result_sha256 = #{resultHash}, w.applied_at = UTC_TIMESTAMP(6)
            WHERE w.id = #{jobId} AND r.id = #{owner.requestId} AND s.id = #{owner.stepId}
                AND r.lease_owner = #{owner.leaseOwner} AND r.lease_epoch = #{owner.leaseEpoch}
                AND r.lease_until > UTC_TIMESTAMP(6) AND r.cancel_requested_at IS NULL
                AND s.status IN ('RUNNING','RECOVERY_REQUIRED') AND s.attempts = w.attempt_no
                AND w.issued_epoch = r.lease_epoch
                AND (r.selection_hash <=> w.selection_sha256) AND w.applied_at IS NULL
            """)
    int apply(@Param("owner") StepOwner owner, @Param("jobId") long jobId, @Param("draftHash") String draftHash,
            @Param("resultHash") String resultHash);
}

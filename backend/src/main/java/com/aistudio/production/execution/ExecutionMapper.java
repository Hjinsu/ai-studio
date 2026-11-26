package com.aistudio.production.execution;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.production.contract.StepStatus;

/**
 * Executor-only SQL over V9 production tables and the V6 run pointer. All times use the DB UTC clock.
 * Every state change except claim, heartbeat and release runs after {@link #lock(long)} in a fenced transaction.
 */
@Mapper
interface ExecutionMapper {
    String DISCOVERY_PIPELINE = "v2-discovery-1";

    record RequestRow(long id, long channelId, String contentType, String pipelineVersion, ProductionStatus status,
            Long contentId, Long runId, String leaseOwner, long leaseEpoch, boolean leaseValid,
            boolean cancelRequested) {

        boolean discovery() {
            return DISCOVERY_PIPELINE.equals(pipelineVersion);
        }
    }

    record StepRow(long id, ProductionStage stage, String targetKey, int inputGeneration, StepStatus status,
            int attempts, boolean retryDue) {}

    String REQUEST_COLUMNS = """
            id, channel_id, content_type, pipeline_version, status, content_id, run_id, lease_owner, lease_epoch,
            COALESCE(lease_until > UTC_TIMESTAMP(6), FALSE) AS lease_valid,
            cancel_requested_at IS NOT NULL AS cancel_requested
            """;

    // ------------------------------------------------------------------ claim (budget_control exclusive held)
    @Select("SELECT COUNT(*) FROM production_request WHERE lease_owner IS NOT NULL AND lease_until > UTC_TIMESTAMP(6)")
    int countValidLeases();

    /**
     * Oldest non-terminal request without a valid lease that has work due now. A request waits while any step has
     * a future retry_at (local retry backoff or recovery recheck); a cancel intent skips a local retry wait but not
     * a recovery recheck.
     *
     * <p>V2-02: discovery requests are only claimed when the discovery flow is wired. A MANUAL request that
     * finished DISCOVER and has no recorded choice waits for the requester without a lease and is not claimed;
     * a committed choice or cancel makes it claimable again. Recovery (status RECOVERY_REQUIRED or a step in
     * recovery) is never held back by the wait.
     */
    @Select("""
            SELECT r.id FROM production_request r
            WHERE (r.pipeline_version = 'v2-fixed-1'
                   OR (#{discovery} AND r.pipeline_version = 'v2-discovery-1'
                       AND NOT (r.cancel_requested_at IS NULL AND r.status = 'RUNNING'
                           AND EXISTS (SELECT 1 FROM production_discovery d
                               WHERE d.production_request_id = r.id AND d.selection_mode = 'MANUAL'
                                 AND d.evaluation_json IS NOT NULL
                                 AND d.chosen_candidate_id IS NULL AND d.selected_candidate_id IS NULL)
                           AND EXISTS (SELECT 1 FROM production_step s WHERE s.production_request_id = r.id
                                       AND s.stage = 'DISCOVER' AND s.status = 'SUCCEEDED')
                           AND EXISTS (SELECT 1 FROM production_step s WHERE s.production_request_id = r.id
                                       AND s.stage = 'SELECT' AND s.status = 'PENDING')
                           AND NOT EXISTS (SELECT 1 FROM production_step s WHERE s.production_request_id = r.id
                                           AND s.status IN ('RUNNING', 'RECOVERY_REQUIRED')))))
              AND r.status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
              AND (r.lease_owner IS NULL OR r.lease_until <= UTC_TIMESTAMP(6))
              AND ((r.cancel_requested_at IS NOT NULL AND r.status <> 'RECOVERY_REQUIRED')
                   OR NOT EXISTS (SELECT 1 FROM production_step s
                                  WHERE s.production_request_id = r.id AND s.retry_at > UTC_TIMESTAMP(6)))
            ORDER BY r.id
            LIMIT 1
            FOR UPDATE
            """)
    Long nextClaimable(@Param("discovery") boolean discovery);

    /** The wait condition of {@link #nextClaimable}: MANUAL, evaluated, and neither chosen nor selected yet. */
    @Select("""
            SELECT COUNT(*) > 0 FROM production_discovery
            WHERE production_request_id = #{requestId} AND selection_mode = 'MANUAL' AND evaluation_json IS NOT NULL
              AND chosen_candidate_id IS NULL AND selected_candidate_id IS NULL
            """)
    boolean awaitingManualChoice(long requestId);

    @Update("""
            UPDATE production_request
            SET lease_owner = #{owner}, lease_epoch = lease_epoch + 1,
                lease_until = UTC_TIMESTAMP(6) + INTERVAL #{leaseMicros} MICROSECOND,
                status = CASE WHEN status = 'QUEUED' THEN 'RUNNING' ELSE status END
            WHERE id = #{id} AND status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
              AND (lease_owner IS NULL OR lease_until <= UTC_TIMESTAMP(6))
            """)
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("leaseMicros") long leaseMicros);

    // ------------------------------------------------------------------ lease
    @Select("SELECT " + REQUEST_COLUMNS + " FROM production_request WHERE id = #{id} FOR UPDATE")
    RequestRow lock(long id);

    @Select("SELECT " + REQUEST_COLUMNS + " FROM production_request WHERE id = #{id}")
    RequestRow read(long id);

    @Update("""
            UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) + INTERVAL #{leaseMicros} MICROSECOND
            WHERE id = #{id} AND lease_owner = #{owner} AND lease_epoch = #{epoch}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int heartbeat(@Param("id") long id, @Param("owner") String owner, @Param("epoch") long epoch,
            @Param("leaseMicros") long leaseMicros);

    @Update("""
            UPDATE production_request SET lease_owner = NULL, lease_until = NULL
            WHERE id = #{id} AND lease_owner = #{owner} AND lease_epoch = #{epoch}
            """)
    int release(@Param("id") long id, @Param("owner") String owner, @Param("epoch") long epoch);

    // ------------------------------------------------------------------ request state (fenced)
    @Update("UPDATE production_request SET status = #{status} WHERE id = #{id} AND status = #{expected}")
    int moveRequest(@Param("id") long id, @Param("expected") ProductionStatus expected,
            @Param("status") ProductionStatus status);

    @Update("""
            UPDATE production_request SET status = #{status}, lease_owner = NULL, lease_until = NULL
            WHERE id = #{id} AND status IN ('RUNNING', 'RECOVERY_REQUIRED')
            """)
    int finishRequest(@Param("id") long id, @Param("status") ProductionStatus status);

    // ------------------------------------------------------------------ steps (fenced)
    @Select("""
            SELECT id, stage, target_key, input_generation, status, attempts,
                   (retry_at IS NULL OR retry_at <= UTC_TIMESTAMP(6)) AS retry_due
            FROM production_step WHERE production_request_id = #{requestId}
            ORDER BY id
            """)
    List<StepRow> steps(long requestId);

    @Update("""
            UPDATE production_step
            SET status = 'RUNNING', attempts = attempts + #{attemptIncrement}, retry_at = NULL,
                started_at = UTC_TIMESTAMP(6), finished_at = NULL
            WHERE id = #{stepId} AND production_request_id = #{requestId}
              AND status IN ('PENDING', 'RETRY_WAIT', 'RUNNING', 'RECOVERY_REQUIRED')
              AND attempts + #{attemptIncrement} <= 3
            """)
    int startStep(@Param("requestId") long requestId, @Param("stepId") long stepId,
            @Param("attemptIncrement") int attemptIncrement);

    @Update("""
            UPDATE production_step
            SET status = 'SUCCEEDED', result_ref = #{resultRef}, last_error_code = NULL, retry_at = NULL,
                finished_at = UTC_TIMESTAMP(6)
            WHERE id = #{stepId} AND production_request_id = #{requestId} AND status = 'RUNNING'
            """)
    int succeedStep(@Param("requestId") long requestId, @Param("stepId") long stepId,
            @Param("resultRef") String resultRef);

    @Update("""
            UPDATE production_step
            SET status = #{status}, last_error_code = #{code}, retry_at = NULL, finished_at = UTC_TIMESTAMP(6)
            WHERE id = #{stepId} AND production_request_id = #{requestId}
              AND status IN ('RUNNING', 'RECOVERY_REQUIRED')
            """)
    int endStep(@Param("requestId") long requestId, @Param("stepId") long stepId,
            @Param("status") StepStatus status, @Param("code") String code);

    /** RETRY_WAIT (local retry) or RECOVERY_REQUIRED (effect recheck) with a DB-clock due time. */
    @Update("""
            UPDATE production_step
            SET status = #{status}, last_error_code = #{code},
                retry_at = UTC_TIMESTAMP(6) + INTERVAL #{delayMicros} MICROSECOND, finished_at = NULL
            WHERE id = #{stepId} AND production_request_id = #{requestId} AND status = 'RUNNING'
            """)
    int waitStep(@Param("requestId") long requestId, @Param("stepId") long stepId,
            @Param("status") StepStatus status, @Param("code") String code, @Param("delayMicros") long delayMicros);

    /**
     * V2-02 MANUAL wait: a SELECT in local retry wait goes back to PENDING without a retry time or error code.
     * Attempts are kept. A PENDING SELECT is left unchanged.
     */
    @Update("""
            UPDATE production_step SET status = 'PENDING', retry_at = NULL, last_error_code = NULL, finished_at = NULL
            WHERE id = #{stepId} AND production_request_id = #{requestId} AND stage = 'SELECT'
              AND status = 'RETRY_WAIT'
            """)
    int awaitChoice(@Param("requestId") long requestId, @Param("stepId") long stepId);

    /** Recheck time for a request whose completion is blocked by cost state; status of the step is unchanged. */
    @Update("""
            UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) + INTERVAL #{delayMicros} MICROSECOND
            WHERE id = #{stepId} AND production_request_id = #{requestId}
            """)
    int scheduleRecheck(@Param("requestId") long requestId, @Param("stepId") long stepId,
            @Param("delayMicros") long delayMicros);

    @Update("""
            UPDATE production_step SET status = 'CANCELLED', retry_at = NULL, finished_at = UTC_TIMESTAMP(6)
            WHERE production_request_id = #{requestId} AND status IN ('PENDING', 'RETRY_WAIT', 'RUNNING')
            """)
    int cancelOpenSteps(long requestId);

    @Update("UPDATE production_step SET retry_at = NULL WHERE production_request_id = #{requestId}")
    int clearRechecks(long requestId);

    // ------------------------------------------------------------------ V6 run (fenced, after the request lock)
    @Update("""
            UPDATE content_run SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6), version = version + 1
            WHERE id = #{runId} AND content_id = #{contentId} AND status = 'QUEUED'
            """)
    int startRun(@Param("contentId") long contentId, @Param("runId") long runId);

    /** R10: release the active pointer before the run becomes terminal, in the same transaction. */
    @Update("""
            UPDATE content SET status = 'DRAFT', active_run_id = NULL, version = version + 1, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{contentId} AND active_run_id = #{runId}
            """)
    int releaseActiveRun(@Param("contentId") long contentId, @Param("runId") long runId);

    @Update("""
            UPDATE content_run
            SET status = #{status}, version = version + 1, finished_at = UTC_TIMESTAMP(6),
                started_at = CASE WHEN #{status} = 'CANCELLED' THEN started_at
                                  ELSE COALESCE(started_at, UTC_TIMESTAMP(6)) END
            WHERE id = #{runId} AND content_id = #{contentId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int finishRun(@Param("contentId") long contentId, @Param("runId") long runId, @Param("status") String status);
}

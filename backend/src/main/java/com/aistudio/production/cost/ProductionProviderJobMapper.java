package com.aistudio.production.cost;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionCostPort.StepOwner;

@Mapper
interface ProductionProviderJobMapper {
    record Job(long operationId, String providerNamespace, String providerJobId, Instant submittedAt,
            String submissionSha256, Instant nextPollAt) {}

    @Select("""
            SELECT operation_id, provider_namespace, provider_job_id, submitted_at, submission_sha256, next_poll_at
            FROM production_provider_job WHERE operation_id = #{operationId}
            """)
    Job find(@Param("operationId") long operationId);

    /** The designated submission evidence is immutable and belongs to exactly one committed operation. */
    @Insert("""
            INSERT INTO production_provider_job (operation_id, provider_namespace, provider_job_id,
                submitted_at, submission_sha256, next_poll_at)
            VALUES (#{operationId}, #{providerNamespace}, #{providerJobId}, #{submittedAt},
                #{submissionSha256}, #{nextPollAt})
            """)
    int insert(Job job);

    @Update("""
            UPDATE production_provider_job j
            JOIN provider_operation o ON o.id = j.operation_id
            JOIN production_request r ON r.id = o.production_request_id
            SET j.next_poll_at = #{due}
            WHERE j.operation_id = #{operationId} AND o.production_request_id = #{owner.requestId}
                AND o.production_step_id = #{owner.stepId} AND o.status IN ('DISPATCHED','UNKNOWN')
                AND r.lease_owner = #{owner.leaseOwner} AND r.lease_epoch = #{owner.leaseEpoch}
                AND r.lease_until > UTC_TIMESTAMP(6)
            """)
    int defer(@Param("owner") StepOwner owner, @Param("operationId") long operationId, @Param("due") Instant due);
}

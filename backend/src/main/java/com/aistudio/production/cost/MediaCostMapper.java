package com.aistudio.production.cost;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionStage;

@Mapper
interface MediaCostMapper {
    record Item(long id, String targetKey, int generation, ProductionStage kind, String inputJson,
            String inputSha256, String state, long leaseEpoch, int attemptNo, Long operationId,
            String executionProofJson, String executionProofSha256, boolean expired, Long contentId, Long runId,
            long channelId) {}

    record Asset(Role role, String relativePath, long byteLength, String sha256) {}

    @Select("""
            SELECT i.id, i.target_key, i.generation, i.kind, i.input_json, i.input_sha256, i.state,
                   i.lease_epoch, i.attempt_no, i.operation_id, i.execution_proof_json, i.execution_proof_sha256,
                   COALESCE(i.recovery_deadline_at <= UTC_TIMESTAMP(6), FALSE) AS expired,
                   r.content_id, r.run_id, r.channel_id
            FROM production_media_item i JOIN production_request r ON r.id = i.production_request_id
            WHERE r.id = #{requestId} AND i.production_step_id = #{stepId} AND i.id = #{itemId}
                  AND i.kind IN ('IMAGE', 'TTS')
            FOR UPDATE
            """)
    Item lock(long requestId, long stepId, long itemId);

    @Select("SELECT id FROM production_media_item WHERE operation_id = #{id}")
    Long byOperation(long id);

    @Update("""
            UPDATE production_media_item SET operation_id = #{operationId},
                lease_epoch = #{epoch}, attempt_no = 1, state = 'ACTIVE', updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND operation_id IS NULL AND state = 'PENDING'
            """)
    int bind(long id, long operationId, long epoch);

    @Update("""
            UPDATE production_media_item SET execution_proof_json = #{json}, execution_proof_sha256 = #{hash},
                recovery_deadline_at = UTC_TIMESTAMP(6) + INTERVAL 24 HOUR, state = 'PROVEN',
                updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND execution_proof_json IS NULL AND state = 'ACTIVE'
            """)
    int prove(long id, String json, String hash);

    @Select("""
            SELECT role, relative_path, byte_length, sha256 FROM production_media_asset
            WHERE item_id = #{itemId} AND attempt_no = 1 AND state IN ('STAGED', 'FINAL')
            ORDER BY role
            """)
    List<Asset> assets(long itemId);

    @Insert("""
            INSERT INTO production_media_asset(item_id, role, attempt_no, writer_epoch, relative_path,
                    mime_type, byte_length, sha256, lineage_json, rights_json, state, created_at)
            VALUES(#{itemId}, #{role}, 1, #{epoch}, #{path},
                    #{mime}, #{bytes}, #{hash}, #{lineage}, #{rights}, 'STAGED', UTC_TIMESTAMP(6))
            """)
    int stage(long itemId, Role role, long epoch, String path, String mime, long bytes, String hash,
            String lineage, String rights);

    @Update("""
            UPDATE production_media_asset SET state = 'FINAL', finalized_at = UTC_TIMESTAMP(6)
            WHERE item_id = #{itemId} AND attempt_no = 1 AND state = 'STAGED'
            """)
    int finalizeAssets(long itemId);

    @Update("UPDATE production_media_asset SET state = 'ABANDONED' WHERE item_id = #{itemId} AND state = 'STAGED'")
    int abandon(long itemId);

    @Update("""
            UPDATE production_media_item SET state = #{state}, updated_at = UTC_TIMESTAMP(6), lease_epoch = #{epoch}
            WHERE id = #{id} AND state IN ('ACTIVE', 'PROVEN', 'FINALIZED')
            """)
    int transition(long id, String state, long epoch);
}

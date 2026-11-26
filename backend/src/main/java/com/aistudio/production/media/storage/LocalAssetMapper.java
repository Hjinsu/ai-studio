package com.aistudio.production.media.storage;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.MediaStore.Role;

@Mapper
interface LocalAssetMapper {
    record Lease(long channelId, long contentId, long runId, long epoch, int stepAttempts, boolean current,
            boolean cancelled) {}
    record Asset(long id, long itemId, int attemptNo, Role role, String mimeType, String relativePath,
            long byteLength, String sha256, String state) {}

    @Select("""
            SELECT r.channel_id, r.content_id, r.run_id, r.lease_epoch AS epoch, s.attempts AS step_attempts,
                   (r.lease_epoch = #{epoch} AND BINARY r.lease_owner = BINARY #{owner}
                       AND r.lease_until > UTC_TIMESTAMP(6) AND s.status = 'RUNNING') AS current,
                   r.cancel_requested_at IS NOT NULL AS cancelled
            FROM production_request r JOIN production_step s ON s.production_request_id = r.id
            WHERE r.id = #{requestId} AND s.id = #{stepId} AND r.pipeline_version = 'v2-media-1'
              AND r.run_id IS NOT NULL
            FOR UPDATE
            """)
    Lease lock(long requestId, long stepId, String owner, long epoch);

    @Select("""
            SELECT a.id, a.item_id, a.attempt_no, a.role, a.mime_type, a.relative_path, a.byte_length, a.sha256, a.state
            FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
            WHERE i.production_request_id = #{requestId} AND i.kind = #{stage} AND a.role = #{role}
              AND i.state = 'APPLIED' AND a.attempt_no = i.attempt_no
              AND (a.state = 'FINAL' OR (#{allowCandidate} AND a.state = 'STAGED' AND a.role IN ('MP4', 'QC_REPORT', 'MANIFEST')))
            ORDER BY i.ordinal, a.id
            """)
    List<Asset> inputs(long requestId, String stage, Role role, boolean allowCandidate);

    @Select("""
            SELECT a.id, a.item_id, a.attempt_no, a.role, a.mime_type, a.relative_path, a.byte_length, a.sha256, a.state
            FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
            WHERE i.production_request_id = #{requestId} AND a.id = #{assetId}
              AND i.state = 'APPLIED' AND a.attempt_no = i.attempt_no
              AND (a.state = 'FINAL' OR (#{allowCandidate} AND a.state = 'STAGED' AND a.role IN ('MP4', 'QC_REPORT', 'MANIFEST')))
            """)
    Asset find(long requestId, long assetId, boolean allowCandidate);

    @Update("""
            UPDATE production_media_item SET state = 'ACTIVE', attempt_no = #{attempt},
                lease_epoch = #{epoch}, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{itemId} AND production_request_id = #{requestId} AND production_step_id = #{stepId}
              AND state IN ('PENDING', 'ACTIVE') AND attempt_no < #{attempt}
            """)
    int begin(long itemId, long requestId, long stepId, int attempt, long epoch);

    @Update("""
            UPDATE production_media_asset SET state = 'ABANDONED'
            WHERE item_id = #{itemId} AND attempt_no < #{attempt} AND state = 'STAGED'
            """)
    int abandonPrevious(long itemId, int attempt);

    @Insert("""
            INSERT INTO production_media_asset(item_id, role, attempt_no, writer_epoch, relative_path,
                    mime_type, byte_length, sha256, lineage_json, rights_json, state, created_at)
            VALUES(#{itemId}, #{role}, #{attempt}, #{epoch}, #{path},
                    #{mime}, #{bytes}, #{hash}, #{lineage}, #{rights}, 'STAGED', UTC_TIMESTAMP(6))
            """)
    int stage(long itemId, int attempt, Role role, long epoch, String path, String mime, long bytes, String hash,
            String lineage, String rights);

    @Update("""
            UPDATE production_media_asset
            SET state = 'FINAL', finalized_at = UTC_TIMESTAMP(6)
            WHERE item_id = #{itemId} AND attempt_no = #{attempt} AND state = 'STAGED'
            """)
    int finalizeAssets(long itemId, int attempt);

    @Update("""
            UPDATE production_media_item SET state = 'APPLIED', updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{itemId} AND attempt_no = #{attempt} AND lease_epoch = #{epoch} AND state = 'ACTIVE'
            """)
    int apply(long itemId, int attempt, long epoch);

    @Update("""
            UPDATE production_media_asset a JOIN production_media_item i ON i.id = a.item_id
            SET a.state = 'FINAL', a.finalized_at = UTC_TIMESTAMP(6)
            WHERE i.production_request_id = #{requestId} AND i.kind = 'RENDER' AND i.state = 'APPLIED'
              AND a.attempt_no = i.attempt_no AND a.role = 'MP4' AND a.state = 'STAGED'
            """)
    int finalizeVideo(long requestId);
}

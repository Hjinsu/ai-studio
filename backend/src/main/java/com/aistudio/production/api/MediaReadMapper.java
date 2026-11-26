package com.aistudio.production.api;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionStage;

@Mapper
interface MediaReadMapper {
    record Asset(long id, long itemId, int attemptNo, ProductionStage stage, Role role, String mimeType,
            long byteLength, String sha256, String relativePath) {}

    String PUBLIC = """
            SELECT a.id, a.item_id, a.attempt_no, i.kind AS stage, a.role, a.mime_type,
                   a.byte_length, a.sha256, a.relative_path
            FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
            WHERE i.production_request_id = #{requestId} AND i.state = 'APPLIED' AND a.state = 'FINAL' AND a.attempt_no = i.attempt_no
              AND a.role IN ('NORMALIZED_IMAGE', 'PCM_AUDIO', 'SRT', 'MP4', 'QC_REPORT', 'MANIFEST')
              AND (a.role NOT IN ('MP4', 'QC_REPORT', 'MANIFEST') OR EXISTS (
                  SELECT 1 FROM production_step q
                  WHERE q.production_request_id = i.production_request_id AND q.stage = 'QC'
                    AND q.status = 'SUCCEEDED'))
            """;

    @Select(PUBLIC + " ORDER BY i.production_step_id, i.ordinal, a.id")
    List<Asset> list(long requestId);

    @Select(PUBLIC + " AND a.id = #{assetId}")
    Asset find(long requestId, long assetId);

    @Select("SELECT status FROM production_step WHERE production_request_id = #{requestId} AND stage = 'QC'")
    String qcStatus(long requestId);
}

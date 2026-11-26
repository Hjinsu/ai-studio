package com.aistudio.production.media.storage;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionStage;

@Mapper
public interface MediaWorkMapper {
    record Item(long id, ProductionStage stage, int ordinal, String inputJson, String inputSha256, String state,
            Long operationId, int attemptNo) {}

    @Select("""
            SELECT i.id, s.stage, i.ordinal, i.input_json, i.input_sha256, i.state, i.operation_id, i.attempt_no
            FROM production_media_item i JOIN production_step s ON s.id = i.production_step_id
            WHERE i.production_request_id = #{requestId} AND s.stage = #{stage} AND i.kind <> 'PROFILE'
            ORDER BY i.ordinal
            """)
    List<Item> items(long requestId, ProductionStage stage);

    @Insert("""
            INSERT INTO production_media_item (production_request_id, production_step_id, target_key, generation,
                ordinal, kind, input_json, input_sha256, state, lease_epoch, created_at, updated_at)
            SELECT s.production_request_id, s.id, #{target}, s.input_generation,
                #{ordinal}, s.stage, #{input.json}, #{input.hash}, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
            FROM production_step s WHERE s.production_request_id = #{requestId} AND s.stage = #{stage}
            """)
    int insert(long requestId, ProductionStage stage, String target, int ordinal, PlanningJson.Encoded input);
}

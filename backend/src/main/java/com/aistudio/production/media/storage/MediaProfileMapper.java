package com.aistudio.production.media.storage;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aistudio.production.contract.PlanningJson;

@Mapper
public interface MediaProfileMapper {
    @Insert("""
            INSERT INTO production_media_item
                (production_request_id, production_step_id, target_key, generation, ordinal, kind,
                 input_json, input_sha256, state, lease_epoch, created_at, updated_at)
            SELECT production_request_id, id, 'PROFILE', 1, 0, 'PROFILE',
                   #{snapshot.json}, #{snapshot.hash}, 'APPLIED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
            FROM production_step
            WHERE production_request_id = #{requestId} AND stage = 'IMAGE'
              AND target_key = 'video' AND input_generation = 1
            """)
    int insert(@Param("requestId") long requestId, @Param("snapshot") PlanningJson.Encoded snapshot);

    @Select("""
            SELECT input_json AS json, input_sha256 AS hash FROM production_media_item
            WHERE production_request_id = #{requestId} AND kind = 'PROFILE'
            """)
    PlanningJson.Encoded find(long requestId);
}

package com.aistudio.production.selection;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionSelectionPort.Selected;

@Mapper
interface ProductionSelectionMapper {
    @Insert("""
            INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version,
                execution_mode, status, requested_by, created_at)
            VALUES (#{contentId}, #{revisionId}, 1, #{pipeline}, 'FAKE', 'QUEUED', #{actor}, UTC_TIMESTAMP(6))
            """)
    void insertRun(long contentId, long revisionId, String pipeline, long actor);

    @Select("SELECT id FROM content_run WHERE content_id = #{contentId} AND run_no = 1")
    long runId(long contentId);

    @Update("""
            UPDATE content SET status = 'PRODUCING', active_run_id = #{runId},
                version = version + 1, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{contentId} AND active_run_id IS NULL
            """)
    int activate(long contentId, long runId);

    @Update("""
            UPDATE production_request SET content_id = #{contentId}, run_id = #{runId}, selection_hash = #{hash}
            WHERE id = #{requestId} AND selection_hash IS NULL
            """)
    int link(long requestId, long contentId, long runId, String hash);

    @Select("""
            SELECT c.topic_id, c.id AS content_id, r.content_revision_id AS revision_id, r.id AS run_id
            FROM production_request p JOIN content c ON c.id = p.content_id
            JOIN content_run r ON r.content_id = c.id AND r.id = p.run_id
            WHERE p.id = #{id}
            """)
    Selected selected(long id);
}

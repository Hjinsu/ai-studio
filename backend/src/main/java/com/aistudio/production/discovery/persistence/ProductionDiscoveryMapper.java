package com.aistudio.production.discovery.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aistudio.production.contract.ProductionDiscovery.Mode;

@Mapper
public interface ProductionDiscoveryMapper {
    record Batch(Mode selectionMode, int candidateCount, int version, String evaluationJson,
            Integer chosenCandidateId, Integer choiceVersion, Integer selectedCandidateId, String selectionReason) {}

    record Snapshot(long channelId, long channelConfigId, String language, int candidateCount, int rulesVersion) {}

    record Generated(long operationId, String responseJson, String responseHash) {}

    @Select("""
            SELECT r.channel_id, r.channel_config_id, d.language, d.candidate_count, d.rules_version
            FROM production_discovery d JOIN production_request r ON r.id = d.production_request_id
            WHERE r.id = #{id}
            """)
    Snapshot snapshot(long id);

    @Select("""
            SELECT provider_operation_id AS operation_id, response_json, response_hash
            FROM production_discovery WHERE production_request_id = #{id} AND response_json IS NOT NULL
            """)
    Generated generated(long id);

    @Update("""
            UPDATE production_discovery SET provider_operation_id = #{operationId}, response_json = #{body},
                response_hash = #{hash}, generated_at = UTC_TIMESTAMP(6)
            WHERE production_request_id = #{id} AND provider_operation_id IS NULL
            """)
    int saveResponse(long id, long operationId, String body, String hash);

    @Update("""
            UPDATE production_discovery SET evaluation_json = #{evaluation}, evaluated_at = UTC_TIMESTAMP(6),
                version = version + 1
            WHERE production_request_id = #{id} AND evaluation_json IS NULL
              AND provider_operation_id = #{operationId}
            """)
    int evaluate(long id, long operationId, String evaluation);

    @Update("""
            UPDATE production_discovery SET selected_candidate_id = #{candidateId}, selection_reason = #{reason},
                selected_at = UTC_TIMESTAMP(6), version = version + 1,
                rechecked_at = IF(#{evaluation} IS NULL, rechecked_at, UTC_TIMESTAMP(6)),
                recheck_json = COALESCE(#{evaluation}, recheck_json)
            WHERE production_request_id = #{id} AND version = #{version} AND selected_candidate_id IS NULL
              AND (selection_mode = 'AUTO' OR chosen_candidate_id = #{candidateId})
              AND EXISTS (
                  SELECT 1 FROM production_request p JOIN content c ON c.id = p.content_id
                  JOIN content_revision r ON r.id = c.current_revision_id
                  WHERE p.id = #{id} AND p.run_id IS NOT NULL AND BINARY r.working_title = BINARY #{title}
              )
            """)
    int selectCandidate(long id, int version, int candidateId, String reason, String title, String evaluation);

    @Update("""
            UPDATE production_discovery SET recheck_json = #{evaluation}, rechecked_at = UTC_TIMESTAMP(6),
                version = version + 1, chosen_candidate_id = NULL, choice_version = NULL,
                chosen_by = NULL, chosen_at = NULL
            WHERE production_request_id = #{id} AND version = #{version} AND selection_mode = 'MANUAL'
              AND chosen_candidate_id IS NOT NULL AND selected_candidate_id IS NULL
            """)
    int invalidateChoice(long id, int version, String evaluation);

    @Update("""
            UPDATE production_discovery SET recheck_json = #{evaluation}, rechecked_at = UTC_TIMESTAMP(6),
                version = version + 1
            WHERE production_request_id = #{id} AND version = #{version} AND selection_mode = 'AUTO'
              AND selected_candidate_id IS NULL
            """)
    int exhaustSelection(long id, int version, String evaluation);

    @Insert("""
            INSERT INTO production_discovery (production_request_id, selection_mode, candidate_count, language)
            VALUES (#{id}, #{mode}, #{candidateCount}, #{language})
            """)
    void initialize(long id, Mode mode, int candidateCount, String language);

    @Select("""
            SELECT selection_mode, candidate_count, version, COALESCE(recheck_json, evaluation_json) AS evaluation_json, chosen_candidate_id,
                choice_version, selected_candidate_id, selection_reason
            FROM production_discovery WHERE production_request_id = #{id}
            """)
    Batch read(long id);

    @Select("""
            SELECT COUNT(*) = 2 FROM production_step
            WHERE production_request_id = #{id}
              AND ((stage = 'DISCOVER' AND status = 'SUCCEEDED') OR (stage = 'SELECT' AND status = 'PENDING'))
            """)
    boolean awaitingSelectionStage(long id);

    @Update("""
            UPDATE production_discovery SET chosen_candidate_id = #{candidateId}, chosen_by = #{actorId},
                chosen_at = UTC_TIMESTAMP(6), choice_version = #{version}, version = version + 1
            WHERE production_request_id = #{id} AND selection_mode = 'MANUAL' AND version = #{version}
              AND chosen_candidate_id IS NULL AND selected_candidate_id IS NULL
            """)
    int choose(long id, int candidateId, int version, long actorId);
}

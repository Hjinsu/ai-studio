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

    @Insert("""
            INSERT INTO production_discovery (production_request_id, selection_mode, candidate_count, language)
            VALUES (#{id}, #{mode}, #{candidateCount}, #{language})
            """)
    void initialize(long id, Mode mode, int candidateCount, String language);

    @Select("""
            SELECT selection_mode, candidate_count, version, evaluation_json, chosen_candidate_id,
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

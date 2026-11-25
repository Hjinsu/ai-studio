package com.aistudio.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface AuditMapper {

    @Insert("""
            INSERT INTO audit_event (occurred_at, actor_type, actor_user_id, action, outcome, entity_type, entity_id,
                                     trace_id, client_ip, details_json)
            VALUES (#{occurredAt}, #{actorType}, #{actorUserId}, #{action}, #{outcome}, #{entityType}, #{entityId},
                    #{traceId}, #{clientIp}, #{detailsJson})
            """)
    void insert(AuditRecord record);
}

package com.aistudio.content.command;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
interface CommandLogMapper {

    record CommandLog(Long id, String requestKey, String requestFingerprint, String commandType, String targetType,
            long targetId, long actorUserId, String traceId, Instant createdAt) {
    }

    @Insert("""
            INSERT INTO queue_command_log (request_key, request_fingerprint, command_type, target_type, target_id,
                   actor_user_id, trace_id, created_at)
            VALUES (#{requestKey}, #{requestFingerprint}, #{commandType}, #{targetType}, #{targetId}, #{actorUserId},
                   #{traceId}, UTC_TIMESTAMP(6))
            """)
    void insert(CommandLog log);

    @Select("""
            SELECT id, request_key, request_fingerprint, command_type, target_type, target_id, actor_user_id, trace_id,
                   created_at
              FROM queue_command_log WHERE request_key = #{requestKey}
            """)
    CommandLog findByKey(String requestKey);
}

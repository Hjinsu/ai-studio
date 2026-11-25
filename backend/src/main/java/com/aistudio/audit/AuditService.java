package com.aistudio.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 이벤트 기록(append-only). 비밀번호·토큰·세션 ID는 넘기지 않는다(ADR-051).
 * 호출한 트랜잭션에 함께 참여한다.
 */
@Service
public class AuditService {

    public enum ActorType { ADMIN, CLI, SYSTEM, ANONYMOUS }

    public enum Outcome { SUCCESS, FAILURE, DENIED }

    public record Actor(ActorType type, Long userId) {

        public static Actor admin(long userId) {
            return new Actor(ActorType.ADMIN, userId);
        }

        public static Actor cli() {
            return new Actor(ActorType.CLI, null);
        }

        public static Actor system() {
            return new Actor(ActorType.SYSTEM, null);
        }

        public static Actor anonymous() {
            return new Actor(ActorType.ANONYMOUS, null);
        }
    }

    private final AuditMapper mapper;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AuditService(AuditMapper mapper, JsonMapper jsonMapper, Clock clock) {
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public void record(Actor actor, String action, Outcome outcome, String entityType, Object entityId,
            String clientIp, Map<String, ?> details) {
        Instant now = clock.instant();
        String json = details == null || details.isEmpty() ? null : jsonMapper.writeValueAsString(details);
        mapper.insert(new AuditRecord(now, actor.type().name(), actor.userId(), action, outcome.name(), entityType,
                entityId == null ? null : String.valueOf(entityId), MDC.get("traceId"), clientIp, json));
    }

    public void record(Actor actor, String action, String entityType, Object entityId, Map<String, ?> details) {
        record(actor, action, Outcome.SUCCESS, entityType, entityId, null, details);
    }
}

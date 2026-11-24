package com.aistudio.audit;

import java.time.Instant;

record AuditRecord(Instant occurredAt, String actorType, Long actorUserId, String action, String outcome,
        String entityType, String entityId, String traceId, String clientIp, String detailsJson) {
}

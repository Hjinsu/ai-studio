package com.aistudio.content;

import java.time.Instant;

/** 불변 입력 사양. channelId는 content.channel_id 복제(복합 FK용). */
public record ContentRevision(Long id, long contentId, long channelId, int revisionNo, long channelConfigId,
        Long baseRunId, String workingTitle, String brief, String language, int targetDurationSeconds,
        String settingsJson, String revisionHash, int hashVersion, String changeReason, long createdBy,
        Instant createdAt) {
}

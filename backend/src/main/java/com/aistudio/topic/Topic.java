package com.aistudio.topic;

import java.time.Instant;

/** 채널 안의 제작 소재. 불변. */
public record Topic(Long id, long channelId, String title, String normalizedTitle, String normalizedHash,
        int normalizationVersion, String note, long createdBy, Instant createdAt) {
}

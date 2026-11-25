package com.aistudio.content;

import java.time.Instant;

/** 채널·유형·topic에 고정된 제작 단위. channel_id·content_type·topic_id는 불변(DB 트리거). */
public record Content(Long id, long channelId, long topicId, String contentType, String title, String status,
        Long currentRevisionId, Long activeRunId, long version, long createdBy, Instant createdAt, Instant updatedAt,
        Instant archivedAt) {

    public static final String DRAFT = "DRAFT";
    public static final String PRODUCING = "PRODUCING";
    public static final String ARCHIVED = "ARCHIVED";

    public static final String SHORTS = "SHORTS";
    public static final String LONGFORM = "LONGFORM";
}

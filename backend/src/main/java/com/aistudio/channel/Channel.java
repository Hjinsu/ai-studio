package com.aistudio.channel;

import java.time.Instant;

public record Channel(Long id, String name, String language, String timezone, String status, long version,
        Instant createdAt, Instant updatedAt, Instant archivedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ARCHIVED = "ARCHIVED";
}

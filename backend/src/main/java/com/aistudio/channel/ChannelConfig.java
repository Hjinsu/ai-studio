package com.aistudio.channel;

import java.time.Instant;

/** 채널 설정 불변 revision (ADR-034, ADR-039). */
public record ChannelConfig(Long id, long channelId, int revisionNo, String defaultContentType, int shortsMinSeconds,
        int shortsMaxSeconds, int longformMinSeconds, int longformMaxSeconds, String contentStrategy,
        String targetAudience, String narrationStyle, String visualStyle, boolean syntheticDisclosureDefault,
        String providerOptionsJson, String changeNote, long createdBy, Instant createdAt) {
}

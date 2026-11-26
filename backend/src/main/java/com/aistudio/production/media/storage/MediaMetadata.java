package com.aistudio.production.media.storage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaMime;
import com.aistudio.production.contract.MediaStore.Role;

import tools.jackson.databind.json.JsonMapper;

public final class MediaMetadata {
    private static final List<String> RIGHTS_TEXT = List.of("source", "provider", "license", "evidenceRef",
            "verifiedBy", "verifiedAt");

    private MediaMetadata() {}

    public static void require(Payload payload, JsonMapper json) {
        long limit = payload.role() == Role.MP4 ? 100L * 1024 * 1024 : 16L * 1024 * 1024;
        if (!MediaMime.accepts(payload.role(), payload.mimeType()) || payload.byteLimit() < 1
                || payload.byteLimit() > limit || payload.lineageJson() == null || payload.rightsJson() == null
                || payload.lineageJson().length() > 32768 || payload.rightsJson().length() > 32768
                || payload.content() == null) {
            throw new IllegalArgumentException("Invalid media asset metadata");
        }
        var rights = json.readTree(payload.rightsJson());
        if (!json.readTree(payload.lineageJson()).isObject() || !rights.isObject()
                || RIGHTS_TEXT.stream().anyMatch(key -> !rights.path(key).isTextual()
                        || rights.path(key).asText().isBlank())
                || !rights.path("commercialUse").isBoolean() || !rights.path("commercialUse").asBoolean()
                || !rights.path("publish").isBoolean() || !rights.path("publish").asBoolean()
                || !rights.path("redistribution").isBoolean() || !rights.path("conditions").isTextual()) {
            throw new IllegalArgumentException("Media asset rights are incomplete");
        }
        try {
            Instant.parse(rights.path("verifiedAt").asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid rights verification timestamp", ex);
        }
    }
}

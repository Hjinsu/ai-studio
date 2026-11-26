package com.aistudio.production.contract;

import java.util.List;
import java.util.Map;

public record MediaProfile(int version, long channelConfigId, String language, String fixtureCatalog,
        String voiceId, String visualPreset, String subtitlePreset, String outputPreset,
        List<Map<String, Object>> characterProfiles) {
    public static final String PIPELINE = "v2-media-1";

    public MediaProfile {
        characterProfiles = List.copyOf(characterProfiles);
    }

    public static MediaProfile localFixture(long configId, String language, String fixtureCatalog, String voiceId) {
        return new MediaProfile(1, configId, language, fixtureCatalog, voiceId,
                "CINEMATIC_COMEDY", "SHORTS_ASS_1", "SHORTS_1080P30_1", List.of());
    }
}

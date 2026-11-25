package com.aistudio.content;

import java.util.HashMap;
import java.util.Map;

import com.aistudio.content.command.CanonicalJson;

/**
 * revision_hash v1: SHA-256(UTF-8(canonical JSON)). 대상 필드(키 사전순): baseRunId, brief, channelConfigId, contentId,
 * hashVersion, language, settings, targetDurationSeconds, workingTitle. null 명시, 정수만, 문자열은 저장값 그대로.
 */
final class RevisionHash {

    static final int VERSION = 1;

    private RevisionHash() {
    }

    record Input(long contentId, long channelConfigId, Long baseRunId, String workingTitle, String brief,
            String language, int targetDurationSeconds, Map<String, Object> settings) {
    }

    static String compute(Input input) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("baseRunId", input.baseRunId());
        fields.put("brief", input.brief());
        fields.put("channelConfigId", input.channelConfigId());
        fields.put("contentId", input.contentId());
        fields.put("hashVersion", VERSION);
        fields.put("language", input.language());
        fields.put("settings", input.settings());
        fields.put("targetDurationSeconds", input.targetDurationSeconds());
        fields.put("workingTitle", input.workingTitle());
        return CanonicalJson.sha256(fields);
    }
}

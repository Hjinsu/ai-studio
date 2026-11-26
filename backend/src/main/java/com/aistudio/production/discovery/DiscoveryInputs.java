package com.aistudio.production.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionDiscovery;
import com.aistudio.production.contract.ProductionDiscovery.Input;

/** Canonical form and checks of the snapshot-based discovery input. */
public final class DiscoveryInputs {
    private DiscoveryInputs() {}

    /** Canonical map of every input value plus the prompt and rule versions. Feeds the FAKE seed and request hash. */
    public static Map<String, Object> canonical(Input input) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("promptVersion", DiscoveryRules.PROMPT_VERSION);
        map.put("rulesVersion", input.rulesVersion());
        map.put("channelConfigId", input.channelConfigId());
        map.put("language", input.language());
        map.put("candidateCount", input.candidateCount());
        map.put("minDurationSeconds", input.minDurationSeconds());
        map.put("maxDurationSeconds", input.maxDurationSeconds());
        map.put("strategy", input.strategy());
        map.put("audience", input.audience());
        map.put("prohibitedTopics", input.prohibitedTopics() == null ? List.of() : List.copyOf(input.prohibitedTopics()));
        return map;
    }

    /** SHA-256 of the canonical input; the same request always yields the same value. */
    public static String sha256(Input input) {
        Map<String, Object> map = new LinkedHashMap<>(canonical(input));
        map.put("requestId", input.requestId());
        return CanonicalJson.sha256(map);
    }

    public static void validate(Input input) {
        if (input == null || input.requestId() < 1 || input.channelConfigId() < 1) {
            throw new IllegalArgumentException("A discovery input needs request and channel config IDs");
        }
        if (input.candidateCount() < ProductionDiscovery.MIN_CANDIDATES
                || input.candidateCount() > ProductionDiscovery.MAX_CANDIDATES) {
            throw new IllegalArgumentException("Candidate count must be between 5 and 10");
        }
        if (input.language() == null || input.language().isBlank() || input.minDurationSeconds() < 1
                || input.minDurationSeconds() > input.maxDurationSeconds()) {
            throw new IllegalArgumentException("A discovery input needs a language and a valid duration range");
        }
        if (input.rulesVersion() != DiscoveryRules.RULES_VERSION) {
            throw new IllegalArgumentException("Unsupported discovery rules version " + input.rulesVersion());
        }
    }
}

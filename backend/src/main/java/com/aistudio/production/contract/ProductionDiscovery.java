package com.aistudio.production.contract;

import java.util.List;

/** Data shared by the discovery evaluator, persistence, and the production API. */
public final class ProductionDiscovery {
    public static final String PIPELINE = "v2-discovery-1";
    public static final int MIN_CANDIDATES = 5;
    public static final int DEFAULT_CANDIDATES = 8;
    public static final int MAX_CANDIDATES = 10;

    private ProductionDiscovery() {}

    public enum Mode { AUTO, MANUAL }

    public record Input(long requestId, long channelConfigId, String language, int candidateCount,
            int minDurationSeconds, int maxDurationSeconds, String strategy, String audience,
            List<String> prohibitedTopics, int rulesVersion) {}

    public record Claim(String assertion, String evidenceId) {}

    public record EvidencePlan(String sourceType, String searchQuestion) {}

    public record Candidate(String coreTopic, String centralQuestion, String title, String angle,
            List<String> outline, List<EvidencePlan> evidencePlan, List<Claim> assertions,
            Scores suggestedScores, String rationale) {}

    public record Scores(int interest, int channelFit, int originality, int visualPotential,
            int evidencePotential, int costEfficiency) {}

    public record Response(int version, List<Candidate> candidates) {}

    public enum Block {
        DUPLICATE, SIMILARITY_UNCERTAIN, CHANNEL_PROHIBITED, UNSUPPORTED_ASSERTION
    }

    public record Comparison(String topicId, String contentId, String revisionId, String reason) {}

    public record EvaluatedCandidate(int candidateId, Candidate candidate, Scores scores, int totalScore,
            List<Block> blockCodes, List<Comparison> comparisons, String rationale) {

        public boolean eligible() {
            return blockCodes.isEmpty();
        }
    }
}

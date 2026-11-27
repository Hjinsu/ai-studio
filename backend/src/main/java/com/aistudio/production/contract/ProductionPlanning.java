package com.aistudio.production.contract;

import java.util.List;

/** Versioned inputs and results for the three fixed planning steps. */
public final class ProductionPlanning {
    public static final String PIPELINE = "v2-planning-1";

    public static boolean supportsPipeline(String version) {
        return PIPELINE.equals(version) || MediaProfile.PIPELINE.equals(version) || RealMediaProfile.PIPELINE.equals(version);
    }
    public static final int VERSION = 1;
    public static final int MAX_BODY_BYTES = 128 * 1024;
    public static final int SCRIPT_COMPARISON_LIMIT = 20;
    public static final String BLOCKED = "PLANNING_VALIDATION_BLOCKED";

    private ProductionPlanning() {}

    public enum SourceStatus { VERIFIED, UNVERIFIED, FIXTURE }
    public enum SourceOrigin { RETRIEVED, MODEL_HINT, FAKE, FIXTURE }
    public enum Relation { SUPPORTS, CONTRADICTS, INSUFFICIENT }
    public enum ClaimKind { FACT, HYPOTHESIS, SCENARIO, UNVERIFIED }
    public enum Severity { BLOCK, RECOMMENDATION }
    public enum Timing { NORMAL, ADJUSTMENT_RECOMMENDED, UNSUITABLE }

    public record Snapshot(long requestId, long runId, long revisionId, String revisionHash,
            long channelConfigId, String selectionHash, String language, String strategy,
            String audience, String narrationStyle, String visualStyle, int targetSeconds) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ResultRef(Long operationId, ProductionStage stage, String bodyHash, Long workerExchangeId) {
        public ResultRef {
            if ((operationId == null) == (workerExchangeId == null)
                    || (operationId != null && operationId <= 0)
                    || (workerExchangeId != null && workerExchangeId <= 0)) {
                throw new IllegalArgumentException("Exactly one planning result owner is required");
            }
        }

        public ResultRef(long operationId, ProductionStage stage, String bodyHash) {
            this(operationId, stage, bodyHash, null);
        }
    }
    public record InputVersion(String promptVersion, String rulesVersion, String providerVersion) {}

    public record ResearchInput(Snapshot snapshot, InputVersion version, int candidateId, String discoveryHash,
            String question, String angle, List<String> outline,
            List<ProductionDiscovery.EvidencePlan> investigationPlan, List<String> unverifiedClaims) {}

    public record Investigation(String id, String question, String requestedEvidence, String status) {}
    public record Source(String id, SourceOrigin origin, SourceStatus status, String url, String title,
            String publisher, String checkedAtUtc, String documentHash) {}
    public record EvidenceRef(String sourceId, String locator, String supportingExcerpt, Relation relation) {}
    public record Claim(String id, ClaimKind kind, String text, List<EvidenceRef> evidenceRefs, String uncertainty) {}
    public record Research(String question, String angle, List<Investigation> investigations, List<Claim> claims,
            List<Source> sources, List<String> limitations, List<String> scriptClaimIds) {}

    public record Comparison(ResultRef ref, String hook, String centralClaim, String structure, String ending) {}
    public record ScriptInput(Snapshot snapshot, InputVersion version, ResultRef researchRef, Research research,
            List<Comparison> recentScripts) {}
    public record Segment(String id, String narration, List<String> claimIds) {}
    public record Script(String language, List<Segment> segments, List<String> hookSegmentIds,
            int estimatedDurationMs, String timingRuleVersion, String editorialIntent) {}

    public record ScenesInput(Snapshot snapshot, InputVersion version, ResultRef scriptRef, Script script) {}
    public record SceneDraft(String id, int order, List<String> segmentIds, int estimatedDurationMs,
            String visualDescription, String imagePrompt, String direction, String transition) {}
    public record ScenePlan(String visualStyle, List<SceneDraft> scenes) {}
    public record Scene(String id, int order, List<String> segmentIds, int estimatedDurationMs,
            String narration, String visualDescription, String imagePrompt, String direction,
            String transition, String subtitleText) {}
    public record Scenes(String visualStyle, List<Scene> scenes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            DynamicScenePlan dynamicPlan) {
        public Scenes(String visualStyle, List<Scene> scenes) {
            this(visualStyle, scenes, null);
        }
    }

    public record Finding(Severity severity, String code, String location, List<String> references, String correction) {}
    public record Validation(List<Finding> findings) {
        public Validation {
            findings = List.copyOf(findings);
        }

        public boolean blocked() {
            return findings.stream().anyMatch(finding -> finding.severity() == Severity.BLOCK);
        }
    }

    public static Timing timing(int durationMs) {
        if (durationMs < 35_000 || durationMs > 65_000) {
            return Timing.UNSUITABLE;
        }
        if (durationMs < 40_000 || durationMs > 60_000) {
            return Timing.ADJUSTMENT_RECOMMENDED;
        }
        return Timing.NORMAL;
    }
}

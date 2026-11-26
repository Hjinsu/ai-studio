package com.aistudio.production.discovery;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;

/**
 * Structural check of a typed provider response against the snapshot (version, exact candidate count, required
 * values, lengths, item counts, score ranges, canonical size). An invalid response is never recorded as a
 * successful discovery. Totals and eligibility are computed by the server, never taken from the response.
 */
public final class DiscoveryResponseValidator {
    /** C0/C1 controls and line separators. Candidate fields are single-line text. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    private static final Pattern SOURCE_TYPE = Pattern.compile("[A-Z][A-Z_]{1,39}");
    private static final Pattern EVIDENCE_ID = Pattern.compile("[A-Za-z0-9:_-]{1,64}");

    private DiscoveryResponseValidator() {}

    public static void validate(Input input, Response response) {
        if (input == null) {
            throw new IllegalArgumentException("A discovery input is required");
        }
        if (response == null) {
            throw new DiscoveryResponseException("response", "missing");
        }
        if (response.version() != DiscoveryRules.RESPONSE_VERSION) {
            throw new DiscoveryResponseException("version", "unsupported");
        }
        List<Candidate> candidates = response.candidates();
        if (candidates == null || candidates.size() != input.candidateCount()) {
            throw new DiscoveryResponseException("candidates", "must have exactly the requested count");
        }
        for (int i = 0; i < candidates.size(); i++) {
            candidate("candidates[" + i + "]", candidates.get(i));
        }
        int bytes = DiscoveryResponseCodec.canonicalJson(response).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DiscoveryRules.MAX_RESPONSE_BYTES) {
            throw new DiscoveryResponseException("response", "too large");
        }
    }

    private static void candidate(String path, Candidate c) {
        if (c == null) {
            throw new DiscoveryResponseException(path, "missing");
        }
        text(path + ".coreTopic", c.coreTopic(), DiscoveryRules.CORE_TOPIC_MAX);
        text(path + ".centralQuestion", c.centralQuestion(), DiscoveryRules.QUESTION_MAX);
        text(path + ".title", c.title(), DiscoveryRules.TITLE_MAX);
        text(path + ".angle", c.angle(), DiscoveryRules.ANGLE_MAX);
        text(path + ".rationale", c.rationale(), DiscoveryRules.RATIONALE_MAX);
        List<String> outline = list(path + ".outline", c.outline(), DiscoveryRules.OUTLINE_MIN,
                DiscoveryRules.OUTLINE_MAX);
        for (int i = 0; i < outline.size(); i++) {
            text(path + ".outline[" + i + "]", outline.get(i), DiscoveryRules.BEAT_MAX);
        }
        List<EvidencePlan> plans = list(path + ".evidencePlan", c.evidencePlan(), DiscoveryRules.EVIDENCE_MIN,
                DiscoveryRules.EVIDENCE_MAX);
        for (int i = 0; i < plans.size(); i++) {
            String p = path + ".evidencePlan[" + i + "]";
            EvidencePlan plan = plans.get(i);
            if (plan == null) {
                throw new DiscoveryResponseException(p, "missing");
            }
            text(p + ".sourceType", plan.sourceType(), DiscoveryRules.SOURCE_TYPE_MAX);
            if (!SOURCE_TYPE.matcher(plan.sourceType()).matches()) {
                throw new DiscoveryResponseException(p + ".sourceType", "must be an upper-case code");
            }
            text(p + ".searchQuestion", plan.searchQuestion(), DiscoveryRules.SEARCH_QUESTION_MAX);
        }
        List<Claim> claims = list(path + ".assertions", c.assertions(), 0, DiscoveryRules.ASSERTIONS_MAX);
        for (int i = 0; i < claims.size(); i++) {
            String p = path + ".assertions[" + i + "]";
            Claim claim = claims.get(i);
            if (claim == null) {
                throw new DiscoveryResponseException(p, "missing");
            }
            text(p + ".assertion", claim.assertion(), DiscoveryRules.ASSERTION_MAX);
            if (claim.evidenceId() != null && !EVIDENCE_ID.matcher(claim.evidenceId()).matches()) {
                throw new DiscoveryResponseException(p + ".evidenceId", "invalid");
            }
        }
        scores(path + ".suggestedScores", c.suggestedScores());
    }

    private static void scores(String path, Scores s) {
        if (s == null) {
            throw new DiscoveryResponseException(path, "missing");
        }
        range(path + ".interest", s.interest(), DiscoveryRules.INTEREST_MAX);
        range(path + ".channelFit", s.channelFit(), DiscoveryRules.CHANNEL_FIT_MAX);
        range(path + ".originality", s.originality(), DiscoveryRules.ORIGINALITY_MAX);
        range(path + ".visualPotential", s.visualPotential(), DiscoveryRules.VISUAL_MAX);
        range(path + ".evidencePotential", s.evidencePotential(), DiscoveryRules.EVIDENCE_POTENTIAL_MAX);
        range(path + ".costEfficiency", s.costEfficiency(), DiscoveryRules.COST_MAX);
    }

    private static void range(String path, int value, int max) {
        if (value < 0 || value > max) {
            throw new DiscoveryResponseException(path, "out of range");
        }
    }

    private static <T> List<T> list(String path, List<T> list, int min, int max) {
        if (list == null || list.size() < min || list.size() > max) {
            throw new DiscoveryResponseException(path, "item count out of range");
        }
        return list;
    }

    private static void text(String path, String value, int max) {
        if (value == null) {
            throw new DiscoveryResponseException(path, "missing");
        }
        if (CONTROL.matcher(value).find()) {
            throw new DiscoveryResponseException(path, "control characters are not allowed");
        }
        String normalized = TextSimilarity.normalized(value);
        if (normalized.isEmpty() || !value.equals(value.strip())) {
            throw new DiscoveryResponseException(path, "must not be blank or padded");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new DiscoveryResponseException(path, "too long");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isSurrogate(ch) && !(Character.isHighSurrogate(ch) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(++i)))) {
                throw new DiscoveryResponseException(path, "invalid text");
            }
        }
    }
}

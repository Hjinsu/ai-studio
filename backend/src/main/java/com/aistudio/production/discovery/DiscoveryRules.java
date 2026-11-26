package com.aistudio.production.discovery;

import java.util.List;
import java.util.Set;

import com.aistudio.production.contract.ProductionDiscovery;

/**
 * Versioned constants of the V2-02 discovery rules (prompt, response contract, evaluation). A change of any rule
 * here is a new {@link #RULES_VERSION}; stored evaluations keep the version they were made with.
 */
public final class DiscoveryRules {
    public static final int RULES_VERSION = 1;
    public static final int RESPONSE_VERSION = 1;
    public static final String PROMPT_VERSION = ProductionDiscovery.PROMPT_VERSION;

    /** UTF-8 bytes of a canonical response; matches the V10 column check. */
    public static final int MAX_RESPONSE_BYTES = 32 * 1024;
    public static final int MAX_JSON_DEPTH = 5;

    public static final int CORE_TOPIC_MAX = 120;
    public static final int QUESTION_MAX = 200;
    /** YouTube title limit; stricter than the Topic limit of 255. */
    public static final int TITLE_MAX = 100;
    public static final int ANGLE_MAX = 300;
    public static final int OUTLINE_MIN = 2;
    public static final int OUTLINE_MAX = 5;
    public static final int BEAT_MAX = 200;
    public static final int EVIDENCE_MIN = 1;
    public static final int EVIDENCE_MAX = 5;
    public static final int SOURCE_TYPE_MAX = 40;
    public static final int SEARCH_QUESTION_MAX = 200;
    public static final int ASSERTIONS_MAX = 5;
    public static final int ASSERTION_MAX = 300;
    public static final int EVIDENCE_ID_MAX = 64;
    public static final int RATIONALE_MAX = 500;

    public static final int INTEREST_MAX = 25;
    public static final int CHANNEL_FIT_MAX = 20;
    public static final int ORIGINALITY_MAX = 20;
    public static final int VISUAL_MAX = 15;
    public static final int EVIDENCE_POTENTIAL_MAX = 15;
    public static final int COST_MAX = 5;

    /** Similarity thresholds over concept sets (R02). */
    public static final double DUPLICATE_QUESTION = 0.80;
    public static final double BORDERLINE_QUESTION = 0.60;
    public static final double DISTINCT_ANGLE = 0.25;
    /** A borderline question explained with this much angle overlap is a reworded duplicate. */
    public static final double SAME_ANGLE = 0.50;

    /** Source types the server recognizes as checkable. Other values are kept but earn no evidence credit. */
    public static final Set<String> KNOWN_SOURCE_TYPES = Set.of("PEER_REVIEWED_PAPER", "SPACE_AGENCY",
            "GOVERNMENT_AGENCY", "UNIVERSITY_EXPLAINER", "TEXTBOOK", "MUSEUM", "DATASET", "ENCYCLOPEDIA");

    /** Always-prohibited topic phrases for this channel class, in addition to the channel's own list. */
    public static final List<String> BASELINE_PROHIBITED = List.of("weapon", "explosive", "medical advice",
            "self harm", "suicide", "diagnose", "drug dosage");

    private DiscoveryRules() {}
}

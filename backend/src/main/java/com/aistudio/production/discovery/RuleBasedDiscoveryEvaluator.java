package com.aistudio.production.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aistudio.production.contract.DiscoveryEvaluator;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.Comparison;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.topic.TopicNormalizer;

/**
 * Deterministic rule v1 evaluator (R01, R02). Candidate IDs are 1-based positions in the provider response.
 *
 * <ol>
 * <li>Blocks, independent of the score: {@code DUPLICATE} (same Topic hash or title, the same question with an
 * overlapping angle, or a repeat inside the batch), {@code SIMILARITY_UNCERTAIN} (borderline similarity),
 * {@code CHANNEL_PROHIBITED} (baseline or channel prohibited phrase), {@code UNSUPPORTED_ASSERTION} (a claim
 * without an approved evidence ID, definitive wording in the title, angle or outline, or a cited source in the
 * research plan).</li>
 * <li>Six criteria, each judged by four rule signals. The level (0 to 4 signals) maps to 0, 25, 50, 75 or 100% of
 * the criterion maximum, rounded down. The total of the six is at most 100. Suggested model scores are only one
 * signal and never the score itself.</li>
 * </ol>
 *
 * In V2-02 no evidence ID is approved by the server, so only questions and research plans can be adopted.
 */
@Component
public class RuleBasedDiscoveryEvaluator implements DiscoveryEvaluator {
    private static final Pattern DEFINITIVE = Pattern.compile(
            "\\b(proven|proves|proved|studies show|research shows|scientists (have )?(confirmed|proved|"
                    + "discovered|found)|it is a fact|according to|guaranteed|definitely|the truth is|"
                    + "undeniabl[ey]|confirmed that)\\b|\\bfact:|\\b\\d+(\\.\\d+)? ?(%|percent\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CITATION = Pattern.compile(
            "(https?://|www\\.|doi:|\\bdoi\\.org|\\bet al\\b|\\bverified\\b|\\bsource:|\\bjournal of\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VISUAL = Pattern.compile(
            "\\b(diagram|compar\\w*|animation|animated|chart|map|model|scale|split screen|side by side|timeline|"
                    + "show\\w*|visual\\w*|graph|demo\\w*|experiment)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REUSABLE = Pattern.compile("\\b(diagram|chart|graph|animation|timeline)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPENSIVE = Pattern.compile(
            "\\b(footage|interview|on location|drone|filming|live action|celebrity)\\b", Pattern.CASE_INSENSITIVE);

    private final Set<String> approvedEvidenceIds;

    public RuleBasedDiscoveryEvaluator() {
        this(Set.of());
    }

    /** Evidence IDs the server has approved (none before V2-03 research). */
    public RuleBasedDiscoveryEvaluator(Set<String> approvedEvidenceIds) {
        this.approvedEvidenceIds = Set.copyOf(approvedEvidenceIds);
    }

    @Override
    public List<EvaluatedCandidate> evaluate(Input input, Response response, List<CatalogEntry> catalog) {
        DiscoveryInputs.validate(input);
        DiscoveryResponseValidator.validate(input, response);
        List<CatalogEntry> entries = catalog == null ? List.of() : catalog;
        List<Candidate> candidates = response.candidates();
        List<EvaluatedCandidate> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            result.add(evaluateOne(input, i + 1, candidates.get(i), candidates.subList(0, i), entries));
        }
        return List.copyOf(result);
    }

    private EvaluatedCandidate evaluateOne(Input input, int id, Candidate c, List<Candidate> earlier,
            List<CatalogEntry> catalog) {
        Set<Block> blocks = new LinkedHashSet<>();
        List<Comparison> comparisons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        double maxSimilarity = 0;

        List<String> question = TextSimilarity.concepts(c.coreTopic() + " " + c.centralQuestion());
        List<String> angle = TextSimilarity.concepts(c.angle());
        String title = TextSimilarity.normalized(c.title());
        String titleHash = TopicNormalizer.hash(TopicNormalizer.VERSION, title);

        for (CatalogEntry entry : catalog) {
            if (titleHash.equals(entry.normalizedTopicHash())
                    || (entry.title() != null && title.equals(TextSimilarity.normalized(entry.title())))) {
                blocks.add(Block.DUPLICATE);
                comparisons.add(comparison(entry, "EXACT_TITLE"));
                maxSimilarity = 1;
                continue;
            }
            String brief = entry.brief() == null ? "" : entry.brief();
            String entryQuestion = labelled(brief, "Question:");
            String entryAngle = labelled(brief, "Angle:");
            List<String> otherQuestion = TextSimilarity.concepts(entryQuestion != null
                    ? labelledOr(brief, "Core topic:") + " " + entryQuestion
                    : nullToEmpty(entry.title()) + " " + brief);
            List<String> otherAngle = TextSimilarity.concepts(entryAngle != null ? entryAngle : brief);
            Similarity s = similarity(question, angle, otherQuestion, otherAngle);
            maxSimilarity = Math.max(maxSimilarity, s.question());
            if (s.block() != null) {
                blocks.add(s.block());
            }
            if (s.block() != null || s.question() >= 0.40) {
                comparisons.add(comparison(entry, s.describe()));
            }
        }
        for (int j = 0; j < earlier.size(); j++) {
            Candidate other = earlier.get(j);
            String ref = "BATCH_CANDIDATE_" + (j + 1);
            if (title.equals(TextSimilarity.normalized(other.title()))) {
                blocks.add(Block.DUPLICATE);
                comparisons.add(new Comparison(null, null, null, ref + " EXACT_REPEAT"));
                maxSimilarity = 1;
                continue;
            }
            Similarity s = similarity(question, angle,
                    TextSimilarity.concepts(other.coreTopic() + " " + other.centralQuestion()),
                    TextSimilarity.concepts(other.angle()));
            maxSimilarity = Math.max(maxSimilarity, s.question());
            if (s.block() != null) {
                blocks.add(s.block());
            }
            if (s.block() != null || s.question() >= 0.40) {
                comparisons.add(new Comparison(null, null, null, ref + " " + s.describe()));
            }
        }

        String prohibited = prohibitedPhrase(input, c);
        if (prohibited != null) {
            blocks.add(Block.CHANNEL_PROHIBITED);
            notes.add("prohibited phrase matched");
        }
        String unsupported = unsupported(c);
        if (unsupported != null) {
            blocks.add(Block.UNSUPPORTED_ASSERTION);
            notes.add(unsupported);
        }

        Scores scores = score(input, c, maxSimilarity);
        int total = scores.interest() + scores.channelFit() + scores.originality() + scores.visualPotential()
                + scores.evidencePotential() + scores.costEfficiency();
        String rationale = rationale(scores, total, blocks, maxSimilarity, notes);
        return new EvaluatedCandidate(id, c, scores, total, List.copyOf(blocks), List.copyOf(comparisons), rationale);
    }

    record Similarity(double question, double angle, boolean angleDistinct, Block block) {
        String describe() {
            return String.format(Locale.ROOT, "QUESTION_SIMILARITY %.2f ANGLE_OVERLAP %.2f%s", question, angle,
                    block == null ? (question >= 0.40 ? " SIMILARITY_PENALTY" : "") : " " + block.name());
        }
    }

    /** R02 rule v1 over concept sets. */
    static Similarity similarity(List<String> question, List<String> angle, List<String> otherQuestion,
            List<String> otherAngle) {
        double q = TextSimilarity.jaccard(question, otherQuestion);
        double a = TextSimilarity.jaccard(angle, otherAngle);
        // an angle that only repeats the question's own concepts does not establish a different explanation
        boolean distinct = a < DiscoveryRules.DISTINCT_ANGLE && !angle.isEmpty()
                && !TextSimilarity.coveredBy(angle, question) && !TextSimilarity.coveredBy(angle, otherAngle);
        Block block = null;
        if ((q >= DiscoveryRules.DUPLICATE_QUESTION && !distinct && a >= DiscoveryRules.DISTINCT_ANGLE)
                || (q >= DiscoveryRules.BORDERLINE_QUESTION && a >= DiscoveryRules.SAME_ANGLE)) {
            block = Block.DUPLICATE;
        } else if (q >= DiscoveryRules.BORDERLINE_QUESTION && !distinct) {
            block = Block.SIMILARITY_UNCERTAIN;
        }
        return new Similarity(q, a, distinct, block);
    }

    private static String prohibitedPhrase(Input input, Candidate c) {
        List<String> phrases = new ArrayList<>(DiscoveryRules.BASELINE_PROHIBITED);
        if (input.prohibitedTopics() != null) {
            phrases.addAll(input.prohibitedTopics());
        }
        String text = String.join(" ", c.coreTopic(), c.centralQuestion(), c.title(), c.angle(),
                String.join(" ", c.outline()));
        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && TextSimilarity.containsPhrase(text, phrase)) {
                return phrase;
            }
        }
        return null;
    }

    private String unsupported(Candidate c) {
        for (Claim claim : c.assertions()) {
            if (claim.evidenceId() == null || !approvedEvidenceIds.contains(claim.evidenceId())) {
                return "claim without approved evidence";
            }
        }
        String definitive = String.join(" ", c.title(), c.angle(), String.join(" ", c.outline()));
        if (DEFINITIVE.matcher(definitive).find()) {
            return "definitive wording";
        }
        for (EvidencePlan plan : c.evidencePlan()) {
            if (CITATION.matcher(plan.sourceType() + " " + plan.searchQuestion()).find()) {
                return "unverified source reference";
            }
        }
        if (CITATION.matcher(definitive).find()) {
            return "unverified source reference";
        }
        return null;
    }

    // ------------------------------------------------------------------ scoring (R01)

    static Scores score(Input input, Candidate c, double maxSimilarity) {
        Scores model = c.suggestedScores();
        String outline = String.join(" ", c.outline());

        int interest = level(c.centralQuestion().endsWith("?"),
                between(c.centralQuestion().length(), 20, 140),
                c.title().length() <= 70 && TextSimilarity.words(c.title()).size() >= 3,
                advised(model.interest(), DiscoveryRules.INTEREST_MAX));

        List<String> channel = TextSimilarity.concepts(nullToEmpty(input.strategy()) + " "
                + nullToEmpty(input.audience()));
        List<String> subject = TextSimilarity.concepts(c.coreTopic() + " " + c.title() + " " + c.centralQuestion()
                + " " + c.angle());
        boolean strategyMatch = channel.isEmpty() || subject.stream().anyMatch(channel::contains)
                || channel.contains("science");
        int maxBeats = Math.max(DiscoveryRules.OUTLINE_MIN, (int) Math.ceil(input.maxDurationSeconds() / 12.0));
        int channelFit = level(languageFits(input.language(), c.title() + " " + c.centralQuestion()),
                c.outline().size() <= maxBeats, strategyMatch,
                advised(model.channelFit(), DiscoveryRules.CHANNEL_FIT_MAX));

        int originalityLevel = maxSimilarity < 0.20 ? 4 : maxSimilarity < 0.35 ? 3 : maxSimilarity < 0.50 ? 2
                : maxSimilarity < DiscoveryRules.BORDERLINE_QUESTION ? 1 : 0;

        long visualBeats = c.outline().stream().filter(beat -> VISUAL.matcher(beat).find()).count();
        int visual = level(c.outline().size() >= 3, visualBeats >= 1, visualBeats >= 2,
                advised(model.visualPotential(), DiscoveryRules.VISUAL_MAX));

        long known = c.evidencePlan().stream().map(EvidencePlan::sourceType)
                .filter(DiscoveryRules.KNOWN_SOURCE_TYPES::contains).distinct().count();
        boolean specific = c.evidencePlan().stream()
                .allMatch(plan -> TextSimilarity.concepts(plan.searchQuestion()).size() >= 3);
        int evidence = level(known >= 1, c.evidencePlan().size() >= 2, known >= 2, specific);

        int cost = level(c.outline().size() <= 4, REUSABLE.matcher(outline).find(),
                !EXPENSIVE.matcher(outline + " " + c.angle()).find(),
                advised(model.costEfficiency(), DiscoveryRules.COST_MAX));

        return new Scores(points(DiscoveryRules.INTEREST_MAX, interest),
                points(DiscoveryRules.CHANNEL_FIT_MAX, channelFit),
                points(DiscoveryRules.ORIGINALITY_MAX, originalityLevel), points(DiscoveryRules.VISUAL_MAX, visual),
                points(DiscoveryRules.EVIDENCE_POTENTIAL_MAX, evidence), points(DiscoveryRules.COST_MAX, cost));
    }

    /** Level 0..4 to 0/25/50/75/100% of the maximum, rounded down. */
    static int points(int max, int level) {
        return max * level / 4;
    }

    private static int level(boolean... signals) {
        int level = 0;
        for (boolean signal : signals) {
            if (signal) {
                level++;
            }
        }
        return level;
    }

    private static boolean advised(int suggested, int max) {
        return suggested * 10 >= max * 6;
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static boolean languageFits(String language, String text) {
        if (!language.toLowerCase(Locale.ROOT).startsWith("en")) {
            return true;
        }
        long letters = text.codePoints().filter(Character::isLetter).count();
        long latin = text.codePoints().filter(cp -> Character.isLetter(cp) && cp < 0x250).count();
        return letters > 0 && latin * 100 >= letters * 95;
    }

    private static String rationale(Scores s, int total, Set<Block> blocks, double similarity, List<String> notes) {
        String text = String.format(Locale.ROOT,
                "rules v%d: total %d/100 (interest %d, channel fit %d, originality %d, visual %d, evidence %d, "
                        + "cost %d); max similarity %.2f",
                DiscoveryRules.RULES_VERSION, total, s.interest(), s.channelFit(), s.originality(),
                s.visualPotential(), s.evidencePotential(), s.costEfficiency(), similarity);
        if (!blocks.isEmpty()) {
            text += "; blocked: " + String.join(", ", blocks.stream().map(Block::name).toList());
        }
        if (!notes.isEmpty()) {
            text += " (" + String.join("; ", notes) + ")";
        }
        return text;
    }

    private static Comparison comparison(CatalogEntry entry, String reason) {
        return new Comparison(entry.topicId(), entry.contentId(), entry.revisionId(), reason);
    }

    private static String labelled(String brief, String label) {
        for (String line : brief.split("\\R")) {
            String stripped = line.strip();
            if (stripped.regionMatches(true, 0, label, 0, label.length())) {
                return stripped.substring(label.length()).strip();
            }
        }
        return null;
    }

    private static String labelledOr(String brief, String label) {
        String value = labelled(brief, label);
        return value == null ? "" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

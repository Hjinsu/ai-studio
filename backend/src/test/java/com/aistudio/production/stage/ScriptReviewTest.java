package com.aistudio.production.stage;

import static com.aistudio.production.stage.PlanningTestSupport.NEUTRON;
import static com.aistudio.production.stage.PlanningTestSupport.REVIEW;
import static com.aistudio.production.stage.PlanningTestSupport.TARDIGRADE;
import static com.aistudio.production.stage.PlanningTestSupport.VENUS;
import static com.aistudio.production.stage.PlanningTestSupport.blocks;
import static com.aistudio.production.stage.PlanningTestSupport.comparison;
import static com.aistudio.production.stage.PlanningTestSupport.recommendations;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.aistudio.production.contract.ProductionPlanning.Comparison;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;

/** SCRIPT review: required blocks versus recommendations (R03, R04), including the V2-02 P2 fixtures. */
class ScriptReviewTest {
    private final PlanningTestSupport.Run neutron = PlanningTestSupport.run(NEUTRON, List.of());
    private final Script base = neutron.script();

    private Validation review(Script script) {
        return review(script, List.of());
    }

    private Validation review(Script script, List<Comparison> recent) {
        ScriptInput input = PlanningTestSupport.scriptInput(neutron.research(), recent);
        return REVIEW.review(ProductionStage.SCRIPT, input, script);
    }

    private static Script script(List<Segment> segments, String intent) {
        return new Script("en", segments, List.of(segments.getFirst().id()), SpeechTiming.estimateMs(segments),
                SpeechTiming.RULE_VERSION, intent);
    }

    private Script replaceSegment(String id, String narration, List<String> claims) {
        List<Segment> segments = new ArrayList<>(base.segments());
        segments.replaceAll(s -> s.id().equals(id) ? new Segment(id, narration, claims) : s);
        return script(segments, base.editorialIntent());
    }

    private Script insertAfter(String id, Segment added) {
        List<Segment> segments = new ArrayList<>(base.segments());
        segments.add(segments.indexOf(segments.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow()) + 1,
                added);
        return script(segments, base.editorialIntent());
    }

    @Test
    void fixtureScriptPassesWithoutFindings() {
        assertThat(review(base).findings()).isEmpty();
    }

    // ------------------------------------------------------------------ P2: assertions outside the claim list

    @Test
    void importantFactMissingFromTheClaimListIsBlockedEvenWithoutNumbers() {
        // a superlative, a cause and a comparison stated in narration only, none of them in the structured claims
        assertThat(blocks(review(insertAfter("s2", new Segment("x1",
                "Neutron stars are the densest objects in the universe.", List.of())))))
                .containsExactly("UNSUPPORTED_ASSERTION");
        assertThat(blocks(review(insertAfter("s2", new Segment("x1",
                "Its gravity is that strong because it spins so fast.", List.of())))))
                .containsExactly("UNSUPPORTED_ASSERTION");
        assertThat(blocks(review(insertAfter("s4", new Segment("x1",
                "That cube is heavier than every mountain on Earth.", List.of())))))
                .containsExactly("UNSUPPORTED_ASSERTION");
    }

    @Test
    void causeAddedToALinkedFactSegmentIsBlocked() {
        var script = replaceSegment("s4", base.segments().get(3).narration()
                + " That is because neutrons are made of dark matter.", List.of("c3"));
        assertThat(blocks(review(script))).containsExactly("UNSUPPORTED_CAUSAL_CLAIM");
    }

    @Test
    void hypothesisStatedAsFactIsBlocked() {
        var script = replaceSegment("s6", "Deeper inside, the matter breaks down into free quarks.", List.of("c5"));
        assertThat(blocks(review(script))).containsExactly("HYPOTHESIS_AS_FACT");
        var scenario = replaceSegment("s5", "Carried home, the cube bursts apart.", List.of("c4"));
        assertThat(blocks(review(scenario))).containsExactly("HYPOTHESIS_AS_FACT");
    }

    @Test
    void numberThatIsNotInTheLinkedClaimsIsAnImportantFactError() {
        var script = replaceSegment("s3", "Now squeeze about 2 times the mass of our Sun into a ball only 20 "
                + "kilometers across.", List.of("c2"));
        Validation validation = review(script);
        assertThat(blocks(validation)).containsExactly("NUMBER_MISMATCH");
        assertThat(validation.findings().getFirst().location()).isEqualTo("script.segments[s3]");
        var words = replaceSegment("s4", "Packed that tightly, a piece the size of a sugar cube would weigh about a "
                + "trillion tons on Earth.", List.of("c3"));
        assertThat(blocks(review(words))).containsExactly("NUMBER_MISMATCH");
    }

    @Test
    void claimReferencesMustBeResearchedAndClearedForTheScript() {
        assertThat(blocks(review(replaceSegment("s2", base.segments().get(1).narration(), List.of("c1", "c6")))))
                .containsExactly("EXCLUDED_CLAIM_REFERENCE");
        assertThat(blocks(review(replaceSegment("s2", base.segments().get(1).narration(), List.of("c1", "c99")))))
                .containsExactly("UNKNOWN_CLAIM_REFERENCE");
    }

    // ------------------------------------------------------------------ structure and timing

    @Test
    void requiredStructureErrorsAreBlocked() {
        var hookNotFirst = new Script("en", base.segments(), List.of("s2"), base.estimatedDurationMs(),
                base.timingRuleVersion(), base.editorialIntent());
        assertThat(blocks(review(hookNotFirst))).containsExactly("SCRIPT_STRUCTURE");
        var language = new Script("ko", base.segments(), base.hookSegmentIds(), base.estimatedDurationMs(),
                base.timingRuleVersion(), base.editorialIntent());
        assertThat(blocks(review(language))).containsExactly("SCRIPT_LANGUAGE_MISMATCH");
        List<Segment> duplicated = new ArrayList<>(base.segments());
        duplicated.add(base.segments().get(2));
        assertThat(blocks(review(script(duplicated, base.editorialIntent())))).containsExactly("SCRIPT_STRUCTURE");
        assertThat(blocks(review(new Script("en", List.of(), List.of(), 0, SpeechTiming.RULE_VERSION, ""))))
                .containsExactly("SCRIPT_STRUCTURE");
        assertThat(review(null).blocked()).isTrue();
    }

    @Test
    void declaredLengthAndRuleVersionMustMatchTheServerEstimate() {
        var wrongEstimate = new Script("en", base.segments(), base.hookSegmentIds(), 45_000,
                base.timingRuleVersion(), base.editorialIntent());
        assertThat(base.estimatedDurationMs()).isGreaterThan(52_000);
        assertThat(blocks(review(wrongEstimate))).containsExactly("DURATION_ESTIMATE_MISMATCH");
        var wrongRule = new Script("en", base.segments(), base.hookSegmentIds(), base.estimatedDurationMs(),
                "words-per-minute-0", base.editorialIntent());
        assertThat(blocks(review(wrongRule))).containsExactly("TIMING_RULE_MISMATCH");
    }

    @ParameterizedTest
    @CsvSource({ "34999,BLOCK", "35000,RECOMMENDATION", "39999,RECOMMENDATION", "40000,NONE", "60000,NONE",
            "60001,RECOMMENDATION", "65000,RECOMMENDATION", "65001,BLOCK", "0,BLOCK", "120000,BLOCK" })
    void durationBoundariesFollowTheCommonContract(int estimateMs, String expected) {
        Findings findings = new Findings();
        ScriptReview.duration(estimateMs, findings);
        var validation = findings.validation();
        switch (expected) {
            case "BLOCK" -> assertThat(blocks(validation)).containsExactly("DURATION_UNSUITABLE");
            case "RECOMMENDATION" -> {
                assertThat(validation.blocked()).isFalse();
                assertThat(recommendations(validation)).containsExactly("DURATION_ADJUSTMENT");
            }
            default -> assertThat(validation.findings()).isEmpty();
        }
    }

    @Test
    void tooShortNarrationIsUnsuitable() {
        var shortScript = script(base.segments().subList(0, 3), base.editorialIntent());
        assertThat(blocks(review(shortScript))).contains("DURATION_UNSUITABLE");
    }

    @Test
    void minorQualityGapsAreRecommendationsAndDoNotBlock() {
        // a slow, flat hook, no ending payoff and no stated intent: all recommendations
        List<Segment> segments = new ArrayList<>(base.segments());
        segments.set(0, new Segment("s1", "This little cube on the kitchen scale looks a lot like ordinary sugar.",
                List.of()));
        segments.set(segments.size() - 1, new Segment("s7", "Follow IFNOVA.", List.of()));
        Validation validation = review(script(segments, " "));
        assertThat(validation.blocked()).isFalse();
        assertThat(recommendations(validation)).containsExactlyInAnyOrder("HOOK_TOO_LONG", "HOOK_WEAK",
                "ENDING_WEAK", "EDITORIAL_INTENT_MISSING");
    }

    @Test
    void slightlyShortScriptIsAdjustableNotBlocked() {
        var tardigrade = PlanningTestSupport.run(TARDIGRADE, List.of());
        List<Segment> segments = new ArrayList<>(tardigrade.script().segments());
        segments.removeIf(s -> s.id().equals("s4"));
        var shorter = script(segments, tardigrade.script().editorialIntent());
        var input = PlanningTestSupport.scriptInput(tardigrade.research(), List.of());
        Validation validation = REVIEW.review(ProductionStage.SCRIPT, input, shorter);
        assertThat(shorter.estimatedDurationMs()).isBetween(35_000, 39_999);
        assertThat(validation.blocked()).isFalse();
        assertThat(recommendations(validation)).containsExactlyInAnyOrder("DURATION_ADJUSTMENT", "CLAIM_UNUSED");
    }

    // ------------------------------------------------------------------ P2: substantive repetition

    /** The neutron fixture reworded: same question, same explanation, same numbers, different wording. */
    static Script paraphrase() {
        List<Segment> segments = List.of(
                new Segment("p1", "How much does this little sugar cube weigh?", List.of()),
                new Segment("p2", "Surprise, it is no sugar. It is a fragment of a neutron star, the collapsed core "
                        + "left behind when a massive star explodes as a supernova.", List.of("c1")),
                new Segment("p3", "Cram about 1.4 times the mass of our Sun into a sphere only 20 kilometers wide.",
                        List.of("c2")),
                new Segment("p4", "That tightly packed, a sugar-cube-sized piece would weigh about a billion tons "
                        + "on Earth.", List.of("c3")),
                new Segment("p5", "Yet if you brought it home, it would not stay tiny. Freed from the star's "
                        + "gravity, it would blow apart violently.", List.of("c4")),
                new Segment("p6", "Further in, nobody knows for sure what the matter turns into. Some models "
                        + "suggest it might become free quarks.", List.of("c5")),
                new Segment("p7", "A spoonful of star that only math can weigh. What would you ask it? Follow "
                        + "IFNOVA for more strange science.", List.of()));
        return script(segments, "Ask viewers to guess the weight of a sugar cube, reveal that it is neutron star "
                + "matter, then twist it with the thought experiment that the cube would blow apart outside the star.");
    }

    /** Same subject and shared facts, but a different question with its own explanation (the unknown core). */
    static Script distinctOnSameSubject() {
        List<Segment> segments = List.of(
                new Segment("i1", "What hides inside a dead star?", List.of()),
                new Segment("i2", "A neutron star is the collapsed core left behind when a massive star explodes as "
                        + "a supernova.", List.of("c1")),
                new Segment("i3", "It packs about 1.4 times the mass of our Sun into a ball about 20 kilometers "
                        + "across.", List.of("c2")),
                new Segment("i4", "So what sits at the very center? Nobody knows for sure. Some models suggest the "
                        + "matter there might dissolve into free quarks, a state no one has ever seen.",
                        List.of("c5")),
                new Segment("i5", "And if you could scoop out a sample to study, it would not even stay put. "
                        + "Without that gravity, it would burst apart.", List.of("c4")),
                new Segment("i6", "The question at the heart of these stars is still wide open. Which idea would "
                        + "you bet on? Follow IFNOVA for more strange science.", List.of()));
        return script(segments, "Explore the unknown core of a neutron star: what the competing models suggest "
                + "and why nobody can sample it.");
    }

    @Test
    void rewordedCopyOfARecentScriptIsBlockedAsSubstantiveRepeat() {
        Script copy = paraphrase();
        assertThat(review(copy).blocked()).isFalse(); // a valid script on its own
        Validation validation = review(copy, List.of(comparison(501, base)));
        assertThat(blocks(validation)).containsExactly("SUBSTANTIVE_REPEAT");
        var repeat = validation.findings().stream().filter(f -> f.code().equals("SUBSTANTIVE_REPEAT")).findFirst()
                .orElseThrow();
        assertThat(repeat.references()).containsExactly("operation:501");
    }

    @Test
    void similarScriptWithItsOwnExplanationIsAllowed() {
        Script distinct = distinctOnSameSubject();
        Validation validation = review(distinct, List.of(comparison(501, base)));
        assertThat(validation.blocked()).as(validation.toString()).isFalse();
        assertThat(blocks(validation)).isEmpty();
        // the lexical closeness is still reported, as a recommendation only
        assertThat(recommendations(validation)).contains("SIMILAR_RECENT_SCRIPT");
    }

    @Test
    void sharedChannelBrandingAloneIsNotARepeat() {
        var venus = PlanningTestSupport.run(VENUS, List.of());
        var input = PlanningTestSupport.scriptInput(venus.research(), List.of(comparison(501, base)));
        Validation validation = REVIEW.review(ProductionStage.SCRIPT, input, venus.script());
        assertThat(validation.findings()).isEmpty();
    }

    @Test
    void emptyComparisonHistoryIsAllowed() {
        assertThat(review(paraphrase(), List.of()).blocked()).isFalse();
    }
}

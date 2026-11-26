package com.aistudio.production.stage;

import static com.aistudio.production.stage.PlanningTestSupport.NEUTRON;
import static com.aistudio.production.stage.PlanningTestSupport.TARDIGRADE;
import static com.aistudio.production.stage.PlanningTestSupport.VENUS;
import static com.aistudio.production.stage.PlanningTestSupport.blocks;
import static com.aistudio.production.stage.PlanningTestSupport.recommendations;
import static com.aistudio.production.stage.PlanningTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.InputVersion;
import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.SourceOrigin;
import com.aistudio.production.contract.ProductionPlanning.SourceStatus;
import com.aistudio.production.contract.ProductionPlanning.Timing;
import com.aistudio.production.contract.ProductionStage;

/** FAKE provider plus review over the offline fixture topics: R01~R05 success paths and deterministic output. */
class FakePlanningFlowTest {

    @ParameterizedTest
    @ValueSource(strings = { NEUTRON, VENUS, TARDIGRADE })
    void fixtureTopicsPassEveryStageWithFixtureSourcesOnly(String question) {
        var run = run(question, List.of());
        assertThat(blocks(run.researchReview())).isEmpty();
        assertThat(blocks(run.scriptReview())).isEmpty();
        assertThat(blocks(run.scenesReview())).isEmpty();
        assertThat(run.research().question()).isEqualTo(question);
        assertThat(run.research().angle()).isEqualTo(run.researchInput().angle());
        assertThat(run.research().investigations()).hasSize(run.researchInput().investigationPlan().size());
        assertThat(run.research().sources()).isNotEmpty().allSatisfy(source -> {
            assertThat(source.origin()).isEqualTo(SourceOrigin.FIXTURE);
            assertThat(source.status()).isEqualTo(SourceStatus.FIXTURE).isNotEqualTo(SourceStatus.VERIFIED);
            assertThat(source.url()).contains(".invalid/");
            assertThat(source.checkedAtUtc()).isNull();
        });
        assertThat(run.research().claims()).extracting(c -> c.kind())
                .contains(ClaimKind.FACT, ClaimKind.HYPOTHESIS);
        assertThat(ProductionPlanning.timing(run.script().estimatedDurationMs())).isEqualTo(Timing.NORMAL);
        assertThat(run.script().estimatedDurationMs()).isEqualTo(SpeechTiming.estimateMs(run.script().segments()));
        assertThat(run.scenes().scenes().stream().mapToInt(Scene::estimatedDurationMs).sum())
                .isEqualTo(run.script().estimatedDurationMs());
        assertThat(run.scenes().scenes()).allSatisfy(scene -> {
            assertThat(scene.subtitleText()).isEqualTo(scene.narration());
            assertThat(scene.estimatedDurationMs()).isPositive();
        });
        assertThat(run.scenes().visualStyle()).contains("Clean infographic").contains("vertical frame").doesNotContain("9:16");
    }

    @Test
    void unsupportedOptionalClaimsAreExcludedWithoutBlocking() {
        var run = run(NEUTRON, List.of());
        assertThat(run.research().scriptClaimIds()).doesNotContain("c6");
        assertThat(recommendations(run.researchReview())).contains("OPTIONAL_CLAIM_EXCLUDED");
        assertThat(run.researchReview().blocked()).isFalse();
    }

    @Test
    void differentTopicsGetDifferentScriptStructuresAndSceneLayouts() {
        var neutron = run(NEUTRON, List.of());
        var venus = run(VENUS, List.of());
        var tardigrade = run(TARDIGRADE, List.of());
        var runs = List.of(neutron, venus, tardigrade);
        assertThat(runs.stream().map(r -> r.script().segments().size()).toList()).containsExactly(7, 6, 6);
        assertThat(runs.stream().map(r -> r.scenes().scenes().size()).toList()).containsExactly(5, 3, 6);
        // grouping of segments into scenes differs: neutron pairs mid segments, venus pairs everything,
        // tardigrade is one segment per scene
        assertThat(runs.stream().map(r -> r.scenes().scenes().stream().map(s -> s.segmentIds().size()).toList())
                .toList()).containsExactly(List.of(1, 1, 2, 1, 2), List.of(2, 2, 2), List.of(1, 1, 1, 1, 1, 1));
        assertThat(Set.copyOf(runs.stream().map(r -> r.script().segments().getFirst().narration()).toList()))
                .hasSize(3);
        assertThat(Set.copyOf(runs.stream().map(r -> r.scenes().visualStyle()).toList())).hasSize(3);
        // the hook of the tardigrade script is an imagined scenario, the others open with a question
        assertThat(tardigrade.script().segments().getFirst().claimIds()).containsExactly("c5");
        assertThat(neutron.script().segments().getFirst().narration()).endsWith("?");
        assertThat(venus.script().segments().getFirst().narration()).endsWith("?");
    }

    @Test
    void sameInputGivesTheSameCanonicalResults() {
        var first = run(VENUS, List.of());
        var second = run(VENUS, List.of());
        assertThat(ProductionPlanningJson.hash(first.research())).isEqualTo(ProductionPlanningJson.hash(second.research()));
        assertThat(first.script()).isEqualTo(second.script());
        assertThat(first.scenes()).isEqualTo(second.scenes());
        assertThat(first.scriptReview()).isEqualTo(second.scriptReview());
    }

    @Test
    void questionWithoutFixtureHasNoEvidenceAndIsBlockedBeforeScript() {
        var input = PlanningTestSupport.researchInput("Why do cats always land on their feet?");
        var research = PlanningTestSupport.PROVIDER.research(input);
        assertThat(research.sources()).isEmpty();
        assertThat(research.claims()).allSatisfy(claim -> assertThat(claim.kind()).isEqualTo(ClaimKind.UNVERIFIED));
        var review = PlanningTestSupport.REVIEW.review(ProductionStage.RESEARCH, input, research);
        assertThat(review.blocked()).isTrue();
        assertThat(blocks(review)).contains("CORE_EVIDENCE_INSUFFICIENT");
    }

    @Test
    void fixtureEvidenceIsNotAcceptedForANonFakeProviderRun() {
        var input = PlanningTestSupport.researchInput(NEUTRON, new InputVersion("p", "r", "real-provider-1"));
        var research = PlanningTestSupport.PROVIDER.research(input);
        var review = PlanningTestSupport.REVIEW.review(ProductionStage.RESEARCH, input, research);
        assertThat(blocks(review)).contains("CORE_EVIDENCE_INSUFFICIENT");
    }

    /** Canonical hash used for stored bodies. */
    static final class ProductionPlanningJson {
        static String hash(Object body) {
            return com.aistudio.production.contract.PlanningJson.body(body).hash();
        }
    }
}

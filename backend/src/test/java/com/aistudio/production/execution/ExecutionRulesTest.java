package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionCostPort.PricedRequest;
import com.aistudio.production.contract.ProductionStage;

/** Backoff, settings bounds, request hash and stage classification. */
class ExecutionRulesTest {

    @Test
    void backoffDoublesUpToTheCapWithEqualJitter() {
        assertThat(RetryBackoff.baseDelayMillis(1, 5_000, 300_000)).isEqualTo(5_000);
        assertThat(RetryBackoff.baseDelayMillis(2, 5_000, 300_000)).isEqualTo(10_000);
        assertThat(RetryBackoff.baseDelayMillis(3, 5_000, 300_000)).isEqualTo(20_000);
        assertThat(RetryBackoff.baseDelayMillis(40, 5_000, 300_000)).isEqualTo(300_000);
        assertThat(RetryBackoff.delayMillis(2, 5_000, 300_000, bound -> 0)).isEqualTo(5_000);
        assertThat(RetryBackoff.delayMillis(2, 5_000, 300_000, bound -> bound - 1)).isEqualTo(10_000);
        assertThatThrownBy(() -> RetryBackoff.baseDelayMillis(0, 5_000, 300_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAreOneWorkerTwoLeasesAndAreBounded() {
        ExecutionProperties defaults = ExecutionProperties.defaults();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.workers()).isEqualTo(1);
        assertThat(defaults.maxLeases()).isEqualTo(2);
        assertThat(defaults.retryBase()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new ExecutionProperties(true, null, 3, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProperties(true, null, null, 3, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProperties(true, null, null, null, Duration.ofMillis(10), null, null,
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProperties(true, null, null, null, null, null, null, null, null,
                new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeEffectMaximumMustBePositive() {
        assertThat(ExecutionProperties.defaults().fakeEffectMaxKrw()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(new ExecutionProperties(true, null, null, null, null, null, null, null, null,
                new BigDecimal("0.01")).fakeEffectMaxKrw()).isEqualByComparingTo("0.01");
        for (String zero : new String[] {"0", "0.00", "-0.000001"}) {
            assertThatThrownBy(() -> new ExecutionProperties(true, null, null, null, null, null, null, null, null,
                    new BigDecimal(zero))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FakeStageWork(new BigDecimal(zero)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                    new PricedRequest("KRW", new BigDecimal(zero), "SUCCESS")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new FakeStageWork(null)).isInstanceOf(IllegalArgumentException.class);
        var context = new StageWork.StepContext(7, 70, ProductionStage.SCRIPT, "video", 1, 1, 1, "SHORTS", null,
                null);
        assertThat(new FakeStageWork(BigDecimal.ONE).price(context).maximumAmount()).isPositive();
    }

    @Test
    void requestHashIsStableForEqualAmountsAndChangesWithTheRequest() {
        String base = EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                new PricedRequest("KRW", new BigDecimal("1.0"), "SUCCESS"));
        assertThat(base).matches("[0-9a-f]{64}");
        assertThat(EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                new PricedRequest("KRW", new BigDecimal("1.000000"), "SUCCESS"))).isEqualTo(base);
        assertThat(EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                new PricedRequest("KRW", new BigDecimal("2"), "SUCCESS"))).isNotEqualTo(base);
        assertThat(EffectRequestHash.of(ProductionStage.SCRIPT, "video", 2,
                new PricedRequest("KRW", BigDecimal.ONE, "SUCCESS"))).isNotEqualTo(base);
        assertThat(EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                new PricedRequest("USD", BigDecimal.ONE, "SUCCESS"))).isNotEqualTo(base);
        assertThatThrownBy(() -> EffectRequestHash.of(ProductionStage.SCRIPT, "video", 1,
                new PricedRequest(null, BigDecimal.ONE, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeWorkUsesTheSelectionPortOnlyForSelectAndIsDeterministic() {
        FakeStageWork work = new FakeStageWork(BigDecimal.ONE);
        StageWork.validate(work);
        assertThat(work.kind(ProductionStage.SELECT)).isEqualTo(StageWork.Kind.SELECT);
        assertThat(work.kind(ProductionStage.RENDER)).isEqualTo(StageWork.Kind.LOCAL);
        assertThat(work.kind(ProductionStage.DISCOVER)).isEqualTo(StageWork.Kind.EFFECT);
        var context = new StageWork.StepContext(7, 70, ProductionStage.SELECT, "video", 1, 1, 1, "SHORTS", null,
                null);
        var retried = new StageWork.StepContext(7, 70, ProductionStage.SELECT, "video", 1, 2, 1, "SHORTS", null,
                null);
        assertThat(work.select(context)).isEqualTo(work.select(retried));
        assertThat(work.select(context).fingerprint()).matches("[0-9a-f]{64}");

        StageWork broken = new FakeStageWork(BigDecimal.ONE) {
            @Override
            public Kind kind(ProductionStage stage) {
                return stage == ProductionStage.DISCOVER ? Kind.SELECT : super.kind(stage);
            }
        };
        assertThatThrownBy(() -> StageWork.validate(broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fakeSelectionLengthStaysInsideTheFrozenRangeOfEitherContentType() {
        // the earlier FAKE 45 s is kept whenever allowed, so earlier SHORTS selections replay with the same hash
        assertThat(new FakeStageWork.DurationRange(40, 60).target()).isEqualTo(45);
        assertThat(new FakeStageWork.DurationRange(45, 45).target()).isEqualTo(45);
        assertThat(new FakeStageWork.DurationRange(1, 180).target()).isEqualTo(45);
        // otherwise the midpoint, rounded down
        assertThat(new FakeStageWork.DurationRange(480, 720).target()).isEqualTo(600);
        assertThat(new FakeStageWork.DurationRange(60, 60).target()).isEqualTo(60);
        assertThat(new FakeStageWork.DurationRange(60, 14400).target()).isEqualTo(7230);
        assertThat(new FakeStageWork.DurationRange(10, 31).target()).isEqualTo(20);
        assertThat(new FakeStageWork.DurationRange(1, 1).target()).isEqualTo(1);
        for (int min = 1; min <= 200; min += 7) {
            for (int max = min; max <= 400; max += 13) {
                int target = new FakeStageWork.DurationRange(min, max).target();
                assertThat(target).isBetween(min, max);
            }
        }
        assertThatThrownBy(() -> new FakeStageWork.DurationRange(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeStageWork.DurationRange(61, 60)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeSelectionAsksTheRangeSourceForEachRequestAndIsDeterministic() {
        StageWork.StepContext shorts = new StageWork.StepContext(7, 70, ProductionStage.SELECT, "video", 1, 1, 1,
                "SHORTS", null, null);
        StageWork.StepContext longform = new StageWork.StepContext(8, 80, ProductionStage.SELECT, "video", 1, 1, 1,
                "LONGFORM", null, null);
        FakeStageWork work = new FakeStageWork(BigDecimal.ONE, context -> "SHORTS".equals(context.contentType())
                ? new FakeStageWork.DurationRange(40, 60) : new FakeStageWork.DurationRange(480, 720));
        assertThat(work.select(shorts).targetDurationSeconds()).isEqualTo(45);
        assertThat(work.select(longform).targetDurationSeconds()).isEqualTo(600);
        assertThat(work.select(longform)).isEqualTo(work.select(longform));
        // the fingerprint identifies the request, not the length
        assertThat(work.select(shorts).fingerprint())
                .isEqualTo(new FakeStageWork(BigDecimal.ONE).select(shorts).fingerprint());

        assertThatThrownBy(() -> new FakeStageWork(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
        StepFailure missing = (StepFailure) org.assertj.core.api.Assertions.catchThrowable(
                () -> new FakeStageWork(BigDecimal.ONE, context -> null).select(shorts));
        assertThat(missing.code()).isEqualTo("FAKE_LENGTH_RANGE_MISSING");
        assertThat(missing.retryable()).isFalse();
    }

    @Test
    void stepFailureCodesAreBounded() {
        assertThat(new StepFailure("RENDER_TIMEOUT", true, "x").retryable()).isTrue();
        assertThatThrownBy(() -> new StepFailure("bad code", true, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}

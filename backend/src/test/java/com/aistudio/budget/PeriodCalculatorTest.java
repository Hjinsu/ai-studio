package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aistudio.budget.PeriodCalculator.Period;
import com.aistudio.budget.PeriodCalculator.PeriodType;
import com.aistudio.budget.PeriodCalculator.TimezoneSegment;

/** docs/design/T02_BUDGET_LEDGER.md §7 (ADR-038, ADR-049). */
class PeriodCalculatorTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static List<TimezoneSegment> seoul() {
        return PeriodCalculator.segments(List.of(new TimezoneSegment(CREATED, "Asia/Seoul")));
    }

    @Test
    void seoulDayAndMonthBoundariesInUtc() {
        Period day = PeriodCalculator.periodAt(seoul(), PeriodType.DAY, Instant.parse("2026-11-26T01:00:00Z"));
        assertThat(day.start()).isEqualTo("2026-11-25T15:00:00Z");
        assertThat(day.end()).isEqualTo("2026-11-26T15:00:00Z");
        Period month = PeriodCalculator.periodAt(seoul(), PeriodType.MONTH, Instant.parse("2026-11-26T01:00:00Z"));
        assertThat(month.start()).isEqualTo("2026-10-31T15:00:00Z");
        assertThat(month.end()).isEqualTo("2026-11-30T15:00:00Z");
    }

    @Test
    void dstDaysAre23And25Hours() {
        List<TimezoneSegment> ny = PeriodCalculator.segments(List.of(new TimezoneSegment(CREATED, "America/New_York")));
        Period spring = PeriodCalculator.periodAt(ny, PeriodType.DAY, Instant.parse("2026-03-08T12:00:00Z"));
        assertThat(Duration.between(spring.start(), spring.end())).isEqualTo(Duration.ofHours(23));
        Period fall = PeriodCalculator.periodAt(ny, PeriodType.DAY, Instant.parse("2026-11-01T12:00:00Z"));
        assertThat(Duration.between(fall.start(), fall.end())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void timezoneChangeTakesEffectAtEndOfCurrentPeriodAndMergesRemainder() {
        Instant changeAt = Instant.parse("2026-11-26T01:00:00Z"); // 서울 11-26 10:00
        Instant effective = PeriodCalculator.timezoneEffectiveFrom(seoul(), PeriodType.DAY, changeAt);
        assertThat(effective).isEqualTo("2026-11-26T15:00:00Z");

        List<TimezoneSegment> segments = PeriodCalculator.segments(List.of(
                new TimezoneSegment(CREATED, "Asia/Seoul"), new TimezoneSegment(effective, "America/New_York")));
        // 효력 전에는 기존 서울 기간 그대로(현재 bucket 경계 불변)
        assertThat(PeriodCalculator.periodAt(segments, PeriodType.DAY, changeAt).end()).isEqualTo(effective);
        // 전환 기간: 자투리(NY 11-26 10시~11-27 0시) + 다음 완전한 NY 하루
        Period transition = PeriodCalculator.periodAt(segments, PeriodType.DAY, Instant.parse("2026-11-26T20:00:00Z"));
        assertThat(transition.start()).isEqualTo("2026-11-26T15:00:00Z");
        assertThat(transition.end()).isEqualTo("2026-11-28T05:00:00Z");
        assertThat(transition.timezone()).isEqualTo("America/New_York");
        // 이후 NY 자연 기간
        Period after = PeriodCalculator.periodAt(segments, PeriodType.DAY, Instant.parse("2026-11-28T06:00:00Z"));
        assertThat(after.start()).isEqualTo("2026-11-28T05:00:00Z");
        assertThat(after.end()).isEqualTo("2026-11-29T05:00:00Z");
    }

    @Test
    void periodsAreContiguousWithoutOverlapAcrossTransition() {
        Instant changeAt = Instant.parse("2026-11-26T01:00:00Z");
        for (PeriodType type : List.of(PeriodType.DAY, PeriodType.MONTH)) {
            // 효력 시각은 기간 유형별로 계산된다(현재 기간의 끝)
            Instant effective = PeriodCalculator.timezoneEffectiveFrom(seoul(), type, changeAt);
            List<TimezoneSegment> segments = PeriodCalculator.segments(List.of(
                    new TimezoneSegment(CREATED, "Asia/Seoul"), new TimezoneSegment(effective, "America/Los_Angeles")));
            Instant t = Instant.parse("2026-11-20T00:00:00Z");
            Period previous = PeriodCalculator.periodAt(segments, type, t);
            for (int i = 0; i < 80; i++) {
                Period next = PeriodCalculator.periodAt(segments, type, previous.end());
                assertThat(next.start()).as("%s 기간 연속", type).isEqualTo(previous.end());
                assertThat(next.end()).isAfter(next.start());
                // 기간 안 임의 시각도 같은 기간으로 계산된다(bucket 생성 여부와 무관)
                Instant middle = next.start().plus(Duration.between(next.start(), next.end()).dividedBy(2));
                assertThat(PeriodCalculator.periodAt(segments, type, middle)).isEqualTo(next);
                previous = next;
            }
        }
    }

    @Test
    void transitionPeriodNeverShorterThanOneFullPeriod() {
        Instant effective = Instant.parse("2026-11-30T15:00:00Z"); // 서울 월 경계
        List<TimezoneSegment> segments = PeriodCalculator.segments(List.of(
                new TimezoneSegment(CREATED, "Asia/Seoul"), new TimezoneSegment(effective, "America/New_York")));
        Period transition = PeriodCalculator.periodAt(segments, PeriodType.MONTH, effective);
        assertThat(transition.start()).isEqualTo(effective);
        assertThat(transition.end()).isEqualTo("2027-01-01T05:00:00Z");
    }

    @Test
    void laterRevisionWithSameEffectiveTimeWinsAndUnchangedZonesCollapse() {
        Instant effective = Instant.parse("2026-11-26T15:00:00Z");
        List<TimezoneSegment> segments = PeriodCalculator.segments(List.of(
                new TimezoneSegment(CREATED, "Asia/Seoul"),
                new TimezoneSegment(CREATED, "Asia/Seoul"),
                new TimezoneSegment(effective, "America/New_York"),
                new TimezoneSegment(effective, "Europe/London")));
        assertThat(segments).containsExactly(new TimezoneSegment(CREATED, "Asia/Seoul"),
                new TimezoneSegment(effective, "Europe/London"));
    }
}

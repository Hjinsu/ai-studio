package com.aistudio.budget;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 예산 기간 계산 (docs/design/T02_BUDGET_LEDGER.md §7, ADR-038, ADR-049).
 * 시간대 효력 시각(revision.timezone_effective_from)만으로 결정되므로 bucket 존재 여부와 무관하다.
 */
public final class PeriodCalculator {

    public enum PeriodType { DAY, MONTH, LIFETIME }

    public record Period(Instant start, Instant end, String timezone) {
    }

    /** 시간대 효력 구간. effectiveFrom 이후 zone 규칙을 따른다. */
    public record TimezoneSegment(Instant effectiveFrom, String zone) {
    }

    private PeriodCalculator() {
    }

    /** revision들의 (효력 시각, 시간대)에서 구간 목록을 만든다. 같은 효력 시각이면 나중 revision의 시간대. */
    public static List<TimezoneSegment> segments(List<TimezoneSegment> revisionsInOrder) {
        List<TimezoneSegment> sorted = new ArrayList<>();
        for (TimezoneSegment segment : revisionsInOrder) {
            sorted.removeIf(existing -> existing.effectiveFrom().equals(segment.effectiveFrom()));
            sorted.add(segment);
        }
        sorted.sort(Comparator.comparing(TimezoneSegment::effectiveFrom));
        List<TimezoneSegment> collapsed = new ArrayList<>();
        for (TimezoneSegment segment : sorted) {
            if (collapsed.isEmpty() || !collapsed.getLast().zone().equals(segment.zone())) {
                collapsed.add(segment);
            }
        }
        return collapsed;
    }

    /** 시각 t가 속한 기간. segments는 {@link #segments}의 결과(시작 시각 오름차순, 비어 있지 않음). */
    public static Period periodAt(List<TimezoneSegment> segments, PeriodType type, Instant t) {
        if (type == PeriodType.LIFETIME) {
            throw new IllegalArgumentException("LIFETIME 기간은 콘텐츠 기준(T03)으로 계산한다");
        }
        int index = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).effectiveFrom().isAfter(t)) {
                index = i;
            }
        }
        TimezoneSegment segment = segments.get(index);
        ZoneId zone = ZoneId.of(segment.zone());
        if (index == 0) {
            return natural(zone, type, t);
        }
        Instant start = segment.effectiveFrom();
        Period first = natural(zone, type, start);
        Instant transitionEnd = first.start().equals(start) ? first.end() : natural(zone, type, first.end()).end();
        if (t.isBefore(transitionEnd)) {
            return new Period(start, transitionEnd, segment.zone());
        }
        return natural(zone, type, t);
    }

    /** 시간대 변경 효력 시각: 변경 시각이 속한 현재 규칙의 기간 끝(ADR-049). */
    public static Instant timezoneEffectiveFrom(List<TimezoneSegment> segments, PeriodType type, Instant changeAt) {
        return periodAt(segments, type, changeAt).end();
    }

    static Period natural(ZoneId zone, PeriodType type, Instant t) {
        LocalDate date = t.atZone(zone).toLocalDate();
        LocalDate startDate = type == PeriodType.MONTH ? date.withDayOfMonth(1) : date;
        LocalDate endDate = type == PeriodType.MONTH ? startDate.plusMonths(1) : startDate.plusDays(1);
        return new Period(startDate.atStartOfDay(zone).toInstant(), endDate.atStartOfDay(zone).toInstant(),
                zone.getId());
    }
}

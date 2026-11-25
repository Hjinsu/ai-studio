package com.aistudio.budget;

import java.time.Instant;
import java.util.List;

/**
 * 예산 API 응답 DTO (docs/api/openapi.yaml). ID·금액·환율은 문자열(ADR-030, ADR-032).
 */
final class BudgetResponses {

    private BudgetResponses() {
    }

    static String str(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    record ExchangeRateResponse(String id, String baseCurrency, String quoteCurrency, String rate, Instant effectiveFrom,
            Instant validUntil, String source, String note, String createdBy, Instant createdAt) {

        static ExchangeRateResponse from(BudgetMapper.Rate r) {
            return new ExchangeRateResponse(str(r.id()), r.baseCurrency(), r.quoteCurrency(), Money.format(r.rate()),
                    r.effectiveFrom(), r.validUntil(), r.source(), r.note(), str(r.createdBy()), r.createdAt());
        }
    }

    record PolicyRevisionResponse(int revisionNo, String limitKrw, String timezone, Instant timezoneEffectiveFrom,
            String changeNote, String createdBy, Instant createdAt) {

        static PolicyRevisionResponse from(BudgetMapper.PolicyRevision r) {
            return new PolicyRevisionResponse(r.revisionNo(), Money.format(r.limitKrw()), r.timezone(),
                    r.timezoneEffectiveFrom(), r.changeNote(), str(r.createdBy()), r.createdAt());
        }
    }

    record PolicyResponse(String id, String scope, String channelId, String contentType, String periodType,
            String status, long version, PolicyRevisionResponse currentRevision, Instant createdAt,
            Instant updatedAt) {

        static PolicyResponse from(BudgetPolicyService.PolicyView view) {
            BudgetMapper.Policy p = view.policy();
            return new PolicyResponse(str(p.id()), p.scope(), str(p.channelId()), p.contentType(), p.periodType(),
                    p.status(), p.version(), PolicyRevisionResponse.from(view.currentRevision()), p.createdAt(),
                    p.updatedAt());
        }
    }

    record BucketResponse(String id, String policyId, String scope, String channelId, String periodType,
            Instant periodStart, Instant periodEnd, String timezone, String limitKrw, String reservedKrw,
            String spentKrw, String remainingKrw) {

        static BucketResponse from(BudgetUsageService.UsageBucket u) {
            BudgetMapper.Policy p = u.policy();
            return new BucketResponse(str(u.id()), str(p.id()), p.scope(), str(p.channelId()), p.periodType(),
                    u.periodStart(), u.periodEnd(), u.timezone(), Money.format(u.limitKrw()),
                    Money.format(u.reservedKrw()), Money.format(u.spentKrw()), Money.format(u.remainingKrw()));
        }

        static BucketResponse from(BudgetMapper.Bucket b, BudgetMapper.Policy p) {
            return from(new BudgetUsageService.UsageBucket(b.id(), p, b.periodStart(), b.periodEnd(), b.timezone(),
                    b.limitKrw(), b.reservedKrw(), b.spentKrw()));
        }
    }

    record UsageResponse(Instant at, String channelId, List<BucketResponse> buckets) {
    }

    record ConsistencyStateResponse(String status, Instant checkedAt, int issueCount) {
    }

    record ControlResponse(boolean spendingEnabled, Instant spendingChangedAt, String spendingChangedBy,
            String spendingChangeReason, ConsistencyStateResponse consistency, long version, Instant updatedAt) {

        static ControlResponse from(BudgetControlService.ControlView c) {
            return new ControlResponse(c.spendingEnabled(), c.spendingChangedAt(), str(c.spendingChangedBy()),
                    c.spendingChangeReason(),
                    new ConsistencyStateResponse(c.consistencyStatus(), c.consistencyCheckedAt(), c.issueCount()),
                    c.version(), c.updatedAt());
        }
    }

    record ConsistencyIssueResponse(String checkCode, String entityType, String entityId, String expected,
            String actual) {
    }

    record ConsistencyReportResponse(Instant checkedAt, boolean consistent, List<ConsistencyIssueResponse> issues) {

        static ConsistencyReportResponse from(BudgetControlService.ConsistencyResult result) {
            return new ConsistencyReportResponse(result.checkedAt(), result.consistent(), result.issues().stream()
                    .map(i -> new ConsistencyIssueResponse(i.checkCode(), i.entityType(), String.valueOf(i.entityId()),
                            i.expected(), i.actual()))
                    .toList());
        }
    }

    record CostLogResponse(String id, String eventKey, String reservationId, String requestKey, String entryType,
            String reasonCode, String originalCurrency, String originalAmount, String fxRate, String exchangeRateId,
            String amountKrw, String reserveDeltaKrw, String costDeltaKrw, String costDeltaOriginal,
            String priceVersion, String externalRef, String actorType, String actorUserId, Instant occurredAt,
            Instant recordedAt) {

        static CostLogResponse from(BudgetMapper.CostLog l) {
            return new CostLogResponse(str(l.id()), l.eventKey(), str(l.reservationId()), l.requestKey(), l.entryType(),
                    l.reasonCode(), l.originalCurrency(), Money.format(l.originalAmount()), Money.format(l.fxRate()),
                    str(l.exchangeRateId()), Money.format(l.amountKrw()), Money.format(l.reserveDeltaKrw()),
                    Money.format(l.costDeltaKrw()), Money.format(l.costDeltaOriginal()), l.priceVersion(),
                    l.externalRef(), l.actorType(), str(l.actorUserId()), l.occurredAt(), l.recordedAt());
        }
    }

    record ReservationResponse(String id, String requestKey, String channelId, String purpose, String originalCurrency,
            String estimateOriginal, String exchangeRateId, String fxRate, String estimateKrw, String status,
            String reservedRemainingKrw, String recognizedCostKrw, String recognizedCostOriginal,
            String reconciliationReason, Instant createdAt, Instant updatedAt, Instant closedAt) {

        static ReservationResponse from(BudgetMapper.Reservation r) {
            return new ReservationResponse(str(r.id()), r.requestKey(), str(r.channelId()), r.purpose(),
                    r.originalCurrency(), Money.format(r.estimateOriginal()), str(r.exchangeRateId()),
                    Money.format(r.fxRate()), Money.format(r.estimateKrw()), r.status(),
                    Money.format(r.reservedRemainingKrw()), Money.format(r.recognizedCostKrw()),
                    Money.format(r.recognizedCostOriginal()), r.reconciliationReason(), r.createdAt(), r.updatedAt(),
                    r.closedAt());
        }
    }

    record ReservationDetailResponse(String id, String requestKey, String channelId, String purpose,
            String originalCurrency, String estimateOriginal, String exchangeRateId, String fxRate, String estimateKrw,
            String status, String reservedRemainingKrw, String recognizedCostKrw, String recognizedCostOriginal,
            String reconciliationReason, Instant createdAt, Instant updatedAt, Instant closedAt, List<String> bucketIds,
            List<CostLogResponse> events) {

        static ReservationDetailResponse from(BudgetMapper.Reservation r, List<Long> bucketIds,
                List<BudgetMapper.CostLog> events) {
            ReservationResponse b = ReservationResponse.from(r);
            return new ReservationDetailResponse(b.id(), b.requestKey(), b.channelId(), b.purpose(),
                    b.originalCurrency(), b.estimateOriginal(), b.exchangeRateId(), b.fxRate(), b.estimateKrw(),
                    b.status(), b.reservedRemainingKrw(), b.recognizedCostKrw(), b.recognizedCostOriginal(),
                    b.reconciliationReason(), b.createdAt(), b.updatedAt(), b.closedAt(),
                    bucketIds.stream().map(String::valueOf).toList(),
                    events.stream().map(CostLogResponse::from).toList());
        }
    }
}

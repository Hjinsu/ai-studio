package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/** Plans v1 snapshots from rows already selected and locked by the reservation transaction. */
final class ContentReservationDecision {
    record ExpectedBucket(long policyId, long bucketId, int policyRevision, BigDecimal appliedLimit,
            Instant periodStart, Instant periodEnd) {}

    record Decision(long policyGeneration, Instant decidedAt, Set<Long> lifetimePolicyIds,
            List<ExpectedBucket> expectedBuckets, String selectionFingerprint) {}

    private ContentReservationDecision() {
    }

    static Decision plan(long contentId, long generation, Instant decidedAt, BigDecimal estimateKrw,
            List<BudgetMapper.Policy> policies, List<BudgetMapper.Bucket> buckets, Set<Long> frozenPolicyIds) {
        ReservationPolicies.requireFrozenLifetimeSet(policies, frozenPolicyIds);
        Map<Long, BudgetMapper.Bucket> byPolicy = new HashMap<>();
        for (BudgetMapper.Bucket bucket : buckets) {
            if (byPolicy.putIfAbsent(bucket.policyId(), bucket) != null) {
                throw invalidBucket();
            }
        }

        var lifetimeIds = new HashSet<Long>();
        var expected = new ArrayList<ExpectedBucket>();
        for (BudgetMapper.Policy policy : policies) {
            BudgetMapper.Bucket bucket = byPolicy.remove(policy.id());
            boolean lifetime = BudgetPolicyService.CONTENT_TYPE.equals(policy.scope());
            if (bucket == null || bucket.limitPolicyRevisionNo() != policy.currentRevisionNo()
                    || !Objects.equals(bucket.contentId(), lifetime ? contentId : null)
                    || decidedAt.isBefore(bucket.periodStart()) || !decidedAt.isBefore(bucket.periodEnd())) {
                throw invalidBucket();
            }
            if (lifetime) {
                if (!ReservationPolicies.LIFETIME_START.equals(bucket.periodStart())
                        || !ReservationPolicies.LIFETIME_END.equals(bucket.periodEnd())
                        || !ReservationPolicies.LIFETIME_TIMEZONE.equals(bucket.timezone())) {
                    throw invalidBucket();
                }
                lifetimeIds.add(policy.id());
            }
            if (bucket.limitKrw().signum() == 0
                    || bucket.spentKrw().add(bucket.reservedKrw()).add(estimateKrw).compareTo(bucket.limitKrw()) > 0) {
                throw new ApiException(ErrorCode.BUDGET_EXCEEDED, "The request exceeds the budget limit.");
            }
            expected.add(new ExpectedBucket(policy.id(), bucket.id(), bucket.limitPolicyRevisionNo(),
                    bucket.limitKrw(), bucket.periodStart(), bucket.periodEnd()));
        }
        if (!byPolicy.isEmpty() || lifetimeIds.isEmpty()) {
            throw invalidBucket();
        }
        List<Long> policyIds = expected.stream().map(ExpectedBucket::policyId).sorted().toList();
        return new Decision(generation, decidedAt, Set.copyOf(lifetimeIds), List.copyOf(expected),
                Fingerprints.of(Map.of("policyIds", policyIds)));
    }

    private static ApiException invalidBucket() {
        return new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                "The locked budget buckets do not match the reservation decision.");
    }
}

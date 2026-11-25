package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

class ContentReservationDecisionTest {
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");
    private static final List<BudgetMapper.Policy> POLICIES = List.of(
            policy(1, "SYSTEM", "DAY"), policy(2, "SYSTEM", "MONTH"), policy(3, "CONTENT_TYPE", "LIFETIME"));
    private static final BudgetMapper.Bucket DAY = bucket(1, null, "10");
    private static final BudgetMapper.Bucket MONTH = bucket(2, null, "10");

    @Test
    void snapshotsLockedBucketsAndKeepsFrozenMembership() {
        var decision = ContentReservationDecision.plan(7, 11, NOW, BigDecimal.ONE, POLICIES,
                List.of(bucket(3, 7L, "10"), MONTH, DAY), Set.of(3L));
        assertThat(decision.policyGeneration()).isEqualTo(11);
        assertThat(decision.lifetimePolicyIds()).containsExactly(3L);
        assertThat(decision.expectedBuckets()).extracting(ContentReservationDecision.ExpectedBucket::bucketId)
                .containsExactly(101L, 102L, 103L);
    }

    @Test
    void unknownBucketOwnerAndZeroLimitFailClosed() {
        assertFailure(bucket(3, 8L, "10"), ErrorCode.PREREQUISITE_NOT_MET);
        assertFailure(bucket(3, 7L, "0"), ErrorCode.BUDGET_EXCEEDED);
    }

    private static void assertFailure(BudgetMapper.Bucket lifetime, ErrorCode code) {
        assertThatThrownBy(() -> ContentReservationDecision.plan(7, 11, NOW, BigDecimal.ONE, POLICIES,
                List.of(DAY, MONTH, lifetime), Set.of()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
    }

    private static BudgetMapper.Policy policy(long id, String scope, String period) {
        return new BudgetMapper.Policy(id, scope, null, id == 3 ? "SHORTS" : null, null, period, "ACTIVE",
                1, 0, NOW, NOW);
    }

    private static BudgetMapper.Bucket bucket(long policyId, Long contentId, String limit) {
        return new BudgetMapper.Bucket(policyId + 100, policyId, contentId,
                contentId == null ? NOW.minusSeconds(1) : ReservationPolicies.LIFETIME_START,
                contentId == null ? NOW.plusSeconds(1) : ReservationPolicies.LIFETIME_END,
                "UTC", new BigDecimal(limit), 1, BigDecimal.ZERO, BigDecimal.ZERO, 0, NOW, NOW);
    }
}

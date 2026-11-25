package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aistudio.budget.ReservationBucketEvidence.Link;

class ReservationBucketEvidenceTest {
    private static final Link DAY = new Link(1, 10);
    private static final Link MONTH = new Link(2, 20);
    private static final Link LIFETIME = new Link(3, 30);

    @Test
    void findsMissingAndUnexpectedLinksEvenWhenPolicyIsTheSame() {
        var wrongBucket = new Link(3, 31);
        var result = ReservationBucketEvidence.compare(Set.of(1L, 2L, 3L),
                List.of(DAY, MONTH, LIFETIME), List.of(MONTH, DAY, wrongBucket));
        assertThat(result.missingLinks()).containsExactly(LIFETIME);
        assertThat(result.unexpectedLinks()).containsExactly(wrongBucket);
        assertThat(result.consistent()).isFalse();
    }

    @Test
    void findsAPolicyOmittedFromBothLinkSets() {
        var result = ReservationBucketEvidence.compare(Set.of(1L, 2L, 3L),
                List.of(DAY, MONTH), List.of(DAY, MONTH));
        assertThat(result.missingPolicies()).containsExactly(3L);
        assertThat(result.consistent()).isFalse();
    }

    @Test
    void acceptsMatchingEvidenceRegardlessOfLinkOrder() {
        assertThat(ReservationBucketEvidence.compare(Set.of(1L, 2L, 3L),
                List.of(DAY, MONTH, LIFETIME), List.of(LIFETIME, MONTH, DAY)).consistent()).isTrue();
    }
}

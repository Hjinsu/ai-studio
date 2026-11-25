package com.aistudio.budget;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Compares v1 evidence. Callers reconstruct the eligible set at the stored policy generation. */
final class ReservationBucketEvidence {
    record Link(long policyId, long bucketId) {}

    record Differences(Set<Link> missingLinks, Set<Link> unexpectedLinks,
            Set<Long> missingPolicies, Set<Long> unexpectedPolicies, boolean duplicatePolicy) {
        boolean consistent() {
            return missingLinks.isEmpty() && unexpectedLinks.isEmpty()
                    && missingPolicies.isEmpty() && unexpectedPolicies.isEmpty() && !duplicatePolicy;
        }
    }

    private ReservationBucketEvidence() {
    }

    static Differences compare(Set<Long> eligiblePolicyIds, List<Link> expected, List<Link> actual) {
        Set<Long> expectedPolicies = new TreeSet<>();
        boolean duplicatePolicy = false;
        for (Link link : expected) {
            duplicatePolicy |= !expectedPolicies.add(link.policyId());
        }
        return new Differences(minus(expected, actual), minus(actual, expected),
                minus(eligiblePolicyIds, expectedPolicies), minus(expectedPolicies, eligiblePolicyIds),
                duplicatePolicy);
    }

    private static <T> Set<T> minus(Collection<T> source, Collection<T> other) {
        Set<T> remaining = new HashSet<>(source);
        remaining.removeAll(other);
        return Set.copyOf(remaining);
    }
}

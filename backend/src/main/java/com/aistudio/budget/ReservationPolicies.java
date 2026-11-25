package com.aistudio.budget;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/** Policy selection before the reservation writer snapshots the decision. */
final class ReservationPolicies {
    static final Instant LIFETIME_START = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant LIFETIME_END = Instant.parse("9999-12-31T23:59:59.999999Z");
    static final String LIFETIME_TIMEZONE = "UTC";

    private ReservationPolicies() {
    }

    record PolicyAtGeneration(long generation, BudgetMapper.Policy policy) {}

    /**
     * Reconstruct the applicable set from immutable policy snapshots. A snapshot contains
     * the status at that generation and the revision recorded by the state event.
     */
    static List<BudgetMapper.Policy> contentAtGeneration(long channelId, String contentType, long generation,
            List<PolicyAtGeneration> history) {
        Map<Long, PolicyAtGeneration> latest = new HashMap<>();
        for (PolicyAtGeneration snapshot : history) {
            if (snapshot.generation() <= generation) {
                latest.merge(snapshot.policy().id(), snapshot,
                        (current, incoming) -> incoming.generation() > current.generation() ? incoming : current);
            }
        }
        return content(channelId, contentType, latest.values().stream().map(PolicyAtGeneration::policy).toList());
    }

    /** Call under the budget control lock with current locked policy rows. */
    static List<BudgetMapper.Policy> content(long channelId, String contentType, List<BudgetMapper.Policy> candidates) {
        Objects.requireNonNull(contentType, "contentType");
        List<BudgetMapper.Policy> selected = candidates.stream()
                .filter(policy -> "ACTIVE".equals(policy.status()))
                .filter(policy -> applies(policy, channelId, contentType))
                .sorted(Comparator.comparingLong(BudgetMapper.Policy::id))
                .toList();
        requireSystemPolicies(selected);
        if (selected.stream().noneMatch(policy -> BudgetPolicyService.CONTENT_TYPE.equals(policy.scope()))) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "An active CONTENT_TYPE LIFETIME policy is required before spending.");
        }
        return selected;
    }

    static void requireFrozenLifetimeSet(List<BudgetMapper.Policy> selected, Set<Long> frozenPolicyIds) {
        if (frozenPolicyIds.isEmpty()) {
            return;
        }
        Set<Long> lifetimeIds = selected.stream()
                .filter(policy -> BudgetPolicyService.CONTENT_TYPE.equals(policy.scope()))
                .map(BudgetMapper.Policy::id)
                .collect(Collectors.toUnmodifiableSet());
        if (!lifetimeIds.equals(frozenPolicyIds)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The applicable lifetime policy set does not match the frozen content policies.");
        }
    }

    private static boolean applies(BudgetMapper.Policy policy, long channelId, String contentType) {
        return switch (policy.scope()) {
            case BudgetPolicyService.SYSTEM -> periodic(policy);
            case BudgetPolicyService.CHANNEL -> periodic(policy) && Objects.equals(policy.channelId(), channelId);
            case BudgetPolicyService.CONTENT_TYPE -> "LIFETIME".equals(policy.periodType())
                    && contentType.equals(policy.contentType())
                    && (policy.channelId() == null || policy.channelId() == channelId);
            default -> false;
        };
    }

    private static boolean periodic(BudgetMapper.Policy policy) {
        return "DAY".equals(policy.periodType()) || "MONTH".equals(policy.periodType());
    }

    static void requireSystemPolicies(List<BudgetMapper.Policy> policies) {
        boolean day = false;
        boolean month = false;
        for (BudgetMapper.Policy policy : policies) {
            if (BudgetPolicyService.SYSTEM.equals(policy.scope())) {
                day |= "DAY".equals(policy.periodType());
                month |= "MONTH".equals(policy.periodType());
            }
        }
        if (!day || !month) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Active SYSTEM DAY and MONTH budget policies are required before spending.");
        }
    }
}

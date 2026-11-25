package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

import com.aistudio.budget.ReservationPolicies.PolicyAtGeneration;

class ReservationPoliciesTest {
    private static final BudgetMapper.Policy DAY = policy(1, "SYSTEM", null, null, "DAY", "ACTIVE");
    private static final BudgetMapper.Policy MONTH = policy(2, "SYSTEM", null, null, "MONTH", "ACTIVE");
    private static final BudgetMapper.Policy GLOBAL = policy(8, "CONTENT_TYPE", null, "SHORTS", "LIFETIME", "ACTIVE");

    @Test
    void selectsAllApplicablePoliciesInIdOrder() {
        var channel = policy(3, "CHANNEL", 7L, null, "DAY", "ACTIVE");
        var local = policy(4, "CONTENT_TYPE", 7L, "SHORTS", "LIFETIME", "ACTIVE");
        var wrongChannel = policy(5, "CONTENT_TYPE", 9L, "SHORTS", "LIFETIME", "ACTIVE");
        var wrongType = policy(6, "CONTENT_TYPE", 7L, "LONGFORM", "LIFETIME", "ACTIVE");
        var inactive = policy(7, "CONTENT_TYPE", 7L, "SHORTS", "LIFETIME", "DISABLED");
        assertThat(ReservationPolicies.content(7, "SHORTS",
                List.of(GLOBAL, inactive, wrongType, local, DAY, wrongChannel, channel, MONTH)))
                .containsExactly(DAY, MONTH, channel, local, GLOBAL);
    }

    @Test
    void missingSystemPeriodOrLifetimeFailsClosed() {
        for (var candidates : List.of(List.of(DAY, MONTH), List.of(DAY, GLOBAL), List.of(MONTH, GLOBAL))) {
            assertThatThrownBy(() -> ReservationPolicies.content(7, "SHORTS", candidates))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));
        }
    }

    @Test
    void frozenLifetimeSetAllowsRevisionChangesButRejectsMembershipChanges() {
        var revised = new BudgetMapper.Policy(GLOBAL.id(), GLOBAL.scope(), GLOBAL.channelId(), GLOBAL.contentType(),
                null, GLOBAL.periodType(), GLOBAL.status(), 2, 1, Instant.EPOCH, Instant.EPOCH);
        var channelPolicy = policy(9, "CONTENT_TYPE", 7L, "SHORTS", "LIFETIME", "ACTIVE");
        ReservationPolicies.requireFrozenLifetimeSet(List.of(DAY, MONTH, revised), Set.of(8L));
        assertThatThrownBy(() -> ReservationPolicies.requireFrozenLifetimeSet(
                List.of(DAY, MONTH, revised, channelPolicy), Set.of(8L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void historicalSelectionIgnoresLaterActivationsAndKeepsEarlierStatusAndRevision() {
        var disabled = new BudgetMapper.Policy(8L, "CONTENT_TYPE", null, "SHORTS", null, "LIFETIME",
                "DISABLED", 2, 1, Instant.EPOCH, Instant.EPOCH);
        var laterPolicy = policy(9, "CONTENT_TYPE", 7L, "SHORTS", "LIFETIME", "ACTIVE");
        var history = List.of(new PolicyAtGeneration(8, laterPolicy), new PolicyAtGeneration(7, disabled),
                new PolicyAtGeneration(0, DAY), new PolicyAtGeneration(0, GLOBAL), new PolicyAtGeneration(0, MONTH));
        assertThat(ReservationPolicies.contentAtGeneration(7, "SHORTS", 6, history))
                .containsExactly(DAY, MONTH, GLOBAL);
        assertThat(ReservationPolicies.contentAtGeneration(7, "SHORTS", 8, history))
                .containsExactly(DAY, MONTH, laterPolicy);
    }

    private static BudgetMapper.Policy policy(long id, String scope, Long channel, String type, String period,
            String status) {
        return new BudgetMapper.Policy(id, scope, channel, type, null, period, status, 1, 0,
                Instant.EPOCH, Instant.EPOCH);
    }
}

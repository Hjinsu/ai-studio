package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aistudio.budget.SettlementIntentDecision.Action;
import com.aistudio.budget.SettlementIntentDecision.Intent;
import com.aistudio.budget.SettlementIntentDecision.ReservationBasis;
import com.aistudio.budget.SettlementIntentDecision.State;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

class SettlementIntentDecisionTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void completedExecutionReplaysStoredReceiptDespiteLaterReservationChanges() {
        var intent = new Intent(ID, 7, "fingerprint", 1, null, Action.CONFIRM, State.COMMITTED, "{}");
        var changed = new ReservationBasis(7, 99, OTHER, OTHER, false, false, "SETTLED");
        assertThat(SettlementIntentDecision.executionReceipt(changed, intent)).contains("{}");
        assertThat(SettlementIntentDecision.replayCreation(intent, 7, "fingerprint")).contains(intent);
        assertThatThrownBy(() -> SettlementIntentDecision.replayCreation(intent, 7, "different"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void requiresOpenGuardAndMatchingReceiptAndReservationVersion() {
        var pending = new Intent(ID, 7, "fingerprint", 2, OTHER, Action.REFUND, State.PENDING, null);
        assertThat(SettlementIntentDecision.executionReceipt(
                new ReservationBasis(7, 2, OTHER, ID, false, true, "SETTLED"), pending)).isEmpty();
        for (var changed : new ReservationBasis[] {
                new ReservationBasis(7, 3, OTHER, ID, false, true, "SETTLED"),
                new ReservationBasis(7, 2, null, ID, false, true, "SETTLED"),
                new ReservationBasis(7, 2, OTHER, OTHER, false, true, "SETTLED")
        }) {
            assertThatThrownBy(() -> SettlementIntentDecision.executionReceipt(changed, pending))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        }
    }

    @Test
    void unresolvedQueueCostCannotBeSettledByAdministratorIntent() {
        var pending = new Intent(ID, 7, "fingerprint", 2, null, Action.CONFIRM, State.PENDING, null);
        assertThatThrownBy(() -> SettlementIntentDecision.requireNewIntent(
                new ReservationBasis(7, 2, null, null, true, true, "RECONCILIATION_REQUIRED"), pending))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));
    }
}

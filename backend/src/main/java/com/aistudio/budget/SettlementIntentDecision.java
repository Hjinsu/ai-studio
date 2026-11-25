package com.aistudio.budget;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/** Business guards for M1/M2 after the reservation and settlement guard have been locked. */
final class SettlementIntentDecision {
    enum Action { CONFIRM, RELEASE, ADJUSTMENT, REFUND }
    enum State { PENDING, COMMITTED, REJECTED, CANCELLED }

    record Intent(UUID id, long reservationId, String fingerprint, long expectedReservationVersion,
            UUID priorReceiptId, Action action, State state, String receiptJson) {}

    record ReservationBasis(long reservationId, long version, UUID latestReceiptId,
            UUID openIntentId, boolean unresolvedQueueCost, boolean queueOwned, String reservationStatus) {}

    private SettlementIntentDecision() {
    }

    static Optional<Intent> replayCreation(Intent existing, long reservationId, String fingerprint) {
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.reservationId() != reservationId || !existing.fingerprint().equals(fingerprint)) {
            throw conflict("The settlement request key was already used with different content.");
        }
        return Optional.of(existing);
    }

    static void requireNewIntent(ReservationBasis basis, Intent candidate) {
        if (basis.openIntentId() != null) {
            throw conflict("An unfinished settlement request already exists.");
        }
        requireCurrentBasis(basis, candidate);
    }

    /** A terminal execution replays its stored result before consulting mutable reservation state. */
    static Optional<String> executionReceipt(ReservationBasis basis, Intent intent) {
        if (basis.reservationId() != intent.reservationId()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (intent.state() != State.PENDING) {
            if (intent.receiptJson() == null) {
                throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "The settlement receipt is unavailable.");
            }
            return Optional.of(intent.receiptJson());
        }
        if (!intent.id().equals(basis.openIntentId())) {
            throw conflict("The settlement request no longer owns the reservation guard.");
        }
        requireCurrentBasis(basis, intent);
        return Optional.empty();
    }

    private static void requireCurrentBasis(ReservationBasis basis, Intent intent) {
        if (basis.reservationId() != intent.reservationId()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (basis.version() != intent.expectedReservationVersion()
                || !Objects.equals(basis.latestReceiptId(), intent.priorReceiptId())) {
            throw conflict("The reservation or its last receipt changed.");
        }
        if (basis.queueOwned() && (basis.unresolvedQueueCost() || !"SETTLED".equals(basis.reservationStatus())
                || (intent.action() != Action.ADJUSTMENT && intent.action() != Action.REFUND))) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Use the queue recovery workflow for this reservation.");
        }
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}

package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.Objects;

import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.stage.PlanningReview;

/**
 * V2-03 collaborators of the executor for {@code v2-planning-1} requests: the planning port (fixed input,
 * cost-guarded dispatch, stored response, fenced apply) and the local review. Without this flow the executor never
 * claims planning requests. {@code maximumKrw} is the reservation ceiling of each planning effect.
 */
public record PlanningFlow(PlanningExecutionPort port, PlanningReview review, BigDecimal maximumKrw) {
    public PlanningFlow {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(review, "review");
        if (maximumKrw == null || maximumKrw.signum() <= 0) {
            throw new IllegalArgumentException("Planning effect maximum must be a positive KRW amount");
        }
    }
}

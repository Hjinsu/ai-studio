package com.aistudio.budget;

import java.math.BigDecimal;

/**
 * 정산 계산식 (docs/design/T02_BUDGET_LEDGER.md §2, ADR-056, ADR-057). 상태 검사는 하지 않는 순수 함수.
 * E: 예약액, RR: 남은 예약, C: 인식 비용 KRW, C0: 인식 비용 원본 통화, r: 고정 환율.
 */
public final class SettlementMath {

    private SettlementMath() {
    }

    /** 원장 한 건의 효과. amountOriginal/amountKrw는 원장 표시용 이벤트 금액. */
    public record Deltas(BigDecimal reserveDelta, BigDecimal costDelta, BigDecimal costDeltaOriginal,
            BigDecimal amountOriginal, BigDecimal amountKrw) {
    }

    public record State(BigDecimal estimate, BigDecimal reservedRemaining, BigDecimal recognizedCost,
            BigDecimal recognizedCostOriginal, BigDecimal rate) {
    }

    /** PROVISIONAL: newCost=K(P0), newReserved=max(0, E−newCost), ΔC=newCost−C, ΔR=newReserved−RR(양수 가능). */
    public static Deltas provisional(State s, BigDecimal provisionalTotalOriginal) {
        BigDecimal newCost = Money.settleKrw(provisionalTotalOriginal, s.rate());
        BigDecimal newReserved = s.estimate().subtract(newCost).max(BigDecimal.ZERO);
        BigDecimal costDelta = newCost.subtract(s.recognizedCost());
        BigDecimal originalDelta = provisionalTotalOriginal.subtract(s.recognizedCostOriginal());
        return new Deltas(newReserved.subtract(s.reservedRemaining()), costDelta, originalDelta, originalDelta,
                costDelta);
    }

    /** CONFIRM: ΔC=K(F0)−C(PROVISIONAL 없으면 C=0), ΔR=−RR(남은 예약 전부 해제). */
    public static Deltas confirm(State s, BigDecimal finalTotalOriginal) {
        BigDecimal costDelta = Money.settleKrw(finalTotalOriginal, s.rate()).subtract(s.recognizedCost());
        BigDecimal originalDelta = finalTotalOriginal.subtract(s.recognizedCostOriginal());
        return new Deltas(s.reservedRemaining().negate(), costDelta, originalDelta, originalDelta, costDelta);
    }

    /** RELEASE: ΔR=−RR, 비용 변화 없음(C=0 전제). */
    public static Deltas release(State s) {
        return new Deltas(s.reservedRemaining().negate(), zero(), zero(), zero(), zero());
    }

    /** 예약 통화 기준 조정(부호 포함). C0도 함께 변한다. */
    public static Deltas adjustOriginal(State s, BigDecimal amountOriginal) {
        BigDecimal costDelta = Money.settleKrw(amountOriginal, s.rate());
        return new Deltas(zero(), costDelta, amountOriginal, amountOriginal, costDelta);
    }

    /** KRW 전용 조정(예: FX_DIFFERENCE). C0는 변하지 않는다(ADR-057). */
    public static Deltas adjustKrw(BigDecimal amountKrw) {
        return new Deltas(zero(), amountKrw, zero(), amountKrw, amountKrw);
    }

    /** 예약 통화 기준 환불(양수 입력). */
    public static Deltas refundOriginal(State s, BigDecimal refundOriginal) {
        BigDecimal costDelta = Money.settleKrw(refundOriginal, s.rate()).negate();
        return new Deltas(zero(), costDelta, refundOriginal.negate(), refundOriginal.negate(), costDelta);
    }

    /** KRW 전용 환불(양수 입력). */
    public static Deltas refundKrw(BigDecimal refundKrw) {
        return new Deltas(zero(), refundKrw.negate(), zero(), refundKrw.negate(), refundKrw.negate());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}

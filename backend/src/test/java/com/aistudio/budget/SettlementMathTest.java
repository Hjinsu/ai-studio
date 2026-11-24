package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.aistudio.budget.SettlementMath.Deltas;
import com.aistudio.budget.SettlementMath.State;

/** docs/design/T02_BUDGET_LEDGER.md §2 계산 예시를 그대로 재현한다(ADR-030, ADR-056, ADR-057). */
class SettlementMathTest {

    private static final BigDecimal RATE = new BigDecimal("1400.1234567891");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /** 이벤트를 적용한 다음 상태. */
    private static State apply(State s, Deltas deltas) {
        return new State(s.estimate(), s.reservedRemaining().add(deltas.reserveDelta()),
                s.recognizedCost().add(deltas.costDelta()),
                s.recognizedCostOriginal().add(deltas.costDeltaOriginal()), s.rate());
    }

    @Test
    void reservationUsesCeilingAtSixDecimals() {
        assertThat(Money.reserveKrw(d("1.00"), RATE)).isEqualByComparingTo("1400.123457");
    }

    @Test
    void documentedSequenceProducesDocumentedValues() {
        BigDecimal estimate = Money.reserveKrw(d("1.00"), RATE);
        State s = new State(estimate, estimate, BigDecimal.ZERO, BigDecimal.ZERO, RATE);

        Deltas p1 = SettlementMath.provisional(s, d("0.80"));
        assertThat(p1.costDelta()).isEqualByComparingTo("1120.098765");
        assertThat(p1.reserveDelta()).isEqualByComparingTo("-1120.098765");
        s = apply(s, p1);
        assertThat(s.reservedRemaining()).isEqualByComparingTo("280.024692");

        // 잠정 비용 감소: 예상액 안의 미사용분이 다시 예약된다(ΔR > 0)
        Deltas p2 = SettlementMath.provisional(s, d("0.30"));
        assertThat(p2.costDelta()).isEqualByComparingTo("-700.061728");
        assertThat(p2.reserveDelta()).isEqualByComparingTo("700.061728");
        s = apply(s, p2);
        assertThat(s.reservedRemaining()).isEqualByComparingTo("980.086420");
        assertThat(s.recognizedCost()).isEqualByComparingTo("420.037037");

        // 다시 증가
        Deltas p3 = SettlementMath.provisional(s, d("0.90"));
        assertThat(p3.costDelta()).isEqualByComparingTo("840.074074");
        assertThat(p3.reserveDelta()).isEqualByComparingTo("-840.074074");
        s = apply(s, p3);
        assertThat(s.reservedRemaining()).isEqualByComparingTo("140.012346");

        // 확정: 예상 초과분도 기록, 남은 예약 전부 해제
        Deltas confirm = SettlementMath.confirm(s, d("1.20"));
        assertThat(confirm.costDelta()).isEqualByComparingTo("420.037037");
        assertThat(confirm.reserveDelta()).isEqualByComparingTo("-140.012346");
        s = apply(s, confirm);
        assertThat(s.recognizedCost()).isEqualByComparingTo("1680.148148");
        assertThat(s.reservedRemaining()).isEqualByComparingTo("0");
        assertThat(s.recognizedCost().subtract(estimate)).isEqualByComparingTo("280.024691");

        // KRW 전용 조정: 원본 통화 인식 비용은 그대로
        Deltas fx = SettlementMath.adjustKrw(d("-50"));
        s = apply(s, fx);
        assertThat(s.recognizedCost()).isEqualByComparingTo("1630.148148");
        assertThat(s.recognizedCostOriginal()).isEqualByComparingTo("1.20");
        assertThat(fx.costDeltaOriginal()).isEqualByComparingTo("0");

        // 원본 통화 환불
        Deltas refund = SettlementMath.refundOriginal(s, d("0.10"));
        assertThat(refund.costDelta()).isEqualByComparingTo("-140.012346");
        s = apply(s, refund);
        assertThat(s.recognizedCost()).isEqualByComparingTo("1490.135802");
        assertThat(s.recognizedCostOriginal()).isEqualByComparingTo("1.10");
    }

    @Test
    void confirmWithoutProvisionalStartsFromZero() {
        BigDecimal estimate = new BigDecimal("1000");
        State s = new State(estimate, estimate, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        Deltas confirm = SettlementMath.confirm(s, d("700"));
        assertThat(confirm.costDelta()).isEqualByComparingTo("700");
        assertThat(confirm.reserveDelta()).isEqualByComparingTo("-1000");
        assertThat(confirm.costDeltaOriginal()).isEqualByComparingTo("700");
    }

    @Test
    void provisionalNeverReservesBeyondEstimate() {
        BigDecimal estimate = new BigDecimal("100");
        State s = new State(estimate, estimate, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        s = apply(s, SettlementMath.provisional(s, d("150")));
        assertThat(s.reservedRemaining()).isEqualByComparingTo("0");
        s = apply(s, SettlementMath.provisional(s, d("0")));
        assertThat(s.reservedRemaining()).isEqualByComparingTo("100");
        assertThat(s.recognizedCost()).isEqualByComparingTo("0");
    }

    @Test
    void halfUpIsSymmetricForNegativeAdjustments() {
        assertThat(Money.settleKrw(d("0.0000005"), BigDecimal.ONE)).isEqualByComparingTo("0.000001");
        assertThat(Money.settleKrw(d("-0.0000005"), BigDecimal.ONE)).isEqualByComparingTo("-0.000001");
    }
}

package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * 신규 예약 Fail Closed (T02_BUDGET_LEDGER §4, ADR-042, 054, 061). 전역 상태(budget_control, SYSTEM 정책)를 바꾸므로
 * 별도 격리 DB에서 한 흐름으로 순서대로 검사한다. 실패한 예약은 아무것도 기록하지 않아야 한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class BudgetFailClosedIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired
    private BudgetReservationService reservations;

    @Autowired
    private BudgetPolicyService policies;

    @Autowired
    private BudgetControlService control;

    @Autowired
    private ChannelService channels;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private void expectRejected(ReserveCommand command, ErrorCode code) {
        assertThatThrownBy(() -> reservations.reserve(command))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_bucket", Integer.class)).isZero();
    }

    private static ReserveCommand krw(String key, Long channelId, String amount) {
        return new ReserveCommand(key, channelId, "test", "KRW", new BigDecimal(amount));
    }

    @Test
    void newReservationsFailClosedUntilEveryPrerequisiteHolds() {
        Actor admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());

        // 1) 기본값: 지출 차단
        assertThat(control.get().spendingEnabled()).isFalse();
        expectRejected(krw("fc-key-0001", null, "1"), ErrorCode.POLICY_VIOLATION);

        control.setSpending(true, "test", control.get().version(), admin);
        // 2) SYSTEM 정책 없음
        expectRejected(krw("fc-key-0002", null, "1"), ErrorCode.PREREQUISITE_NOT_MET);

        // 3) DAY만 있음(MONTH 누락)
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "Asia/Seoul", null, admin);
        expectRejected(krw("fc-key-0003", null, "1"), ErrorCode.PREREQUISITE_NOT_MET);

        // 4) MONTH가 비활성
        var month = policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "Asia/Seoul", null, admin);
        policies.setStatus(month.policy().id(), false, month.policy().version(), admin);
        expectRejected(krw("fc-key-0004", null, "1"), ErrorCode.PREREQUISITE_NOT_MET);
        var disabled = policies.get(month.policy().id());
        policies.setStatus(month.policy().id(), true, disabled.policy().version(), admin);

        // 5) 외화 환율 없음
        expectRejected(new ReserveCommand("fc-key-0005", null, "test", "USD", new BigDecimal("1")),
                ErrorCode.PREREQUISITE_NOT_MET);

        // 6) 일시정지 채널
        var channel = channels.create(ApiTestSupport.uniqueKey("fc"), "en", "UTC", null, admin);
        channels.pause(channel.channel().id(), channel.channel().version(), admin);
        expectRejected(krw("fc-key-0006", channel.channel().id(), "1"), ErrorCode.PREREQUISITE_NOT_MET);

        // 7) 한도 0원 채널 정책 = 무제한 아님(ADR-054). 활성 채널 + 0원 CHANNEL 정책
        var active = channels.create(ApiTestSupport.uniqueKey("fc-zero"), "en", "UTC", null, admin);
        policies.create("CHANNEL", active.channel().id(), null, "DAY", BigDecimal.ZERO, "UTC", null, admin);
        assertThatThrownBy(() -> reservations.reserve(krw("fc-key-0007", active.channel().id(), "0.000001")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log", Integer.class)).isZero();

        // 8) SYSTEM 한도 초과(1000원)
        assertThatThrownBy(() -> reservations.reserve(krw("fc-key-0008", null, "1000.000001")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED));

        // 모든 전제 충족 → 성공(한도와 정확히 같은 금액 허용)
        var ok = reservations.reserve(krw("fc-key-0009", null, "1000"));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.reservation().status()).isEqualTo(BudgetReservationService.ACTIVE);
        assertThat(reservations.bucketIds(ok.reservation().id())).hasSize(2);

        // 9) 정합성 ERROR면 차단 — 원장 우회 조작으로 불일치를 만든 뒤 검사
        jdbc.update("UPDATE budget_bucket SET reserved_krw = reserved_krw - 1 WHERE id = ?",
                reservations.bucketIds(ok.reservation().id()).getFirst());
        assertThat(control.runConsistencyCheck(admin).consistent()).isFalse();
        assertThat(control.get().consistencyStatus()).isEqualTo("ERROR");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Long.class);
        assertThatThrownBy(() -> reservations.reserve(krw("fc-key-0010", null, "0.5")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Long.class)).isEqualTo(before);

        // 지출 차단 중에도 기존 예약의 정산은 허용(ADR-061)
        jdbc.update("UPDATE budget_bucket SET reserved_krw = reserved_krw + 1 WHERE id = ?",
                reservations.bucketIds(ok.reservation().id()).getFirst());
        assertThat(control.runConsistencyCheck(admin).consistent()).isTrue();
        control.setSpending(false, "stop", control.get().version(), admin);
        var released = reservations.release(new BudgetReservationService.EventCommand(ok.reservation().id(),
                "fc-release-0009", null, null, null, null, null, null, Actor.system()));
        assertThat(released.status()).isEqualTo(BudgetReservationService.RELEASED);
        assertThat(control.runConsistencyCheck(admin).consistent()).isTrue();
    }
}

package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aistudio.ApiTestSupport;
import com.aistudio.MutableClock;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * 시각 의존 예산 동작의 DB 통합 검증(T02_BUDGET_LEDGER §7·§8, ADR-017, 038, 046, 049): 환율 효력·만료, 기존 기간
 * bucket으로의 지연 정산, DST 기간 길이, 시간대 변경 전환 기간(bucket 미생성 구간 포함), 한도 revision–bucket 연결.
 * 조정 가능한 UTC 시계를 @Primary Clock으로 주입한다. 시계는 앞으로만 움직여야 하므로(뒤로 돌리면 닫힌 bucket이 다시
 * 열린 것으로 보인다) 테스트를 날짜 오름차순으로 실행한다.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest
@ActiveProfiles("test")
class BudgetTimeIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-25T03:00:00Z"));
    private static boolean initialized;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private BudgetReservationService reservations;

    @Autowired
    private BudgetPolicyService policies;

    @Autowired
    private BudgetControlService control;

    @Autowired
    private ExchangeRateService rates;

    @Autowired
    private ChannelService channels;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private Actor admin;

    @BeforeEach
    void setUp() {
        synchronized (BudgetTimeIT.class) {
            if (!initialized) {
                CLOCK.set(Instant.parse("2026-11-25T03:00:00Z"));
                admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
                policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000000000"), "Asia/Seoul", null, admin);
                policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000000"), "Asia/Seoul", null, admin);
                control.setSpending(true, "test", control.get().version(), admin);
                initialized = true;
            }
        }
        admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
    }

    @AfterEach
    void consistent() {
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();
    }

    private long channelPolicy(String timezone, String limit) {
        long channelId = channels.create(ApiTestSupport.uniqueKey("time"), "en", "UTC", null, admin).channel().id();
        return policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal(limit), timezone, null, admin).policy()
                .channelId();
    }

    private long policyIdOf(long channelId) {
        return jdbc.queryForObject("SELECT id FROM budget_policy WHERE channel_id = ?", Long.class, channelId);
    }

    private BudgetMapper.Reservation reserve(Long channelId, String currency, String amount) {
        return reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("t"), channelId, "time", currency,
                new BigDecimal(amount))).reservation();
    }

    private EventCommand event(long id, String amount) {
        return new EventCommand(id, ApiTestSupport.uniqueKey("e"), new BigDecimal(amount), null, null, null, null, null,
                Actor.system());
    }

    private record Period(Instant start, Instant end, BigDecimal limit, int revisionNo, BigDecimal reserved,
            BigDecimal spent) {
        Duration length() {
            return Duration.between(start, end);
        }
    }

    private List<Period> buckets(long policyId) {
        return jdbc.query("""
                SELECT period_start, period_end, limit_krw, limit_policy_revision_no, reserved_krw, spent_krw
                  FROM budget_bucket WHERE policy_id = ? ORDER BY period_start""",
                (rs, i) -> new Period(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getBigDecimal(3),
                        rs.getInt(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), policyId);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    // ------------------------------------------------------------------ 환율
    @Test
    @Order(2)
    void exchangeRateMissingFutureAndExpiredAreHandledWithoutFallback() {
        CLOCK.set(at("2027-01-10T00:00:00Z"));
        assertThatThrownBy(() -> reserve(null, "EUR", "1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));

        rates.create("EUR", new BigDecimal("1400"), null, "r1", admin);
        rates.create("EUR", new BigDecimal("1500"), at("2027-01-11T00:00:00Z"), "r2 future", admin);
        BudgetMapper.Reservation first = reserve(null, "EUR", "1");
        assertThat(first.fxRate()).isEqualByComparingTo("1400"); // 미래 환율은 아직 미적용

        CLOCK.set(at("2027-01-11T00:00:01Z"));
        assertThat(reserve(null, "EUR", "1").fxRate()).isEqualByComparingTo("1500");

        // 최신 환율 만료(effective + 72h 도달) → 이전 환율로 fallback 없이 차단
        CLOCK.set(at("2027-01-14T00:00:00Z"));
        assertThatThrownBy(() -> reserve(null, "EUR", "1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));
        // 기존 예약 정산은 예약 시 고정 환율 사용
        BudgetMapper.Reservation settled = reservations.confirm(event(first.id(), "2"));
        assertThat(settled.recognizedCostKrw()).isEqualByComparingTo("2800");
        // 등록 범위(now−5분 ~ now+7일) 밖 거부
        assertThatThrownBy(() -> rates.create("EUR", BigDecimal.TEN, at("2027-01-13T23:50:00Z"), "past", admin))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> rates.create("EUR", BigDecimal.TEN, at("2027-01-21T00:00:01Z"), "far", admin))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ------------------------------------------------------------------ 지연 정산
    @Test
    @Order(3)
    void lateSettlementIsAppliedToTheOriginalPeriodBucket() {
        CLOCK.set(at("2027-02-10T12:00:00Z"));
        long channelId = channelPolicy("UTC", "1000");
        long policyId = policyIdOf(channelId);
        BudgetMapper.Reservation reservation = reserve(channelId, "KRW", "900");

        CLOCK.set(at("2027-02-13T12:00:00Z"));
        reservations.confirm(event(reservation.id(), "800"));
        List<Period> afterConfirm = buckets(policyId);
        assertThat(afterConfirm).hasSize(1);
        assertThat(afterConfirm.getFirst().start()).isEqualTo(at("2027-02-10T00:00:00Z"));
        assertThat(afterConfirm.getFirst().reserved()).isEqualByComparingTo("0");
        assertThat(afterConfirm.getFirst().spent()).isEqualByComparingTo("800");

        // 새 기간은 새 bucket(이전 기간 지출과 무관하게 한도 전체 사용 가능)
        reserve(channelId, "KRW", "1000");
        List<Period> later = buckets(policyId);
        assertThat(later).hasSize(2);
        assertThat(later.get(1).start()).isEqualTo(at("2027-02-13T00:00:00Z"));
        assertThat(later.get(1).reserved()).isEqualByComparingTo("1000");
        assertThat(later.getFirst().spent()).isEqualByComparingTo("800");
    }

    // ------------------------------------------------------------------ DST
    @Test
    @Order(4)
    void dstDaysProduce23And25HourBuckets() {
        CLOCK.set(at("2027-03-14T12:00:00Z")); // America/New_York DST 시작일
        long channelId = channelPolicy("America/New_York", "100000");
        long policyId = policyIdOf(channelId);
        reserve(channelId, "KRW", "1");
        CLOCK.set(at("2027-11-07T12:00:00Z")); // DST 종료일
        reserve(channelId, "KRW", "1");
        List<Period> periods = buckets(policyId);
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).start()).isEqualTo(at("2027-03-14T05:00:00Z"));
        assertThat(periods.get(0).length()).isEqualTo(Duration.ofHours(23));
        assertThat(periods.get(1).start()).isEqualTo(at("2027-11-07T04:00:00Z"));
        assertThat(periods.get(1).length()).isEqualTo(Duration.ofHours(25));
    }

    // ------------------------------------------------------------------ 시간대 변경 (설계 §7 예시)
    @Test
    @Order(1)
    void timezoneChangeTakesEffectAtPeriodBoundaryEvenWithoutBuckets() {
        CLOCK.set(at("2026-11-26T00:30:00Z"));
        long channelId = channelPolicy("Asia/Seoul", "1000");
        long policyId = policyIdOf(channelId);

        // 변경 시각에 bucket 없음(미생성 구간)
        CLOCK.set(at("2026-11-26T01:00:00Z"));
        var revised = policies.revise(policyId, 1, new BigDecimal("1000"), "America/New_York", "move", admin);
        assertThat(revised.currentRevision().timezoneEffectiveFrom()).isEqualTo(at("2026-11-26T15:00:00Z"));
        assertThat(buckets(policyId)).isEmpty();

        CLOCK.set(at("2026-11-26T02:00:00Z"));
        reserve(channelId, "KRW", "1");
        CLOCK.set(at("2026-11-27T00:00:00Z"));
        reserve(channelId, "KRW", "1");
        CLOCK.set(at("2026-11-28T06:00:00Z"));
        reserve(channelId, "KRW", "1");

        List<Period> periods = buckets(policyId);
        assertThat(periods).extracting(Period::start).containsExactly(at("2026-11-25T15:00:00Z"),
                at("2026-11-26T15:00:00Z"), at("2026-11-28T05:00:00Z"));
        assertThat(periods).extracting(Period::end).containsExactly(at("2026-11-26T15:00:00Z"),
                at("2026-11-28T05:00:00Z"), at("2026-11-29T05:00:00Z"));
    }

    // ------------------------------------------------------------------ 한도 revision ↔ bucket
    @Test
    @Order(5)
    void limitRevisionUpdatesOnlyOpenBuckets() {
        CLOCK.set(at("2027-12-01T10:00:00Z"));
        long channelId = channelPolicy("UTC", "1000");
        long policyId = policyIdOf(channelId);
        reserve(channelId, "KRW", "10");
        CLOCK.set(at("2027-12-02T10:00:00Z"));
        reserve(channelId, "KRW", "10");
        policies.revise(policyId, 1, new BigDecimal("2000"), "UTC", "raise", admin);
        List<Period> periods = buckets(policyId);
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).limit()).isEqualByComparingTo("1000"); // 닫힌 기간 유지
        assertThat(periods.get(0).revisionNo()).isEqualTo(1);
        assertThat(periods.get(1).limit()).isEqualByComparingTo("2000"); // 열린 기간 즉시 반영
        assertThat(periods.get(1).revisionNo()).isEqualTo(2);
    }
}

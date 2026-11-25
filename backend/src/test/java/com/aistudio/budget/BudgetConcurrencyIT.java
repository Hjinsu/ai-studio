package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReservationResult;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * 예약과 차단성 작업의 동시 실행(T02_BUDGET_LEDGER §6, ADR-042, 055, 061) 및 실패 시 전체 롤백.
 * 한쪽 트랜잭션을 잠금을 쥔 채 열어 두고 다른 쪽이 대기하는지 확인한 뒤 커밋해, 대기하던 쪽이 커밋된 상태를 기준으로
 * 판정하는지를 결정적으로 검증한다(타이밍 의존 없음). 각 테스트 끝에 정합성 검사 0건.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class BudgetConcurrencyIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final long BLOCKED_PROBE_MILLIS = 700;
    private static boolean initialized;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Actor admin;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @BeforeEach
    void setUp() {
        admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        synchronized (BudgetConcurrencyIT.class) {
            if (!initialized) {
                policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000000000"), "UTC", null, admin);
                policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000000"), "UTC", null, admin);
                initialized = true;
            }
        }
        if (!control.get().spendingEnabled()) {
            control.setSpending(true, "test", control.get().version(), admin);
        }
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();
    }

    // ------------------------------------------------------------------ 도구
    /** 작업을 트랜잭션 안에서 실행하고 잠금을 쥔 채 release 전까지 커밋하지 않는다. */
    private final class Held<T> {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Future<T> result;

        Held(Supplier<T> work) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            result = executor.submit(() -> template.execute(status -> {
                T value = work.get();
                acquired.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return value;
            }));
        }

        T commit() throws Exception {
            release.countDown();
            return result.get(30, TimeUnit.SECONDS);
        }
    }

    private <T> Held<T> hold(Supplier<T> work) throws InterruptedException {
        Held<T> held = new Held<>(work);
        assertThat(held.acquired.await(30, TimeUnit.SECONDS)).as("holder acquired locks").isTrue();
        return held;
    }

    /** 경쟁 작업이 잠금 대기 중(완료되지 않음)인지 확인한다. */
    private static void assertBlocked(Future<?> contender) throws Exception {
        assertThatThrownBy(() -> contender.get(BLOCKED_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static ErrorCode errorOf(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            return ((ApiException) e.getCause()).errorCode();
        }
    }

    private Future<ReservationResult> reserveAsync(Long channelId, String amount) {
        String key = ApiTestSupport.uniqueKey("cc");
        return executor.submit(() -> reservations.reserve(new ReserveCommand(key, channelId, "race", "KRW",
                new BigDecimal(amount))));
    }

    private ReservationResult reserve(Long channelId, String amount) {
        return reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("cc"), channelId, "seq", "KRW",
                new BigDecimal(amount)));
    }

    private long newChannel() {
        return channels.create(ApiTestSupport.uniqueKey("cc"), "en", "UTC", null, admin).channel().id();
    }

    private long channelVersion(long channelId) {
        return channels.get(channelId).channel().version();
    }

    private int reservationsOf(long channelId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE channel_id = ?", Integer.class,
                channelId);
    }

    // ------------------------------------------------------------------ 채널 일시정지·보관
    @Test
    void pauseCommittedFirstBlocksWaitingReservation() throws Exception {
        long channelId = newChannel();
        var pause = hold(() -> channels.pause(channelId, channelVersion(channelId), admin));
        Future<ReservationResult> reservation = reserveAsync(channelId, "10");
        assertBlocked(reservation);
        pause.commit();
        assertThat(errorOf(reservation)).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET);
        assertThat(reservationsOf(channelId)).isZero();
    }

    @Test
    void reservationCommittedFirstThenPauseWaitsAndSucceeds() throws Exception {
        long channelId = newChannel();
        var reservation = hold(() -> reserve(channelId, "10"));
        Future<?> pause = executor.submit(() -> channels.pause(channelId, channelVersion(channelId), admin));
        assertBlocked(pause);
        reservation.commit();
        pause.get(30, TimeUnit.SECONDS);
        assertThat(channels.get(channelId).channel().status()).isEqualTo("PAUSED");
        assertThat(reservationsOf(channelId)).isEqualTo(1);
        // 일시정지 이후 신규 예약은 차단
        assertThatThrownBy(() -> reserve(channelId, "10")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET));
    }

    @Test
    void archiveCommittedFirstBlocksWaitingReservation() throws Exception {
        long channelId = newChannel();
        var archive = hold(() -> channels.archive(channelId, channelVersion(channelId), admin));
        Future<ReservationResult> reservation = reserveAsync(channelId, "10");
        assertBlocked(reservation);
        archive.commit();
        assertThat(errorOf(reservation)).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET);
        assertThat(reservationsOf(channelId)).isZero();
    }

    // ------------------------------------------------------------------ 긴급 지출 차단
    @Test
    void spendingDisableCommittedFirstBlocksWaitingReservation() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Long.class);
        var disable = hold(() -> control.setSpending(false, "emergency", control.get().version(), admin));
        Future<ReservationResult> reservation = reserveAsync(null, "10");
        assertBlocked(reservation);
        disable.commit();
        assertThat(errorOf(reservation)).isEqualTo(ErrorCode.POLICY_VIOLATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Long.class)).isEqualTo(before);
    }

    @Test
    void reservationInFlightDelaysSpendingDisable() throws Exception {
        long channelId = newChannel();
        var reservation = hold(() -> reserve(channelId, "10"));
        Future<?> disable = executor.submit(() -> control.setSpending(false, "emergency", control.get().version(),
                admin));
        assertBlocked(disable);
        reservation.commit();
        disable.get(30, TimeUnit.SECONDS);
        assertThat(control.get().spendingEnabled()).isFalse();
        assertThat(reservationsOf(channelId)).isEqualTo(1);
        // 차단 중에도 기존 예약 정산 허용(ADR-061)
        long id = jdbc.queryForObject("SELECT id FROM budget_reservation WHERE channel_id = ?", Long.class, channelId);
        assertThat(reservations.confirm(new EventCommand(id, ApiTestSupport.uniqueKey("cf"), new BigDecimal("7"), null,
                null, null, null, null, Actor.system())).status()).isEqualTo(BudgetReservationService.SETTLED);
    }

    // ------------------------------------------------------------------ 정책 생성·수정·비활성화
    @Test
    void policyCreatedConcurrentlyAppliesToWaitingReservation() throws Exception {
        long channelId = newChannel();
        var create = hold(() -> policies.create("CHANNEL", channelId, null, "DAY", BigDecimal.ZERO, "UTC", null, admin));
        Future<ReservationResult> reservation = reserveAsync(channelId, "1");
        assertBlocked(reservation);
        create.commit();
        assertThat(errorOf(reservation)).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(reservationsOf(channelId)).isZero();
    }

    @Test
    void policyLimitLoweredConcurrentlyAppliesToWaitingReservation() throws Exception {
        long channelId = newChannel();
        var policy = policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal("1000"), "UTC", null, admin);
        reserve(channelId, "10"); // 열린 bucket 생성
        var revise = hold(() -> policies.revise(policy.policy().id(), 1, new BigDecimal("50"), "UTC", "lower", admin));
        Future<ReservationResult> reservation = reserveAsync(channelId, "100");
        assertBlocked(reservation);
        revise.commit();
        assertThat(errorOf(reservation)).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(reservationsOf(channelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT limit_krw FROM budget_bucket WHERE policy_id = ?""", BigDecimal.class, policy.policy().id()))
                .isEqualByComparingTo("50");
    }

    @Test
    void policyDisabledConcurrentlyNoLongerBlocksWaitingReservation() throws Exception {
        long channelId = newChannel();
        var policy = policies.create("CHANNEL", channelId, null, "DAY", BigDecimal.ZERO, "UTC", null, admin);
        assertThatThrownBy(() -> reserve(channelId, "1")).isInstanceOf(ApiException.class);
        var disable = hold(() -> policies.setStatus(policy.policy().id(), false, policy.policy().version(), admin));
        Future<ReservationResult> reservation = reserveAsync(channelId, "1");
        assertBlocked(reservation);
        disable.commit();
        assertThat(errorOf(reservation)).isNull();
        assertThat(reservationsOf(channelId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 멱등 키 동시 요청(이벤트)
    @Test
    void concurrentSameEventKeyAppliesOnce() throws Exception {
        long id = reserve(null, "100").reservation().id();
        String key = ApiTestSupport.uniqueKey("same-evt");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BudgetMapper.Reservation>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return reservations.recordProvisional(new EventCommand(id, key, new BigDecimal("30"), null, null, null,
                        null, null, Actor.system()));
            }));
        }
        start.countDown();
        Set<String> costs = new HashSet<>();
        for (Future<BudgetMapper.Reservation> future : futures) {
            costs.add(future.get(30, TimeUnit.SECONDS).recognizedCostKrw().stripTrailingZeros().toPlainString());
        }
        assertThat(costs).containsExactly("30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE event_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 실패 시 전체 롤백
    /** 트랜잭션 마지막 단계(원장 INSERT)를 DB 트리거로 강제 실패시켜 앞선 쓰기(bucket 생성, 예약, 연결)가 모두 롤백되는지. */
    @Test
    void reservationFailureAtLastStepRollsBackEverything() {
        long channelId = newChannel();
        var policy = policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal("1000"), "UTC", null, admin);
        BigDecimal systemReserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_krw),0) FROM budget_bucket b "
                + "JOIN budget_policy p ON p.id = b.policy_id WHERE p.scope = 'SYSTEM'", BigDecimal.class);
        withFailingLedgerInsert("RESERVE", () -> assertThatThrownBy(() -> reservations.reserve(new ReserveCommand(
                "boom-" + ApiTestSupport.uniqueKey("rb"), channelId, "x", "KRW", new BigDecimal("10"))))
                .hasStackTraceContaining("forced ledger failure"));
        assertThat(reservationsOf(channelId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_bucket WHERE policy_id = ?", Integer.class,
                policy.policy().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(reserved_krw),0) FROM budget_bucket b "
                + "JOIN budget_policy p ON p.id = b.policy_id WHERE p.scope = 'SYSTEM'", BigDecimal.class))
                .isEqualByComparingTo(systemReserved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE request_key LIKE 'boom-%'",
                Integer.class)).isZero();
    }

    @Test
    void settlementFailureRollsBackReservationAndBuckets() {
        long channelId = newChannel();
        policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal("1000"), "UTC", null, admin);
        var reserved = reservations.reserve(new ReserveCommand("boom-" + ApiTestSupport.uniqueKey("sb"), channelId, "x",
                "KRW", new BigDecimal("10"))).reservation();
        withFailingLedgerInsert("CONFIRM", () -> assertThatThrownBy(() -> reservations.confirm(new EventCommand(
                reserved.id(), ApiTestSupport.uniqueKey("cf"), new BigDecimal("8"), null, null, null, null, null,
                Actor.system()))).hasStackTraceContaining("forced ledger failure"));
        BudgetMapper.Reservation after = reservations.get(reserved.id());
        assertThat(after.status()).isEqualTo(BudgetReservationService.ACTIVE);
        assertThat(after.reservedRemainingKrw()).isEqualByComparingTo("10");
        assertThat(after.recognizedCostKrw()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE reservation_id = ?", Integer.class,
                reserved.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT b.spent_krw FROM budget_bucket b JOIN budget_policy p ON p.id = b.policy_id
                 WHERE p.channel_id = ?""", BigDecimal.class, channelId)).isEqualByComparingTo("0");
    }

    /** 테스트 DB(격리, 관리자 계정)에만 임시 트리거를 만들고 반드시 제거한다. request_key가 boom-으로 시작할 때만 실패. */
    private void withFailingLedgerInsert(String entryType, Runnable body) {
        jdbc.execute("""
                CREATE TRIGGER it_fail_cost_log BEFORE INSERT ON cost_log FOR EACH ROW
                BEGIN
                  IF NEW.request_key LIKE 'boom-%%' AND NEW.entry_type = '%s' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced ledger failure';
                  END IF;
                END""".formatted(entryType));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER it_fail_cost_log");
        }
    }
}

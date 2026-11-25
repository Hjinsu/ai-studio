package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.aistudio.ApiTestSupport;
import com.aistudio.MutableClock;
import com.aistudio.shared.db.BudgetControlLock;
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

    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-25T03:00:00Z"));

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private BudgetControlLock controlLock;

    @Autowired
    private ExchangeRateService rates;

    @Autowired
    private BudgetMapper mapper;

    @Autowired
    private com.aistudio.shared.db.LockRetryingTransactions lockRetries;

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

    private final ThreadLocal<Runnable> pauseBeforeCommit = new ThreadLocal<>();
    private final TransactionExecutionListener commitListener = new TransactionExecutionListener() {
        @Override
        public void beforeCommit(TransactionExecution transaction) {
            Runnable pause = pauseBeforeCommit.get();
            if (pause != null && !transaction.isReadOnly()) {
                pause.run();
            }
        }
    };

    @BeforeEach
    void setUp() {
        ((ConfigurableTransactionManager) transactionManager).addListener(commitListener);
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
        var manager = (ConfigurableTransactionManager) transactionManager;
        manager.getTransactionExecutionListeners().remove(commitListener);
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();
    }

    // ------------------------------------------------------------------ 도구
    /** 작업을 트랜잭션 안에서 실행하고 잠금을 쥔 채 release 전까지 커밋하지 않는다. */
    private final class Held<T> {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Future<T> result;

        Held(Supplier<T> work) {
            result = executor.submit(() -> {
                pauseBeforeCommit.set(this::awaitRelease);
                try {
                    return work.get();
                } finally {
                    pauseBeforeCommit.remove();
                }
            });
        }

        private void awaitRelease() {
            pauseBeforeCommit.remove();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding commit", e);
            }
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

    private Held<Void> holdControlLock() throws InterruptedException {
        var template = new TransactionTemplate(transactionManager);
        return hold(() -> template.execute(status -> {
            controlLock.exclusive();
            return null;
        }));
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
    private void awaitLockQuery(String table, Future<?> contender) throws Exception {
        awaitDatabaseQuery("%FROM " + table + "%", contender);
    }

    private void awaitDatabaseQuery(String statement, Future<?> contender) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !contender.isDone()) {
            Integer queries = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                    WHERE ID <> CONNECTION_ID() AND DB = DATABASE()
                      AND INFO LIKE ?
                    """, Integer.class, statement);
            if (queries > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No waiting query matching " + statement);
    }

    @Test
    void concurrentReplaySucceedsAfterFirstRequestConsumesRemainingLimit() throws Exception {
        long channel = newChannel();
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("100"), "UTC", null, admin);
        ReserveCommand command = new ReserveCommand(ApiTestSupport.uniqueKey("full"), channel, "race", "KRW",
                new BigDecimal("100"));
        Held<ReservationResult> first = hold(() -> reservations.reserve(command));
        Future<ReservationResult> second = executor.submit(() -> reservations.reserve(command));
        awaitLockQuery("budget_policy", second);
        ReservationResult original = first.commit();
        ReservationResult replay = second.get(30, TimeUnit.SECONDS);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservation()).isEqualTo(original.reservation());
        assertThat(reservations.events(original.reservation().id())).hasSize(1);
        assertThat(reservationsOf(channel)).isEqualTo(1);
    }

    @Test
    void concurrentConfirmAndReleaseReplayAfterTerminalTransition() throws Exception {
        for (boolean confirm : List.of(true, false)) {
            long id = reserve(null, "100").reservation().id();
            EventCommand command = new EventCommand(id, ApiTestSupport.uniqueKey("terminal"),
                    confirm ? new BigDecimal("50") : null, null, null, null, null, null, Actor.system());
            Supplier<BudgetMapper.Reservation> submit = () -> confirm ? reservations.confirm(command)
                    : reservations.release(command);
            Held<BudgetMapper.Reservation> first = hold(submit);
            Future<BudgetMapper.Reservation> second = executor.submit(submit::get);
            awaitLockQuery("budget_bucket", second);
            assertThat(secondResult(first, second).status()).isEqualTo(confirm ? "SETTLED" : "RELEASED");
            assertThat(reservations.events(id)).hasSize(2);
        }
    }

    @Test
    void concurrentDifferentReservationAndFingerprintCannotReuseEventKey() throws Exception {
        CLOCK.set(Instant.parse("2027-01-15T00:00:00Z"));
        long firstId = reserve(null, "100").reservation().id();
        CLOCK.set(Instant.parse("2027-02-15T00:00:00Z"));
        long secondId = reserve(null, "100").reservation().id();
        assertThat(reservations.bucketIds(firstId)).doesNotContainAnyElementsOf(reservations.bucketIds(secondId));
        String key = ApiTestSupport.uniqueKey("global");
        EventCommand firstCommand = new EventCommand(firstId, key, new BigDecimal("20"),
                null, null, null, null, null, Actor.system());
        EventCommand competingCommand = new EventCommand(secondId, key, new BigDecimal("20"),
                null, null, null, null, null, Actor.system());
        Held<?> first = hold(() -> reservations.confirm(firstCommand));
        Future<?> competing = executor.submit(() -> reservations.confirm(competingCommand));
        awaitDatabaseQuery("INSERT INTO cost_log%", competing);
        first.commit();
        assertThat(errorOf(competing)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(reservations.events(firstId)).hasSize(2);
        assertThat(reservations.events(secondId)).hasSize(1);
        assertThat(reservations.get(secondId).reservedRemainingKrw()).isEqualByComparingTo("100");
        assertThat(reservations.get(secondId).recognizedCostKrw()).isEqualByComparingTo("0");
    }

    @Test
    void concurrentDifferentReserveFingerprintReturnsConflictBeforeLimitCheck() throws Exception {
        long channel = newChannel();
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("100"), "UTC", null, admin);
        var original = new ReserveCommand(ApiTestSupport.uniqueKey("different"), channel, "first", "KRW",
                new BigDecimal("100"));
        var competing = new ReserveCommand(original.requestKey(), channel, "second", "KRW", BigDecimal.ONE);
        Held<?> first = hold(() -> reservations.reserve(original));
        Future<?> waiting = executor.submit(() -> reservations.reserve(competing));
        awaitLockQuery("budget_policy", waiting);
        first.commit();
        assertThat(errorOf(waiting)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(reservationsOf(channel)).isEqualTo(1);
    }

    private BudgetMapper.Reservation secondResult(Held<BudgetMapper.Reservation> first,
            Future<BudgetMapper.Reservation> second) throws Exception {
        BudgetMapper.Reservation original = first.commit();
        BudgetMapper.Reservation replay = second.get(30, TimeUnit.SECONDS);
        assertThat(replay).isEqualTo(original);
        return replay;
    }

    @Test
    void decisionUsesNewDayAndMonthAfterWaitingForPolicyChange() throws Exception {
        Instant boundary = Instant.parse("2026-12-01T00:00:00Z");
        CLOCK.set(boundary.minusSeconds(1));
        long channel = newChannel();
        var day = policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("100"), "UTC", null, admin);
        Held<?> holder = hold(() -> policies.revise(day.policy().id(), 1, new BigDecimal("200"),
                "Asia/Seoul", null, admin));
        Future<ReservationResult> waiting = reserveAsync(channel, "10");
        awaitLockQuery("budget_control", waiting);
        CLOCK.set(boundary);
        holder.commit();
        var result = waiting.get(30, TimeUnit.SECONDS);
        assertThat(result.reservation().createdAt()).isEqualTo(boundary);
        var buckets = mapper.lockBuckets(reservations.bucketIds(result.reservation().id()));
        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket.periodStart()).isEqualTo(boundary));
        assertThat(buckets).anySatisfy(bucket -> {
            assertThat(bucket.policyId()).isEqualTo(day.policy().id());
            assertThat(bucket.timezone()).isEqualTo("Asia/Seoul");
            assertThat(bucket.limitKrw()).isEqualByComparingTo("200");
        });
    }

    @Test
    void decisionReplansBucketsAfterWaitingForAnExistingSettlement() throws Exception {
        Instant boundary = Instant.parse("2026-12-15T00:00:00Z");
        CLOCK.set(boundary.minusSeconds(1));
        long id = reserve(null, "10").reservation().id();
        EventCommand event = new EventCommand(id, ApiTestSupport.uniqueKey("bucket-held"), new BigDecimal("5"),
                null, null, null, null, null, Actor.system());
        Held<?> holder = hold(() -> reservations.confirm(event));
        Future<ReservationResult> waiting = reserveAsync(null, "10");
        awaitLockQuery("budget_bucket", waiting);
        CLOCK.set(boundary);
        holder.commit();
        var result = waiting.get(30, TimeUnit.SECONDS);
        assertThat(result.reservation().createdAt()).isEqualTo(boundary);
        var buckets = mapper.lockBuckets(reservations.bucketIds(result.reservation().id()));
        assertThat(buckets).anySatisfy(bucket -> assertThat(bucket.periodStart()).isEqualTo(boundary));
    }

    @Test
    void decisionRejectsLatestExpiredRateAndStillReplaysExistingReservation() throws Exception {
        Instant original = CLOCK.instant();
        // The earlier rate expires later than the latest effective rate.
        jdbc.update("""
                INSERT INTO exchange_rate
                    (base_currency, quote_currency, rate, effective_from, valid_until, source, created_by, created_at)
                VALUES ('EUR', 'KRW', 1400, ?, ?, 'MANUAL', ?, ?)
                """, java.sql.Timestamp.from(original.minusSeconds(60)), java.sql.Timestamp.from(original.plusSeconds(604800)),
                admin.userId(), java.sql.Timestamp.from(original));
        var latest = rates.create("EUR", new BigDecimal("1500"), original, null, admin);
        CLOCK.set(latest.validUntil().minusSeconds(1));
        var existing = new ReserveCommand(ApiTestSupport.uniqueKey("fx-ok"), null, "race", "EUR", BigDecimal.ONE);
        var first = reservations.reserve(existing);
        Held<?> holder = holdControlLock();
        var pending = new ReserveCommand(ApiTestSupport.uniqueKey("fx-wait"), null, "race", "EUR", BigDecimal.ONE);
        Future<ReservationResult> waiting = executor.submit(() -> reservations.reserve(pending));
        awaitLockQuery("budget_control", waiting);
        CLOCK.set(latest.validUntil());
        holder.commit();
        assertThat(errorOf(waiting)).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET);
        assertThat(reservations.reserve(existing).reservation()).isEqualTo(first.reservation());
    }

    @Test
    void retriesRealDeadlockAfterRollingBackVictimWrites() throws Exception {
        createRetryProbe();
        var acquired = new CountDownLatch(2);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> updateInOppositeOrder(1, 2, acquired, attempts));
            Future<?> second = executor.submit(() -> updateInOppositeOrder(2, 1, acquired, attempts));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            assertThat(attempts.get()).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT n FROM retry_probe ORDER BY id", Integer.class))
                    .containsExactly(2, 2);
        } finally {
            jdbc.execute("DROP TABLE retry_probe");
        }
    }

    @Test
    void realLockTimeoutExhaustsRetriesAndRollsBackEveryAttempt() throws Exception {
        createRetryProbe();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        Held<?> holder = holdControlLock();
        try {
            assertThatThrownBy(() -> lockRetries.run(() -> {
                attempts.incrementAndGet();
                Integer previous = jdbc.queryForObject("SELECT @@innodb_lock_wait_timeout", Integer.class);
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    incrementProbe(2);
                    controlLock.exclusive();
                } finally {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
                }
            })).isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(attempts.get()).isEqualTo(4);
            holder.commit();
            assertThat(jdbc.queryForList("SELECT n FROM retry_probe ORDER BY id", Integer.class))
                    .containsExactly(0, 0);
        } finally {
            holder.release.countDown();
            jdbc.execute("DROP TABLE retry_probe");
        }
    }

    @Test
    void rejectsParticipatingRetryBeforeCallbackOrSql() {
        createRetryProbe();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var outer = new TransactionTemplate(transactionManager);
        try {
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> lockRetries.run(() -> {
                attempts.incrementAndGet();
                incrementProbe(1);
            }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");
            assertThat(attempts.get()).isZero();
            assertThat(jdbc.queryForList("SELECT n FROM retry_probe ORDER BY id", Integer.class))
                    .containsExactly(0, 0);
        } finally {
            jdbc.execute("DROP TABLE retry_probe");
        }
    }

    private void updateInOppositeOrder(int first, int second, CountDownLatch acquired,
            java.util.concurrent.atomic.AtomicInteger attempts) {
        lockRetries.run(() -> {
            attempts.incrementAndGet();
            incrementProbe(first);
            acquired.countDown();
            try {
                assertThat(acquired.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            incrementProbe(second);
        });
    }

    private void createRetryProbe() {
        jdbc.execute("CREATE TABLE retry_probe (id INT PRIMARY KEY, n INT NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO retry_probe (id, n) VALUES (1, 0), (2, 0)");
    }

    private int incrementProbe(int id) {
        return jdbc.update("UPDATE retry_probe SET n = n + 1 WHERE id = ?", id);
    }

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

package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetReservationService.AdjustBasis;
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * 예산 예약·정산·원장 DB 통합 검증 (T02_BUDGET_LEDGER §2~§6, T02_PLAN §4). 클래스 단위 격리 DB를 쓰고 SYSTEM 한도는
 * 크게 잡아 각 테스트는 자기 채널 정책이나 증감값으로만 검사한다. 각 테스트 끝에 정합성 검사 0건을 확인한다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetLedgerIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final BigDecimal USD_RATE = new BigDecimal("1400.1234567891");
    private static boolean initialized;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private BudgetMapper mapper;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private AdminPrincipal principal;
    private Actor admin;

    @BeforeEach
    void setUp() {
        principal = ApiTestSupport.admin(accounts, jdbc);
        admin = Actor.admin(principal.id());
        synchronized (BudgetLedgerIT.class) {
            if (!initialized) {
                policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000000000"), "Asia/Seoul", null, admin);
                policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000000"), "Asia/Seoul", null, admin);
                rates.create("USD", USD_RATE, null, "test", admin);
                control.setSpending(true, "integration test", control.get().version(), admin);
                initialized = true;
            }
        }
    }

    private void assertConsistent() {
        var result = control.runConsistencyCheck(admin);
        assertThat(result.issues()).isEmpty();
    }

    private long channelWithDailyLimit(String limitKrw) {
        long channelId = channels.create(ApiTestSupport.uniqueKey("budget"), "en", "UTC", null, admin).channel().id();
        policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal(limitKrw), "UTC", null, admin);
        return channelId;
    }

    private EventCommand event(long reservationId, String amount) {
        return new EventCommand(reservationId, ApiTestSupport.uniqueKey("evt"), amount == null ? null : new BigDecimal(amount),
                null, null, null, null, null, Actor.system());
    }

    private EventCommand adminEvent(long reservationId, String amount, String reason) {
        return new EventCommand(reservationId, ApiTestSupport.uniqueKey("adm"), new BigDecimal(amount), "ext-1", null,
                reason, "note", null, admin);
    }

    private static void assertState(BudgetMapper.Reservation r, String reserved, String cost, String costOriginal) {
        assertThat(r.reservedRemainingKrw()).isEqualByComparingTo(reserved);
        assertThat(r.recognizedCostKrw()).isEqualByComparingTo(cost);
        assertThat(r.recognizedCostOriginal()).isEqualByComparingTo(costOriginal);
    }

    /** 설계 문서 §2 계산 예시(USD, r=1400.1234567891)를 실제 DB 흐름으로 재현한다. */
    @Test
    void usdSettlementFollowsDesignExampleAndKeepsLedgerConsistent() throws Exception {
        long channelId = channelWithDailyLimit("100000");
        var reserved = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("usd"), channelId, "image",
                "USD", new BigDecimal("1.00")));
        long id = reserved.reservation().id();
        assertThat(reserved.reservation().estimateKrw()).isEqualByComparingTo("1400.123457");
        assertState(reserved.reservation(), "1400.123457", "0", "0");
        // SYSTEM DAY, SYSTEM MONTH, CHANNEL DAY
        assertThat(reservations.bucketIds(id)).hasSize(3);

        assertState(reservations.recordProvisional(event(id, "0.80")), "280.024692", "1120.098765", "0.80");
        assertState(reservations.recordProvisional(event(id, "0.30")), "980.086420", "420.037037", "0.30");
        assertState(reservations.recordProvisional(event(id, "0.90")), "140.012346", "1260.111111", "0.90");
        BudgetMapper.Reservation confirmed = reservations.confirm(event(id, "1.20"));
        assertThat(confirmed.status()).isEqualTo(BudgetReservationService.SETTLED);
        assertState(confirmed, "0", "1680.148148", "1.20");
        // 예상 초과 → audit BUDGET_OVERRUN
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action = 'BUDGET_OVERRUN' AND entity_id = ?",
                Integer.class, String.valueOf(id))).isEqualTo(1);

        assertState(reservations.adjust(adminEvent(id, "-50", "FX_DIFFERENCE"), "ADJUSTMENT", AdjustBasis.KRW),
                "0", "1630.148148", "1.20");
        assertState(reservations.adjust(adminEvent(id, "0.10", "PARTIAL_REFUND"), "REFUND", AdjustBasis.ORIGINAL),
                "0", "1490.135802", "1.10");

        // 모든 연결 bucket에 같은 증감이 반영되었다(CHANNEL bucket 기준)
        BigDecimal channelSpent = jdbc.queryForObject("""
                SELECT b.spent_krw FROM budget_bucket b JOIN budget_policy p ON p.id = b.policy_id
                 WHERE p.scope = 'CHANNEL' AND p.channel_id = ?""", BigDecimal.class, channelId);
        assertThat(channelSpent).isEqualByComparingTo("1490.135802");

        // 상세 API + 원장 API 계약
        mockMvc.perform(get("/api/v1/budget-reservations/" + id).with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.recognizedCostKrw").value("1490.135802"))
                .andExpect(jsonPath("$.events.length()").value(7))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/cost-ledger?reservationId=" + id).with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(ApiTestSupport.matchesContract());

        // 불변식 위반 환불 → 422, 기록 없음
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM cost_log", Long.class);
        assertThatThrownBy(() -> reservations.adjust(adminEvent(id, "5.00", "PARTIAL_REFUND"), "REFUND",
                AdjustBasis.ORIGINAL))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POLICY_VIOLATION));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log", Long.class)).isEqualTo(events);
        // SETTLED 상태에서 PROVISIONAL → 409
        assertThatThrownBy(() -> reservations.recordProvisional(event(id, "1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertConsistent();
    }

    @Test
    void confirmWithoutProvisionalReleaseAndLateCharge() {
        long channelId = channelWithDailyLimit("100000");
        long direct = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("krw"), channelId, "tts", "KRW",
                new BigDecimal("500"))).reservation().id();
        assertState(reservations.confirm(event(direct, "300")), "0", "300", "300");

        long released = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("krw"), channelId, "tts",
                "KRW", new BigDecimal("400"))).reservation().id();
        assertThat(reservations.release(event(released, null)).status()).isEqualTo(BudgetReservationService.RELEASED);
        // 근거 없는 지연 과금 거부, 근거 있으면 RELEASED → SETTLED(LATE_CHARGE)
        assertThatThrownBy(() -> reservations.confirm(event(released, "50")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        BudgetMapper.Reservation late = reservations.confirm(adminEvent(released, "50", null));
        assertThat(late.status()).isEqualTo(BudgetReservationService.SETTLED);
        assertState(late, "0", "50", "50");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM cost_log WHERE reservation_id = ? AND entry_type = 'CONFIRM'",
                String.class, released)).isEqualTo("LATE_CHARGE");

        // 비용이 인식된 예약은 RELEASE 불가, 불명 상태는 자동 해제 없음·근거 없으면 해제 불가
        long uncertain = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("krw"), channelId, "tts",
                "KRW", new BigDecimal("100"))).reservation().id();
        reservations.recordProvisional(event(uncertain, "10"));
        assertThatThrownBy(() -> reservations.release(event(uncertain, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(reservations.markReconciliationRequired(uncertain, "timeout", Actor.system()).status())
                .isEqualTo(BudgetReservationService.RECONCILIATION_REQUIRED);
        assertThat(reservations.get(uncertain).status()).isEqualTo(BudgetReservationService.RECONCILIATION_REQUIRED);
        assertThat(reservations.confirm(event(uncertain, "10")).status()).isEqualTo(BudgetReservationService.SETTLED);
        assertConsistent();
    }

    @Test
    void krwAdjustmentHttpContractPreservesOnlyForeignOriginalCost() throws Exception {
        for (String currency : List.of("KRW", "USD")) {
            long id = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("adjust"), null,
                    "contract", currency, BigDecimal.ONE)).reservation().id();
            reservations.confirm(event(id, "1"));
            String adjusted = currency.equals("KRW") ? "1.500000000000" : "1.000000000000";
            mockMvc.perform(adjustment(id, "ADJUSTMENT", "KRW", "0.5"))
                    .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                    .andExpect(jsonPath("$.recognizedCostOriginal").value(adjusted));
            mockMvc.perform(adjustment(id, "REFUND", "KRW", "0.5"))
                    .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                    .andExpect(jsonPath("$.recognizedCostOriginal").value("1.000000000000"));
            mockMvc.perform(adjustment(id, "ADJUSTMENT", "ORIGINAL", "0.25"))
                    .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                    .andExpect(jsonPath("$.recognizedCostOriginal").value("1.250000000000"));
            mockMvc.perform(adjustment(id, "ADJUSTMENT", "KRW", "0.0000001"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(adjustment(id, "ADJUSTMENT", "KRW", "1000000000000"))
                    .andExpect(status().isBadRequest());
        }
        assertConsistent();
    }

    @Test
    void acceptsLargestKrwAdjustmentRepresentableInEveryLedgerColumn() throws Exception {
        long id = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("adj-max"), null,
                "contract", "USD", BigDecimal.ONE)).reservation().id();
        reservations.confirm(event(id, "0"));
        String maximum = "999999999999.999999";
        mockMvc.perform(adjustment(id, "ADJUSTMENT", "KRW", maximum))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.recognizedCostKrw").value(maximum));
        mockMvc.perform(adjustment(id, "REFUND", "KRW", maximum))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.recognizedCostKrw").value("0.000000"));
        assertConsistent();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adjustment(
            long id, String entryType, String basis, String amount) {
        return post("/api/v1/budget-reservations/{id}/adjustments", id).with(ApiTestSupport.as(principal))
                .header("Idempotency-Key", ApiTestSupport.uniqueKey("adjust-http"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"entryType":"%s","basis":"%s","amount":"%s","reasonCode":"MANUAL","note":"test"}
                        """.formatted(entryType, basis, amount));
    }

    @Test
    void signedBigintIdBoundaryUsesClientErrorsForPathAndQuery() throws Exception {
        String maximum = Long.toString(Long.MAX_VALUE);
        for (String invalid : List.of("0", "-1", "abc", "9223372036854775808")) {
            mockMvc.perform(get("/api/v1/budget-reservations/{id}", invalid).with(ApiTestSupport.as(principal)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mockMvc.perform(get("/api/v1/cost-ledger").param("reservationId", invalid)
                    .with(ApiTestSupport.as(principal)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String valid : List.of("1", maximum)) {
            mockMvc.perform(get("/api/v1/cost-ledger").param("reservationId", valid)
                    .with(ApiTestSupport.as(principal))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/budget-reservations/{id}", maximum).with(ApiTestSupport.as(principal)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/channels/{id}", "9223372036854775808").with(ApiTestSupport.as(principal)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reserveKeysRemainDistinctAtEveryLengthBoundary() {
        Set<String> eventKeys = new java.util.HashSet<>();
        for (int length = 120; length <= 128; length++) {
            for (String prefix : List.of("a", "b")) {
                String key = prefix + "x".repeat(length - 1);
                ReserveCommand command = new ReserveCommand(key, null, "key-boundary", "KRW", BigDecimal.ONE);
                var first = reservations.reserve(command);
                var replay = reservations.reserve(command);
                assertThat(replay.replayed()).isTrue();
                assertThat(replay.reservation().id()).isEqualTo(first.reservation().id());
                var events = reservations.events(first.reservation().id());
                assertThat(events).hasSize(1);
                assertThat(eventKeys.add(events.getFirst().eventKey())).isTrue();
                assertThat(events.getFirst().requestKey()).isEqualTo(key);
                assertThat(events.getFirst().eventKey()).startsWith("reserve-v2:");
            }
        }
        assertConsistent();
    }

    @Test
    void legacyReserveKeySupportsReplayAndSettlement() {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var current = new ReserveCommand(ApiTestSupport.uniqueKey("new-key"), null, "legacy-replay", "KRW",
                BigDecimal.ONE);
        var source = reservations.reserve(current).reservation();
        var legacy = new ReserveCommand(ApiTestSupport.uniqueKey("legacy-key"), null, current.purpose(),
                current.currency(), current.estimateOriginal());
        long id = template.execute(status -> {
            long reservationId = insertLegacyReserve(source, legacy.requestKey());
            for (long bucketId : reservations.bucketIds(source.id())) {
                mapper.insertReservationBucket(reservationId, bucketId);
                mapper.applyBucketDeltas(bucketId, source.estimateKrw(), BigDecimal.ZERO, source.createdAt());
            }
            return reservationId;
        });
        assertThat(reservations.reserve(legacy).replayed()).isTrue();
        assertThat(reservations.reserve(legacy).reservation().id()).isEqualTo(id);
        reservations.confirm(event(id, "1"));
        assertThat(reservations.events(id)).extracting(BudgetMapper.CostLog::entryType)
                .containsExactly("RESERVE", "CONFIRM");
        assertThat(reservations.events(id).getFirst().eventKey()).isEqualTo("reserve:" + legacy.requestKey());
        assertConsistent();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "DAY", "MONTH", "BOTH", "WRONG_SCOPE", "WRONG_PERIOD", "DUPLICATE_DAY"
    })
    void detectsMissingSystemBucketLinksEvenWhenLedgerSumsMatch(String missing) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Long wrongBucketId = missing.equals("WRONG_SCOPE") ? channelBucketForWrongScopeProbe() : null;
        var command = new ReserveCommand(ApiTestSupport.uniqueKey("link-source"), null, "link-probe", "KRW",
                BigDecimal.ONE);
        var source = reservations.reserve(command).reservation();
        template.executeWithoutResult(status -> {
            long id = insertLegacyReserve(source, ApiTestSupport.uniqueKey("missing-links"));
            for (var bucket : mapper.lockBuckets(reservations.bucketIds(source.id()))) {
                String period = policies.get(bucket.policyId()).policy().periodType();
                if (!missing.equals("BOTH") && !missing.equals("WRONG_SCOPE") && !period.equals(missing)) {
                    mapper.insertReservationBucket(id, bucket.id());
                    mapper.applyBucketDeltas(bucket.id(), source.estimateKrw(), BigDecimal.ZERO, source.createdAt());
                }
                if (period.equals("DAY") && (missing.equals("WRONG_PERIOD") || missing.equals("DUPLICATE_DAY"))) {
                    boolean duplicate = missing.equals("DUPLICATE_DAY");
                    var extra = new BudgetMapper.Generated();
                    mapper.insertBucket(new BudgetMapper.Bucket(null, bucket.policyId(), null,
                            bucket.periodStart().minusSeconds(1),
                            duplicate ? bucket.periodEnd() : bucket.periodStart(), bucket.timezone(),
                            bucket.limitKrw(), bucket.limitPolicyRevisionNo(), BigDecimal.ZERO, BigDecimal.ZERO,
                            0, source.createdAt(), source.createdAt()), extra);
                    mapper.insertReservationBucket(id, extra.id);
                    mapper.applyBucketDeltas(extra.id, source.estimateKrw(), BigDecimal.ZERO, source.createdAt());
                }
            }
            if (wrongBucketId != null) {
                mapper.insertReservationBucket(id, wrongBucketId);
                mapper.applyBucketDeltas(wrongBucketId, source.estimateKrw(), BigDecimal.ZERO, source.createdAt());
            }
            assertThat(reservations.events(id)).hasSize(1);
            var first = control.runConsistencyCheck(admin);
            assertThat(first.issues()).noneMatch(issue -> issue.checkCode().endsWith("_RESERVED_MISMATCH")
                    || issue.checkCode().endsWith("_SPENT_MISMATCH"));
            assertThat(first.issues()).filteredOn(issue -> issue.checkCode().equals("RESERVATION_BUCKET_LINK_MISMATCH"))
                    .singleElement().satisfies(issue -> assertThat(issue.entityId()).isEqualTo(id));
            assertThat(control.get().consistencyStatus()).isEqualTo("ERROR");
            assertThat(control.runConsistencyCheck(admin).issues()).isEqualTo(first.issues());
            status.setRollbackOnly();
        });
        assertConsistent();
    }

    private long channelBucketForWrongScopeProbe() {
        long channel = channelWithDailyLimit("100");
        var linked = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("wrong-scope"),
                channel, "link-probe", "KRW", BigDecimal.ONE));
        return mapper.lockBuckets(reservations.bucketIds(linked.reservation().id())).stream()
                .filter(bucket -> policies.get(bucket.policyId()).policy().scope().equals("CHANNEL"))
                .findFirst().orElseThrow().id();
    }

    private long insertLegacyReserve(BudgetMapper.Reservation source, String requestKey) {
        var generated = new BudgetMapper.Generated();
        mapper.insertReservation(new BudgetMapper.Reservation(null, requestKey, source.requestFingerprint(), null,
                null, source.purpose(), source.originalCurrency(), source.estimateOriginal(), null, source.fxRate(),
                source.estimateKrw(), "ACTIVE", source.estimateKrw(), BigDecimal.ZERO, BigDecimal.ZERO,
                null, 0, source.createdAt(), source.createdAt(), null), generated);
        long id = generated.id;
        mapper.insertCostLog(new BudgetMapper.CostLog(null, "reserve:" + requestKey,
                Fingerprints.of(java.util.Map.of("reservationId", id, "entryType", "RESERVE")), id, requestKey,
                "RESERVE", null, source.originalCurrency(), source.estimateOriginal(), source.fxRate(), null,
                source.estimateKrw(), source.estimateKrw(), BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                "SYSTEM", null, null, source.createdAt(), source.createdAt()));
        return id;
    }

    @Test
    void settlementCannotClaimInternalReserveNamespace() throws Exception {
        long id = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("namespace"), null,
                "contract", "KRW", BigDecimal.ONE)).reservation().id();
        String internalKey = reservations.events(id).getFirst().eventKey();
        mockMvc.perform(post("/api/v1/budget-reservations/{id}/resolve", id).with(ApiTestSupport.as(principal))
                .header("Idempotency-Key", internalKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"NOT_CHARGED\",\"note\":\"test\"}"))
                .andExpect(status().isBadRequest());
        var internal = new EventCommand(id, "reserve-v2:unused-key", null, null, null, null, null, null, Actor.system());
        assertThatThrownBy(() -> reservations.release(internal))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(reservations.events(id)).hasSize(1);
        reservations.release(event(id, null));
        assertConsistent();
    }

    @Test
    void idempotencyKeysAreCaseSensitiveAndRejectDifferentContent() {
        String key = ApiTestSupport.uniqueKey("Idem");
        var first = reservations.reserve(new ReserveCommand(key, null, "script", "KRW", new BigDecimal("10")));
        var replay = reservations.reserve(new ReserveCommand(key, null, "script", "KRW", new BigDecimal("10.000")));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservation().id()).isEqualTo(first.reservation().id());
        assertThatThrownBy(() -> reservations.reserve(new ReserveCommand(key, null, "script", "KRW", new BigDecimal("11"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        var lower = reservations.reserve(new ReserveCommand(key.toLowerCase(), null, "script", "KRW",
                new BigDecimal("10")));
        assertThat(lower.replayed()).isFalse();
        assertThat(lower.reservation().id()).isNotEqualTo(first.reservation().id());

        long id = first.reservation().id();
        EventCommand provisional = event(id, "3");
        reservations.recordProvisional(provisional);
        reservations.recordProvisional(provisional); // 같은 event_key·내용 → 무시
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE reservation_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThatThrownBy(() -> reservations.recordProvisional(new EventCommand(id, provisional.eventKey(),
                new BigDecimal("4"), null, null, null, null, null, Actor.system())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertConsistent();
    }

    @Test
    void concurrentReservationsNeverExceedLimitAndShareOneBucket() throws Exception {
        long channelId = channelWithDailyLimit("1000");
        int threads = 20;
        List<Callable<Object>> tasks = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            String key = ApiTestSupport.uniqueKey("race");
            tasks.add(() -> {
                start.await();
                try {
                    return reservations.reserve(new ReserveCommand(key, channelId, "race", "KRW", new BigDecimal("100")));
                } catch (ApiException e) {
                    return e.errorCode();
                }
            });
        }
        List<Object> results = runConcurrently(tasks, start);
        assertThat(results.stream().filter(r -> r instanceof BudgetReservationService.ReservationResult)).hasSize(10);
        assertThat(results.stream().filter(ErrorCode.BUDGET_EXCEEDED::equals)).hasSize(10);
        List<BigDecimal> reserved = jdbc.queryForList("""
                SELECT b.reserved_krw FROM budget_bucket b JOIN budget_policy p ON p.id = b.policy_id
                 WHERE p.scope = 'CHANNEL' AND p.channel_id = ?""", BigDecimal.class, channelId);
        assertThat(reserved).hasSize(1);
        assertThat(reserved.getFirst()).isEqualByComparingTo("1000");

        // 같은 request_key 동시 재요청 → 예약 1건
        String sameKey = ApiTestSupport.uniqueKey("same");
        long other = channelWithDailyLimit("1000");
        List<Callable<Object>> same = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            same.add(() -> {
                go.await();
                return reservations.reserve(new ReserveCommand(sameKey, other, "race", "KRW", new BigDecimal("100")))
                        .reservation().id();
            });
        }
        Set<Object> ids = new HashSet<>(runConcurrently(same, go));
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE request_key = ?", Integer.class,
                sameKey)).isEqualTo(1);
        assertConsistent();
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks, CountDownLatch start) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void systemUsageCountsChannelSpendOnceAndDashboardMatchesContract() throws Exception {
        long channelId = channelWithDailyLimit("100000");
        BigDecimal before = systemDayReserved();
        reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("dash"), channelId, "dash", "KRW",
                new BigDecimal("700")));
        assertThat(systemDayReserved().subtract(before)).isEqualByComparingTo("700");

        mockMvc.perform(get("/api/v1/dashboard/summary").with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemUsage.length()").value(2))
                .andExpect(jsonPath("$.spendingEnabled").value(true))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/budget/usage?channelId=" + channelId).with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/budget-policies?scope=SYSTEM").with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(post("/api/v1/budget/consistency-checks").with(ApiTestSupport.as(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(post("/api/v1/exchange-rates").with(ApiTestSupport.as(principal))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseCurrency\":\"EUR\",\"rate\":\"1500.5\"}"))
                .andExpect(status().isCreated())
                .andExpect(ApiTestSupport.matchesContract());
    }

    private BigDecimal systemDayReserved() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.reserved_krw), 0) FROM budget_bucket b JOIN budget_policy p ON p.id = b.policy_id
                 WHERE p.scope = 'SYSTEM' AND p.period_type = 'DAY'""", BigDecimal.class);
    }

    @Test
    void ledgerAndImmutableTablesRejectUpdateAndDelete() {
        long id = reservations.reserve(new ReserveCommand(ApiTestSupport.uniqueKey("immut"), null, "x", "KRW",
                new BigDecimal("1"))).reservation().id();
        var statements = new java.util.LinkedHashMap<String, String>();
        statements.put("UPDATE cost_log SET amount_krw = 0 WHERE reservation_id = " + id, "cost_log is append-only");
        statements.put("DELETE FROM cost_log WHERE reservation_id = " + id, "cost_log is append-only");
        statements.put("DELETE FROM budget_reservation_bucket WHERE reservation_id = " + id,
                "budget_reservation_bucket is immutable");
        statements.put("UPDATE exchange_rate SET rate = 1", "exchange_rate is immutable");
        statements.put("DELETE FROM budget_policy_revision", "budget_policy_revision is immutable");
        statements.put("DELETE FROM budget_control", "budget_control row cannot be deleted");
        statements.forEach((sql, message) ->
                assertThatThrownBy(() -> jdbc.update(sql)).as(sql).hasStackTraceContaining(message));
        assertConsistent();
    }
}

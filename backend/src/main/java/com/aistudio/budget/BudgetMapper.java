package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 예산·원장 SQL. 잠금 SELECT는 ADR-055 순서(control → channel → policy → bucket → reservation)로만 호출한다.
 */
@Mapper
interface BudgetMapper {

    // ---------------------------------------------------------------- records
    record Control(int id, boolean spendingEnabled, Instant spendingChangedAt, Long spendingChangedBy,
            String spendingChangeReason, String consistencyStatus, Instant consistencyCheckedAt,
            int consistencyIssueCount, long version, Instant updatedAt) {
    }

    record Rate(Long id, String baseCurrency, String quoteCurrency, BigDecimal rate, Instant effectiveFrom,
            Instant validUntil, String source, String note, long createdBy, Instant createdAt) {
    }

    record Policy(Long id, String scope, Long channelId, String contentType, Long contentId, String periodType,
            String status, int currentRevisionNo, long version, Instant createdAt, Instant updatedAt) {
    }

    record PolicyRevision(Long id, long policyId, int revisionNo, BigDecimal limitKrw, String timezone,
            Instant timezoneEffectiveFrom, String changeNote, long createdBy, Instant createdAt) {
    }

    record Bucket(Long id, long policyId, Long contentId, Instant periodStart, Instant periodEnd, String timezone,
            BigDecimal limitKrw, int limitPolicyRevisionNo, BigDecimal reservedKrw, BigDecimal spentKrw, long version,
            Instant createdAt, Instant updatedAt) {
    }

    record Reservation(Long id, String requestKey, String requestFingerprint, Long channelId, Long contentId,
            String purpose, String originalCurrency, BigDecimal estimateOriginal, Long exchangeRateId, BigDecimal fxRate,
            BigDecimal estimateKrw, String status, BigDecimal reservedRemainingKrw, BigDecimal recognizedCostKrw,
            BigDecimal recognizedCostOriginal, String reconciliationReason, long version, Instant createdAt,
            Instant updatedAt, Instant closedAt) {
    }

    record CostLog(Long id, String eventKey, String eventFingerprint, long reservationId, String requestKey,
            String entryType, String reasonCode, String originalCurrency, BigDecimal originalAmount, BigDecimal fxRate,
            Long exchangeRateId, BigDecimal amountKrw, BigDecimal reserveDeltaKrw, BigDecimal costDeltaKrw,
            BigDecimal costDeltaOriginal, String priceVersion, String externalRef, String actorType, Long actorUserId,
            String traceId, Instant occurredAt, Instant recordedAt) {
    }

    record ConsistencyIssue(String checkCode, String entityType, long entityId, String expected, String actual) {
    }

    final class Generated {
        public Long id;
    }

    // ---------------------------------------------------------------- budget_control
    String CONTROL = "id, spending_enabled, spending_changed_at, spending_changed_by, spending_change_reason,"
            + " consistency_status, consistency_checked_at, consistency_issue_count, version, updated_at";

    @Select("SELECT " + CONTROL + " FROM budget_control WHERE id = 1")
    Control control();

    @Update("""
            UPDATE budget_control SET spending_enabled = #{enabled}, spending_changed_at = #{now},
                   spending_changed_by = #{by}, spending_change_reason = #{reason}, version = version + 1,
                   updated_at = #{now}
             WHERE id = 1 AND version = #{version}
            """)
    int updateSpending(@Param("enabled") boolean enabled, @Param("by") long by, @Param("reason") String reason,
            @Param("version") long version, @Param("now") Instant now);

    @Update("""
            UPDATE budget_control SET consistency_status = #{status}, consistency_checked_at = #{now},
                   consistency_issue_count = #{issues}, version = version + 1, updated_at = #{now}
             WHERE id = 1
            """)
    void updateConsistency(@Param("status") String status, @Param("issues") int issues, @Param("now") Instant now);

    // ---------------------------------------------------------------- exchange_rate
    String RATE = "id, base_currency, quote_currency, rate, effective_from, valid_until, source, note, created_by,"
            + " created_at";

    @Insert("""
            INSERT INTO exchange_rate (base_currency, quote_currency, rate, effective_from, valid_until, source, note,
                                       created_by, created_at)
            VALUES (#{r.baseCurrency}, 'KRW', #{r.rate}, #{r.effectiveFrom}, #{r.validUntil}, 'MANUAL', #{r.note},
                    #{r.createdBy}, #{r.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "g.id", keyColumn = "id")
    void insertRate(@Param("r") Rate rate, @Param("g") Generated generated);

    @Select("SELECT " + RATE + " FROM exchange_rate WHERE id = #{id}")
    Rate rate(long id);

    /** 가장 최근 발효 환율 1건(만료 여부는 호출자가 판단, 이전 환율 fallback 금지 — ADR-046). */
    @Select("SELECT " + RATE + " FROM exchange_rate WHERE base_currency = #{base} AND quote_currency = 'KRW'"
            + " AND effective_from <= #{at} ORDER BY effective_from DESC LIMIT 1")
    Rate latestEffectiveRate(@Param("base") String base, @Param("at") Instant at);

    @Select("""
            <script>
            SELECT\s""" + RATE + """
             FROM exchange_rate <where><if test="base != null">base_currency = #{base}</if></where>
            ORDER BY effective_from DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Rate> rates(@Param("base") String base, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM exchange_rate <where><if test="base != null">base_currency = #{base}</if></where>
            </script>
            """)
    long countRates(@Param("base") String base);

    // ---------------------------------------------------------------- budget_policy
    String POLICY = "id, scope, channel_id, content_type, content_id, period_type, status, current_revision_no, version,"
            + " created_at, updated_at";

    @Insert("""
            INSERT INTO budget_policy (scope, channel_id, content_type, period_type, status, current_revision_no,
                                       version, created_at, updated_at)
            VALUES (#{p.scope}, #{p.channelId}, #{p.contentType}, #{p.periodType}, 'ACTIVE', 1, 0, #{p.createdAt},
                    #{p.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "g.id", keyColumn = "id")
    void insertPolicy(@Param("p") Policy policy, @Param("g") Generated generated);

    @Select("SELECT " + POLICY + " FROM budget_policy WHERE id = #{id}")
    Policy policy(long id);

    @Select("SELECT " + POLICY + " FROM budget_policy WHERE id = #{id} FOR UPDATE")
    Policy lockPolicy(long id);

    /** 예약에 적용할 활성 정책(SYSTEM + 해당 채널)을 id 순으로 잠근다. */
    @Select("""
            <script>
            SELECT\s""" + POLICY + """
             FROM budget_policy
             WHERE status = 'ACTIVE' AND (scope = 'SYSTEM'
                   <if test="channelId != null">OR (scope = 'CHANNEL' AND channel_id = #{channelId})</if>)
             ORDER BY id FOR UPDATE
            </script>
            """)
    List<Policy> lockActivePolicies(@Param("channelId") Long channelId);

    @Select("""
            <script>
            SELECT\s""" + POLICY + """
             FROM budget_policy
             WHERE status = 'ACTIVE' AND (scope = 'SYSTEM'
                   <if test="channelId != null">OR (scope = 'CHANNEL' AND channel_id = #{channelId})</if>)
             ORDER BY id
            </script>
            """)
    List<Policy> activePolicies(@Param("channelId") Long channelId);

    @Select("""
            <script>
            SELECT\s""" + POLICY + """
             FROM budget_policy
            <where>
              <if test="scope != null">AND scope = #{scope}</if>
              <if test="channelId != null">AND channel_id = #{channelId}</if>
              <if test="status != null">AND status = #{status}</if>
            </where>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Policy> policies(@Param("scope") String scope, @Param("channelId") Long channelId,
            @Param("status") String status, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM budget_policy
            <where>
              <if test="scope != null">AND scope = #{scope}</if>
              <if test="channelId != null">AND channel_id = #{channelId}</if>
              <if test="status != null">AND status = #{status}</if>
            </where>
            </script>
            """)
    long countPolicies(@Param("scope") String scope, @Param("channelId") Long channelId, @Param("status") String status);

    @Update("""
            UPDATE budget_policy SET current_revision_no = #{revisionNo}, version = version + 1, updated_at = #{now}
             WHERE id = #{id} AND version = #{version}
            """)
    int updatePolicyRevision(@Param("id") long id, @Param("revisionNo") int revisionNo, @Param("version") long version,
            @Param("now") Instant now);

    @Update("""
            UPDATE budget_policy SET status = #{status}, version = version + 1, updated_at = #{now}
             WHERE id = #{id} AND version = #{version}
            """)
    int updatePolicyStatus(@Param("id") long id, @Param("status") String status, @Param("version") long version,
            @Param("now") Instant now);

    // ---------------------------------------------------------------- budget_policy_revision
    String REVISION = "id, policy_id, revision_no, limit_krw, timezone, timezone_effective_from, change_note,"
            + " created_by, created_at";

    @Insert("""
            INSERT INTO budget_policy_revision (policy_id, revision_no, limit_krw, timezone, timezone_effective_from,
                                                change_note, created_by, created_at)
            VALUES (#{policyId}, #{revisionNo}, #{limitKrw}, #{timezone}, #{timezoneEffectiveFrom}, #{changeNote},
                    #{createdBy}, #{createdAt})
            """)
    void insertRevision(PolicyRevision revision);

    @Select("SELECT " + REVISION + " FROM budget_policy_revision WHERE policy_id = #{policyId} ORDER BY revision_no")
    List<PolicyRevision> allRevisions(long policyId);

    @Select("SELECT " + REVISION + " FROM budget_policy_revision WHERE policy_id = #{policyId}"
            + " AND revision_no = #{revisionNo}")
    PolicyRevision revision(@Param("policyId") long policyId, @Param("revisionNo") int revisionNo);

    @Select("SELECT " + REVISION + " FROM budget_policy_revision WHERE policy_id = #{policyId}"
            + " ORDER BY revision_no DESC LIMIT #{limit} OFFSET #{offset}")
    List<PolicyRevision> revisions(@Param("policyId") long policyId, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM budget_policy_revision WHERE policy_id = #{policyId}")
    long countRevisions(long policyId);

    // ---------------------------------------------------------------- budget_bucket
    String BUCKET = "id, policy_id, content_id, period_start, period_end, timezone, limit_krw, limit_policy_revision_no,"
            + " reserved_krw, spent_krw, version, created_at, updated_at";

    @Select("SELECT " + BUCKET + " FROM budget_bucket WHERE policy_id = #{policyId} AND subject_key = 0"
            + " AND period_start = #{start}")
    Bucket bucketByStart(@Param("policyId") long policyId, @Param("start") Instant start);

    @Select("SELECT " + BUCKET + " FROM budget_bucket WHERE policy_id = #{policyId} AND subject_key = 0"
            + " AND period_start <= #{at} AND period_end > #{at}")
    Bucket bucketCovering(@Param("policyId") long policyId, @Param("at") Instant at);

    @Insert("""
            INSERT INTO budget_bucket (policy_id, period_start, period_end, timezone, limit_krw,
                                       limit_policy_revision_no, reserved_krw, spent_krw, version, created_at,
                                       updated_at)
            VALUES (#{b.policyId}, #{b.periodStart}, #{b.periodEnd}, #{b.timezone}, #{b.limitKrw},
                    #{b.limitPolicyRevisionNo}, 0, 0, 0, #{b.createdAt}, #{b.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "g.id", keyColumn = "id")
    void insertBucket(@Param("b") Bucket bucket, @Param("g") Generated generated);

    @Select("""
            <script>
            SELECT\s""" + BUCKET + """
             FROM budget_bucket WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY id FOR UPDATE
            </script>
            """)
    List<Bucket> lockBuckets(@Param("ids") List<Long> ids);

    @Select("SELECT " + BUCKET + " FROM budget_bucket WHERE policy_id = #{policyId} AND period_end > #{now}"
            + " ORDER BY id FOR UPDATE")
    List<Bucket> lockOpenBuckets(@Param("policyId") long policyId, @Param("now") Instant now);

    @Update("""
            UPDATE budget_bucket SET reserved_krw = reserved_krw + #{reserveDelta}, spent_krw = spent_krw + #{costDelta},
                   version = version + 1, updated_at = #{now}
             WHERE id = #{id}
            """)
    void applyBucketDeltas(@Param("id") long id, @Param("reserveDelta") BigDecimal reserveDelta,
            @Param("costDelta") BigDecimal costDelta, @Param("now") Instant now);

    @Update("""
            UPDATE budget_bucket SET limit_krw = #{limit}, limit_policy_revision_no = #{revisionNo},
                   version = version + 1, updated_at = #{now}
             WHERE id = #{id}
            """)
    void updateBucketLimit(@Param("id") long id, @Param("limit") BigDecimal limit, @Param("revisionNo") int revisionNo,
            @Param("now") Instant now);

    @Select("""
            <script>
            SELECT\s""" + BUCKET + """
             FROM budget_bucket WHERE policy_id = #{policyId}
              <if test="from != null">AND period_end &gt; #{from}</if>
              <if test="to != null">AND period_start &lt; #{to}</if>
             ORDER BY period_start DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Bucket> buckets(@Param("policyId") long policyId, @Param("from") Instant from, @Param("to") Instant to,
            @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM budget_bucket WHERE policy_id = #{policyId}
              <if test="from != null">AND period_end &gt; #{from}</if>
              <if test="to != null">AND period_start &lt; #{to}</if>
            </script>
            """)
    long countBuckets(@Param("policyId") long policyId, @Param("from") Instant from, @Param("to") Instant to);

    // ---------------------------------------------------------------- budget_reservation
    String RESERVATION = "id, request_key, request_fingerprint, channel_id, content_id, purpose, original_currency,"
            + " estimate_original, exchange_rate_id, fx_rate, estimate_krw, status, reserved_remaining_krw,"
            + " recognized_cost_krw, recognized_cost_original, reconciliation_reason, version, created_at, updated_at,"
            + " closed_at";

    @Insert("""
            INSERT INTO budget_reservation (request_key, request_fingerprint, channel_id, purpose, original_currency,
                   estimate_original, exchange_rate_id, fx_rate, estimate_krw, status, reserved_remaining_krw,
                   recognized_cost_krw, recognized_cost_original, version, created_at, updated_at)
            VALUES (#{r.requestKey}, #{r.requestFingerprint}, #{r.channelId}, #{r.purpose}, #{r.originalCurrency},
                   #{r.estimateOriginal}, #{r.exchangeRateId}, #{r.fxRate}, #{r.estimateKrw}, 'ACTIVE',
                   #{r.estimateKrw}, 0, 0, 0, #{r.createdAt}, #{r.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "g.id", keyColumn = "id")
    void insertReservation(@Param("r") Reservation reservation, @Param("g") Generated generated);

    @Select("SELECT " + RESERVATION + " FROM budget_reservation WHERE request_key = #{requestKey}")
    Reservation reservationByRequestKey(String requestKey);

    @Select("SELECT " + RESERVATION + " FROM budget_reservation WHERE id = #{id}")
    Reservation reservation(long id);

    @Select("SELECT " + RESERVATION + " FROM budget_reservation WHERE id = #{id} FOR UPDATE")
    Reservation lockReservation(long id);

    @Update("""
            UPDATE budget_reservation
               SET status = #{status}, reserved_remaining_krw = #{reservedRemaining}, recognized_cost_krw = #{cost},
                   recognized_cost_original = #{costOriginal}, reconciliation_reason = #{reason},
                   closed_at = #{closedAt}, version = version + 1, updated_at = #{now}
             WHERE id = #{id}
            """)
    void updateReservation(@Param("id") long id, @Param("status") String status,
            @Param("reservedRemaining") BigDecimal reservedRemaining, @Param("cost") BigDecimal cost,
            @Param("costOriginal") BigDecimal costOriginal, @Param("reason") String reason,
            @Param("closedAt") Instant closedAt, @Param("now") Instant now);

    @Select("""
            <script>
            SELECT\s""" + RESERVATION + """
             FROM budget_reservation
            <where>
              <if test="status != null">AND status = #{status}</if>
              <if test="channelId != null">AND channel_id = #{channelId}</if>
            </where>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Reservation> reservations(@Param("status") String status, @Param("channelId") Long channelId,
            @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM budget_reservation
            <where>
              <if test="status != null">AND status = #{status}</if>
              <if test="channelId != null">AND channel_id = #{channelId}</if>
            </where>
            </script>
            """)
    long countReservations(@Param("status") String status, @Param("channelId") Long channelId);

    @Insert("INSERT INTO budget_reservation_bucket (reservation_id, bucket_id) VALUES (#{reservationId}, #{bucketId})")
    void insertReservationBucket(@Param("reservationId") long reservationId, @Param("bucketId") long bucketId);

    @Select("SELECT bucket_id FROM budget_reservation_bucket WHERE reservation_id = #{reservationId} ORDER BY bucket_id")
    List<Long> reservationBucketIds(long reservationId);

    // ---------------------------------------------------------------- cost_log
    String COST_LOG = "id, event_key, event_fingerprint, reservation_id, request_key, entry_type, reason_code,"
            + " original_currency, original_amount, fx_rate, exchange_rate_id, amount_krw, reserve_delta_krw,"
            + " cost_delta_krw, cost_delta_original, price_version, external_ref, actor_type, actor_user_id, trace_id,"
            + " occurred_at, recorded_at";

    String L_COST_LOG = "l.id, l.event_key, l.event_fingerprint, l.reservation_id, l.request_key, "
            + " l.entry_type, l.reason_code, l.original_currency, l.original_amount, l.fx_rate, "
            + " l.exchange_rate_id, l.amount_krw, l.reserve_delta_krw, l.cost_delta_krw, "
            + " l.cost_delta_original, l.price_version, l.external_ref, l.actor_type, l.actor_user_id, "
            + " l.trace_id, l.occurred_at, l.recorded_at";

    @Insert("""
            INSERT INTO cost_log (event_key, event_fingerprint, reservation_id, request_key, entry_type, reason_code,
                   original_currency, original_amount, fx_rate, exchange_rate_id, amount_krw, reserve_delta_krw,
                   cost_delta_krw, cost_delta_original, price_version, external_ref, actor_type, actor_user_id,
                   trace_id, occurred_at, recorded_at)
            VALUES (#{eventKey}, #{eventFingerprint}, #{reservationId}, #{requestKey}, #{entryType}, #{reasonCode},
                   #{originalCurrency}, #{originalAmount}, #{fxRate}, #{exchangeRateId}, #{amountKrw},
                   #{reserveDeltaKrw}, #{costDeltaKrw}, #{costDeltaOriginal}, #{priceVersion}, #{externalRef},
                   #{actorType}, #{actorUserId}, #{traceId}, #{occurredAt}, #{recordedAt})
            """)
    void insertCostLog(CostLog entry);

    @Select("SELECT " + COST_LOG + " FROM cost_log WHERE event_key = #{eventKey}")
    CostLog costLogByEventKey(String eventKey);

    @Select("SELECT " + COST_LOG + " FROM cost_log WHERE reservation_id = #{reservationId} ORDER BY id")
    List<CostLog> costLogsOfReservation(long reservationId);

    @Select("""
            <script>
            SELECT\s""" + L_COST_LOG + """
             FROM cost_log l
            <if test="channelId != null">JOIN budget_reservation r ON r.id = l.reservation_id</if>
            <where>
              <if test="channelId != null">AND r.channel_id = #{channelId}</if>
              <if test="reservationId != null">AND l.reservation_id = #{reservationId}</if>
              <if test="requestKey != null">AND l.request_key = #{requestKey}</if>
              <if test="entryType != null">AND l.entry_type = #{entryType}</if>
              <if test="from != null">AND l.recorded_at &gt;= #{from}</if>
              <if test="to != null">AND l.recorded_at &lt; #{to}</if>
            </where>
            ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CostLog> costLogs(@Param("channelId") Long channelId, @Param("reservationId") Long reservationId,
            @Param("requestKey") String requestKey, @Param("entryType") String entryType, @Param("from") Instant from,
            @Param("to") Instant to, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM cost_log l
            <if test="channelId != null">JOIN budget_reservation r ON r.id = l.reservation_id</if>
            <where>
              <if test="channelId != null">AND r.channel_id = #{channelId}</if>
              <if test="reservationId != null">AND l.reservation_id = #{reservationId}</if>
              <if test="requestKey != null">AND l.request_key = #{requestKey}</if>
              <if test="entryType != null">AND l.entry_type = #{entryType}</if>
              <if test="from != null">AND l.recorded_at &gt;= #{from}</if>
              <if test="to != null">AND l.recorded_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countCostLogs(@Param("channelId") Long channelId, @Param("reservationId") Long reservationId,
            @Param("requestKey") String requestKey, @Param("entryType") String entryType, @Param("from") Instant from,
            @Param("to") Instant to);

    // ---------------------------------------------------------------- consistency (docs T02_BUDGET_LEDGER §9)
    @Select("""
            SELECT 'RESERVATION_RESERVED_MISMATCH' AS check_code, 'RESERVATION' AS entity_type, r.id AS entity_id,
                   CAST(COALESCE(SUM(l.reserve_delta_krw), 0) AS CHAR) AS expected,
                   CAST(r.reserved_remaining_krw AS CHAR) AS actual
              FROM budget_reservation r LEFT JOIN cost_log l ON l.reservation_id = r.id
             GROUP BY r.id, r.reserved_remaining_krw
            HAVING COALESCE(SUM(l.reserve_delta_krw), 0) <> r.reserved_remaining_krw
            UNION ALL
            SELECT 'RESERVATION_COST_MISMATCH', 'RESERVATION', r.id, CAST(COALESCE(SUM(l.cost_delta_krw), 0) AS CHAR),
                   CAST(r.recognized_cost_krw AS CHAR)
              FROM budget_reservation r LEFT JOIN cost_log l ON l.reservation_id = r.id
             GROUP BY r.id, r.recognized_cost_krw
            HAVING COALESCE(SUM(l.cost_delta_krw), 0) <> r.recognized_cost_krw
            UNION ALL
            SELECT 'RESERVATION_ORIGINAL_COST_MISMATCH', 'RESERVATION', r.id,
                   CAST(COALESCE(SUM(l.cost_delta_original), 0) AS CHAR), CAST(r.recognized_cost_original AS CHAR)
              FROM budget_reservation r LEFT JOIN cost_log l ON l.reservation_id = r.id
             GROUP BY r.id, r.recognized_cost_original
            HAVING COALESCE(SUM(l.cost_delta_original), 0) <> r.recognized_cost_original
            UNION ALL
            SELECT 'BUCKET_RESERVED_MISMATCH', 'BUCKET', b.id, CAST(COALESCE(x.reserved, 0) AS CHAR),
                   CAST(b.reserved_krw AS CHAR)
              FROM budget_bucket b LEFT JOIN (
                   SELECT rb.bucket_id, SUM(l.reserve_delta_krw) AS reserved, SUM(l.cost_delta_krw) AS spent
                     FROM budget_reservation_bucket rb JOIN cost_log l ON l.reservation_id = rb.reservation_id
                    GROUP BY rb.bucket_id) x ON x.bucket_id = b.id
             WHERE COALESCE(x.reserved, 0) <> b.reserved_krw
            UNION ALL
            SELECT 'BUCKET_SPENT_MISMATCH', 'BUCKET', b.id, CAST(COALESCE(x.spent, 0) AS CHAR), CAST(b.spent_krw AS CHAR)
              FROM budget_bucket b LEFT JOIN (
                   SELECT rb.bucket_id, SUM(l.cost_delta_krw) AS spent
                     FROM budget_reservation_bucket rb JOIN cost_log l ON l.reservation_id = rb.reservation_id
                    GROUP BY rb.bucket_id) x ON x.bucket_id = b.id
             WHERE COALESCE(x.spent, 0) <> b.spent_krw
            UNION ALL
            SELECT 'BUCKET_OVERLAP', 'BUCKET', b1.id, CAST(b2.id AS CHAR), 'overlapping bucket'
              FROM budget_bucket b1 JOIN budget_bucket b2
                ON b1.policy_id = b2.policy_id AND b1.subject_key = b2.subject_key AND b1.id < b2.id
               AND b1.period_start < b2.period_end AND b2.period_start < b1.period_end
            UNION ALL
            SELECT 'BUCKET_REVISION_MISMATCH', 'BUCKET', b.id, CAST(pr.limit_krw AS CHAR), CAST(b.limit_krw AS CHAR)
              FROM budget_bucket b JOIN budget_policy_revision pr
                ON pr.policy_id = b.policy_id AND pr.revision_no = b.limit_policy_revision_no
             WHERE pr.limit_krw <> b.limit_krw
            UNION ALL
            SELECT 'BUCKET_REVISION_MISMATCH', 'BUCKET', b.id, CAST(p.current_revision_no AS CHAR),
                   CAST(b.limit_policy_revision_no AS CHAR)
              FROM budget_bucket b JOIN budget_policy p ON p.id = b.policy_id
             WHERE b.period_end > #{now} AND b.limit_policy_revision_no <> p.current_revision_no
            UNION ALL
            SELECT 'CLOSED_RESERVATION_HAS_RESERVE', 'RESERVATION', r.id, '0', CAST(r.reserved_remaining_krw AS CHAR)
              FROM budget_reservation r
             WHERE r.status IN ('SETTLED', 'RELEASED') AND r.reserved_remaining_krw <> 0
            UNION ALL
            SELECT 'MISSING_RESERVE_EVENT', 'RESERVATION', r.id, '1', CAST(COUNT(l.id) AS CHAR)
              FROM budget_reservation r LEFT JOIN cost_log l ON l.reservation_id = r.id AND l.entry_type = 'RESERVE'
             GROUP BY r.id
            HAVING COUNT(l.id) <> 1
            """)
    List<ConsistencyIssue> consistencyIssues(@Param("now") Instant now);

    @Select("""
            WITH linked_buckets AS (
                SELECT rb.reservation_id, p.scope, p.period_type,
                       CASE WHEN b.content_id IS NULL AND p.created_at <= r.created_at
                                  AND b.period_start <= r.created_at AND r.created_at < b.period_end
                                  AND p.period_type IN ('DAY', 'MONTH')
                                  AND (p.scope = 'SYSTEM' OR (p.scope = 'CHANNEL' AND p.channel_id = r.channel_id))
                            THEN 1 ELSE 0 END AS valid_link
                  FROM budget_reservation_bucket rb
                  JOIN budget_reservation r ON r.id = rb.reservation_id
                  JOIN budget_bucket b ON b.id = rb.bucket_id
                  JOIN budget_policy p ON p.id = b.policy_id
            ), link_counts AS (
                SELECT r.id,
                       COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'SYSTEM' AND l.period_type = 'DAY'
                                  THEN 1 END) AS system_day,
                       COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'SYSTEM' AND l.period_type = 'MONTH'
                                  THEN 1 END) AS system_month,
                       COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'CHANNEL' AND l.period_type = 'DAY'
                                  THEN 1 END) AS channel_day,
                       COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'CHANNEL' AND l.period_type = 'MONTH'
                                  THEN 1 END) AS channel_month,
                       COUNT(CASE WHEN l.valid_link = 0 THEN 1 END) AS invalid_links
                  FROM budget_reservation r
                  LEFT JOIN linked_buckets l ON l.reservation_id = r.id
                 GROUP BY r.id
            )
            SELECT 'RESERVATION_BUCKET_LINK_MISMATCH' AS check_code, 'RESERVATION' AS entity_type, id AS entity_id,
                   'SYSTEM DAY=1, SYSTEM MONTH=1, CHANNEL DAY<=1, CHANNEL MONTH<=1, invalid=0' AS expected,
                   CONCAT('SYSTEM DAY=', system_day, ', SYSTEM MONTH=', system_month,
                          ', CHANNEL DAY=', channel_day, ', CHANNEL MONTH=', channel_month,
                          ', invalid=', invalid_links) AS actual
              FROM link_counts
             WHERE system_day <> 1 OR system_month <> 1 OR channel_day > 1 OR channel_month > 1 OR invalid_links <> 0
             ORDER BY id
            """)
    List<ConsistencyIssue> reservationBucketIssues();

    @Select("SELECT COUNT(*) FROM budget_reservation WHERE status = 'RECONCILIATION_REQUIRED'")
    long countReconciliationRequired();
}

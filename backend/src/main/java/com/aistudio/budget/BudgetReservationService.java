package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.PeriodCalculator.Period;
import com.aistudio.budget.PeriodCalculator.PeriodType;
import com.aistudio.budget.SettlementMath.Deltas;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.PageResponse.PageQuery;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

/**
 * 비용 예약·정산 내부 서비스 (docs/design/T02_BUDGET_LEDGER.md). 외부 API 호출은 이 서비스의 트랜잭션 밖에서 한다.
 * 잠금 순서(ADR-055): budget_control → channel → budget_policy → budget_bucket → budget_reservation → INSERT.
 * T02에는 실제 provider 호출이 없다(ADR-061, T07 승인 전 금지).
 */
@Service
public class BudgetReservationService {

    public static final String ACTIVE = "ACTIVE";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
    public static final String SETTLED = "SETTLED";
    public static final String RELEASED = "RELEASED";

    private static final int DECISION_ATTEMPTS = 3;
    private static final String LEGACY_RESERVE_PREFIX = "reserve:";
    private static final String HASHED_RESERVE_PREFIX = "reserve-v2:";
    private static final String PRODUCTION_PREFIX = "production:";

    private static final class DecisionPeriodChanged extends RuntimeException {
    }

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9:._-]{8,128}$");
    private static final Pattern REASON = Pattern.compile("^[A-Z_]{2,32}$");
    private static final Logger log = LoggerFactory.getLogger(BudgetReservationService.class);

    /** 예약 요청. estimateOriginal은 원본 통화 금액(소수 ≤ 12). */
    public record ReserveCommand(String requestKey, Long channelId, String purpose, String currency,
            BigDecimal estimateOriginal) {
    }

    /** 결과. replayed=true면 같은 키·같은 내용의 재요청으로 기존 예약을 돌려준 것. */
    public record ReservationResult(BudgetMapper.Reservation reservation, boolean replayed) {
    }

    public enum AdjustBasis { ORIGINAL, KRW }

    private final BudgetMapper mapper;
    private final BudgetControlLock controlLock;
    private final ChannelService channels;
    private final ExchangeRateService rates;
    private final LockRetryingTransactions transactions;
    private final AuditService audit;
    private final Clock clock;

    BudgetReservationService(BudgetMapper mapper, BudgetControlLock controlLock, ChannelService channels,
            ExchangeRateService rates, LockRetryingTransactions transactions, AuditService audit, Clock clock) {
        this.mapper = mapper;
        this.controlLock = controlLock;
        this.channels = channels;
        this.rates = rates;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================ 예약
    public ReservationResult reserve(ReserveCommand command) {
        if (command.requestKey() != null && command.requestKey().startsWith(PRODUCTION_PREFIX)
                && mapper.reservationByRequestKey(command.requestKey()) == null) {
            throw ApiException.validation("requestKey", "uses a production reservation namespace");
        }
        return reserve(command, false);
    }

    private ReservationResult reserve(ReserveCommand command, boolean production) {
        validateKey("requestKey", command.requestKey());
        if (command.purpose() == null || command.purpose().isBlank() || command.purpose().length() > 64) {
            throw ApiException.validation("purpose", "must be 1 to 64 characters");
        }
        String currency = Money.currency("currency", command.currency());
        if (command.estimateOriginal() == null || command.estimateOriginal().signum() <= 0) {
            throw ApiException.validation("estimateOriginal", "must be greater than 0");
        }
        BigDecimal estimate = Money.original(command.estimateOriginal());
        var fields = Fingerprints.fields();
        fields.put("channelId", command.channelId());
        fields.put("contentId", null);
        fields.put("purpose", command.purpose());
        fields.put("currency", currency);
        fields.put("estimateOriginal", Fingerprints.amount(estimate));
        String fingerprint = Fingerprints.of(fields);

        if (production) {
            return doReserve(command, currency, estimate, fingerprint, true);
        }
        BudgetMapper.Reservation existing = mapper.reservationByRequestKey(command.requestKey());
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return transactions.execute(() -> doReserve(command, currency, estimate, fingerprint, false));
                } catch (DecisionPeriodChanged e) {
                    if (attempt == DECISION_ATTEMPTS) {
                        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                                "The budget period changed while acquiring reservation locks.");
                    }
                }
            }
        } catch (DuplicateKeyException e) {
            BudgetMapper.Reservation raced = mapper.reservationByRequestKey(command.requestKey());
            if (raced == null) {
                throw e;
            }
            return replay(raced, fingerprint);
        }
    }

    private ReservationResult replay(BudgetMapper.Reservation existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The request key was already used with different content.");
        }
        return new ReservationResult(existing, true);
    }

    private ReservationResult doReserve(ReserveCommand command, String currency, BigDecimal estimate,
            String fingerprint, boolean controlAlreadyLocked) {
        // 1) control S: 지출 차단·정합성 상태 (Fail Closed, ADR-054, ADR-061)
        if (!controlAlreadyLocked) {
            controlLock.shared();
        }
        Channel channel = command.channelId() == null ? null : channels.lockForReservation(command.channelId());
        List<BudgetMapper.Policy> policies = mapper.lockActivePolicies(command.channelId());
        BudgetMapper.Reservation existing = mapper.reservationByRequestKey(command.requestKey());
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        BudgetMapper.Control control = mapper.control();
        if (!control.spendingEnabled()) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "Spending is disabled by the administrator.");
        }
        if ("ERROR".equals(control.consistencyStatus())) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Budget consistency check failed; new reservations are blocked until it passes.");
        }
        requireSystemPolicies(policies);
        // 2) channel S: ACTIVE만 (ADR-042)
        if (command.channelId() != null) {
            if (channel == null) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Channel not found.");
            }
            if (!Channel.ACTIVE.equals(channel.status())) {
                throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "The channel is not active.");
            }
        }
        // Locate candidate buckets under the policy locks, then take the decision time after
        // waiting for existing bucket writers. Crossing a boundary requires a fresh transaction.
        Instant candidateTime = clock.instant();
        List<Long> bucketIds = new ArrayList<>();
        for (BudgetMapper.Policy policy : policies) {
            bucketIds.add(currentBucket(policy, candidateTime).id());
        }
        List<BudgetMapper.Bucket> buckets = mapper.lockBuckets(bucketIds);
        Instant now = clock.instant();
        if (buckets.stream().anyMatch(bucket -> now.isBefore(bucket.periodStart()) || !now.isBefore(bucket.periodEnd()))) {
            throw new DecisionPeriodChanged();
        }

        // 환율(불변 행, 잠금 없음)
        BigDecimal fxRate = BigDecimal.ONE;
        Long rateId = null;
        if (!Money.KRW.equals(currency)) {
            BudgetMapper.Rate rate = rates.validAt(currency, now).orElseThrow(() -> new ApiException(
                    ErrorCode.PREREQUISITE_NOT_MET, "No valid exchange rate for " + currency + "."));
            fxRate = rate.rate();
            rateId = rate.id();
        }
        BigDecimal estimateKrw = Money.reserveKrw(estimate, fxRate);
        for (BudgetMapper.Bucket bucket : buckets) {
            BigDecimal after = bucket.spentKrw().add(bucket.reservedKrw()).add(estimateKrw);
            if (after.compareTo(bucket.limitKrw()) > 0) {
                throw new ApiException(ErrorCode.BUDGET_EXCEEDED, "The request exceeds the budget limit.");
            }
        }
        // 5) INSERT
        BudgetMapper.Generated generated = new BudgetMapper.Generated();
        mapper.insertReservation(new BudgetMapper.Reservation(null, command.requestKey(), fingerprint,
                command.channelId(), null, command.purpose(), currency, estimate, rateId, Money.rate(fxRate), estimateKrw,
                ACTIVE, estimateKrw, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, now, now, null), generated);
        long reservationId = generated.id;
        for (BudgetMapper.Bucket bucket : buckets) {
            mapper.insertReservationBucket(reservationId, bucket.id());
            mapper.applyBucketDeltas(bucket.id(), estimateKrw, BigDecimal.ZERO, now);
        }
        var eventFields = Fingerprints.fields();
        eventFields.put("reservationId", reservationId);
        eventFields.put("entryType", "RESERVE");
        String eventKey = HASHED_RESERVE_PREFIX + Fingerprints.of(Map.of("requestKey", command.requestKey()));
        mapper.insertCostLog(new BudgetMapper.CostLog(null, eventKey, Fingerprints.of(eventFields), reservationId,
                command.requestKey(), "RESERVE", null, currency, estimate, Money.rate(fxRate), rateId, estimateKrw,
                estimateKrw, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "SYSTEM", null, MDC.get("traceId"), now,
                now));
        return new ReservationResult(mapper.reservation(reservationId), false);
    }

    private void requireSystemPolicies(List<BudgetMapper.Policy> policies) {
        if (!hasSystemPolicies(policies)) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Active SYSTEM DAY and MONTH budget policies are required before spending.");
        }
    }

    private boolean hasSystemPolicies(List<BudgetMapper.Policy> policies) {
        boolean day = false;
        boolean month = false;
        for (BudgetMapper.Policy policy : policies) {
            if (BudgetPolicyService.SYSTEM.equals(policy.scope())) {
                day |= "DAY".equals(policy.periodType());
                month |= "MONTH".equals(policy.periodType());
            }
        }
        return day && month;
    }

    /** 정책 행 잠금 하에서 현재 bucket을 찾거나 만든다(동시 생성은 정책 잠금으로 직렬화, UNIQUE는 최종 방어). */
    private BudgetMapper.Bucket currentBucket(BudgetMapper.Policy policy, Instant now) {
        BudgetMapper.Bucket covering = mapper.bucketCovering(policy.id(), now);
        if (covering != null) {
            return covering;
        }
        List<BudgetMapper.PolicyRevision> revisions = mapper.allRevisions(policy.id());
        Period period = PeriodCalculator.periodAt(BudgetPolicyService.segments(revisions),
                PeriodType.valueOf(policy.periodType()), now);
        BudgetMapper.PolicyRevision current = revisions.getLast();
        BudgetMapper.Generated generated = new BudgetMapper.Generated();
        mapper.insertBucket(new BudgetMapper.Bucket(null, policy.id(), null, period.start(), period.end(),
                period.timezone(), current.limitKrw(), current.revisionNo(), BigDecimal.ZERO, BigDecimal.ZERO, 0, now,
                now), generated);
        return mapper.bucketByStart(policy.id(), period.start());
    }

    // ================================================================ 정산 이벤트
    /** 공통 이벤트 입력. amount는 이벤트 유형별 의미(총액/증감/환불액). */
    public record EventCommand(long reservationId, String eventKey, BigDecimal amount, String externalRef,
            String priceVersion, String reasonCode, String note, Instant occurredAt, Actor actor) {
    }

    public BudgetMapper.Reservation recordProvisional(EventCommand c) {
        requirePositiveOrZero(c.amount(), "amount");
        return applyEvent(c, "PROVISIONAL", null, false, (r, s) -> {
            requireStatus(r, ACTIVE, RECONCILIATION_REQUIRED);
            Deltas d = SettlementMath.provisional(s, Money.original(c.amount()));
            return new Transition(d, r.originalCurrency(), ACTIVE, null, null, null);
        });
    }

    public BudgetMapper.Reservation confirm(EventCommand c) {
        return confirm(c, false);
    }

    private BudgetMapper.Reservation confirm(EventCommand c, boolean production) {
        requirePositiveOrZero(c.amount(), "amount");
        return applyEvent(c, "CONFIRM", null, production, (r, s) -> {
            requireStatus(r, ACTIVE, RECONCILIATION_REQUIRED, RELEASED);
            String reason = c.reasonCode();
            if (RELEASED.equals(r.status())) {
                // 지연 과금: 근거·사유 필수(ADR-048)
                if (blank(c.externalRef()) || blank(c.note())) {
                    throw ApiException.validation("externalRef", "late charge needs externalRef and note");
                }
                reason = "LATE_CHARGE";
            }
            Deltas d = SettlementMath.confirm(s, Money.original(c.amount()));
            return new Transition(d, r.originalCurrency(), SETTLED, reason, null, clock.instant());
        });
    }

    public BudgetMapper.Reservation release(EventCommand c) {
        return release(c, false);
    }

    private BudgetMapper.Reservation release(EventCommand c, boolean production) {
        return applyEvent(c, "RELEASE", null, production, (r, s) -> {
            requireStatus(r, ACTIVE, RECONCILIATION_REQUIRED);
            if (r.recognizedCostKrw().signum() != 0) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "A reservation with recognized cost must be confirmed, not released.");
            }
            if (RECONCILIATION_REQUIRED.equals(r.status()) && blank(c.note())) {
                throw ApiException.validation("note", "releasing an uncertain reservation needs a reason");
            }
            return new Transition(SettlementMath.release(s), r.originalCurrency(), RELEASED, c.reasonCode(), null,
                    clock.instant());
        });
    }

    /** ADJUSTMENT/REFUND (SETTLED만, ADR-057). basis=KRW이면 원본 통화 인식 비용은 변하지 않는다. */
    public BudgetMapper.Reservation adjust(EventCommand c, String entryType, AdjustBasis basis) {
        if (!"ADJUSTMENT".equals(entryType) && !"REFUND".equals(entryType)) {
            throw ApiException.validation("entryType", "must be ADJUSTMENT or REFUND");
        }
        if (c.reasonCode() == null || !REASON.matcher(c.reasonCode()).matches()) {
            throw ApiException.validation("reasonCode", "must match ^[A-Z_]{2,32}$");
        }
        if (blank(c.note())) {
            throw ApiException.validation("note", "is required");
        }
        if ("REFUND".equals(entryType) && (c.amount() == null || c.amount().signum() <= 0)) {
            throw ApiException.validation("amount", "refund must be greater than 0");
        }
        if ("ADJUSTMENT".equals(entryType) && (c.amount() == null || c.amount().signum() == 0)) {
            throw ApiException.validation("amount", "adjustment must not be 0");
        }
        return applyEvent(c, entryType, basis, false, (r, s) -> {
            requireStatus(r, SETTLED);
            boolean krwOnly = basis == AdjustBasis.KRW && !Money.KRW.equals(r.originalCurrency());
            Deltas d;
            if (krwOnly) {
                BigDecimal amount = Money.krw(c.amount());
                d = "REFUND".equals(entryType) ? SettlementMath.refundKrw(amount) : SettlementMath.adjustKrw(amount);
            } else {
                BigDecimal amount = Money.original(c.amount());
                d = "REFUND".equals(entryType) ? SettlementMath.refundOriginal(s, amount)
                        : SettlementMath.adjustOriginal(s, amount);
            }
            return new Transition(d, krwOnly ? Money.KRW : r.originalCurrency(), SETTLED, c.reasonCode(), null,
                    r.closedAt());
        });
    }

    /** 외부 결과 불명 → RECONCILIATION_REQUIRED (원장 이벤트 없음, 자동 해제 금지). */
    public BudgetMapper.Reservation markReconciliationRequired(long reservationId, String reason, Actor actor) {
        return transactions.execute(() -> {
            controlLock.shared();
            BudgetMapper.Reservation r = mapper.lockReservation(reservationId);
            if (r == null) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            rejectProductionMutation(r);
            if (RECONCILIATION_REQUIRED.equals(r.status())) {
                return r;
            }
            requireStatus(r, ACTIVE);
            mapper.updateReservation(r.id(), RECONCILIATION_REQUIRED, r.reservedRemainingKrw(), r.recognizedCostKrw(),
                    r.recognizedCostOriginal(), reason, null, clock.instant());
            audit.record(actor, "BUDGET_RESERVATION_UNCERTAIN", "BUDGET_RESERVATION", r.id(), Map.of("reason", reason));
            return mapper.reservation(r.id());
        });
    }
    private void rejectProductionMutation(BudgetMapper.Reservation reservation) {
        if (reservation.requestKey().startsWith(PRODUCTION_PREFIX) && mapper.productionManaged(reservation.id())) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "Production reservations require provider evidence.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long settleProduction(EventCommand command, boolean release) {
        if (release) {
            release(command, true);
        } else {
            confirm(command, true);
        }
        return mapper.costLogByEventKey(command.eventKey()).id();
    }

    public enum DispatchBudget { ALLOWED, BLOCKED, PERIOD_ENDED }

    @Transactional(propagation = Propagation.MANDATORY)
    public long createProductionReservation(ReserveCommand command) {
        if (command.requestKey() == null || !command.requestKey().startsWith(PRODUCTION_PREFIX)) {
            throw new IllegalArgumentException("Production reservation key is missing its namespace");
        }
        ReservationResult result;
        try {
            result = reserve(command, true);
        } catch (DecisionPeriodChanged e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The budget period changed during reservation.");
        }
        if (result.replayed()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Production reservation key already exists.");
        }
        return result.reservation().id();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DispatchBudget checkProductionDispatch(long channelId, long reservationId) {
        Channel channel = channels.lockForReservation(channelId);
        List<BudgetMapper.Policy> policies = mapper.lockActivePolicies(channelId);
        BudgetMapper.Control control = mapper.control();
        List<Long> ids = mapper.reservationBucketIds(reservationId);
        List<BudgetMapper.Bucket> buckets = ids.isEmpty() ? List.of() : mapper.lockBuckets(ids);
        BudgetMapper.Reservation reservation = mapper.lockReservation(reservationId);
        if (reservation == null || !ACTIVE.equals(reservation.status())
                || !Objects.equals(reservation.channelId(), channelId)
                || reservation.estimateKrw().compareTo(reservation.reservedRemainingKrw()) != 0) {
            return DispatchBudget.BLOCKED;
        }
        Instant now = clock.instant();
        if (buckets.stream().anyMatch(bucket -> !now.isBefore(bucket.periodEnd()))) {
            return DispatchBudget.PERIOD_ENDED;
        }
        if (channel == null || !Channel.ACTIVE.equals(channel.status()) || !control.spendingEnabled()
                || "ERROR".equals(control.consistencyStatus()) || !hasSystemPolicies(policies)
                || buckets.size() != policies.size() || policies.stream().anyMatch(policy -> buckets.stream()
                .noneMatch(bucket -> bucket.policyId() == policy.id() && !now.isBefore(bucket.periodStart())
                        && bucket.spentKrw().add(bucket.reservedKrw()).compareTo(bucket.limitKrw()) <= 0))) {
            return DispatchBudget.BLOCKED;
        }
        return DispatchBudget.ALLOWED;
    }

    private record Transition(Deltas deltas, String eventCurrency, String newStatus, String reasonCode,
            String reconciliationReason, Instant closedAt) {
    }

    @FunctionalInterface
    private interface TransitionRule {
        Transition apply(BudgetMapper.Reservation reservation, SettlementMath.State state);
    }

    private BudgetMapper.Reservation applyEvent(EventCommand c, String entryType, AdjustBasis basis, boolean production,
            TransitionRule rule) {
        validateKey("eventKey", c.eventKey());
        var fields = Fingerprints.fields();
        fields.put("reservationId", c.reservationId());
        fields.put("entryType", entryType);
        fields.put("basis", basis == null ? null : basis.name());
        fields.put("amount", Fingerprints.amount(c.amount()));
        fields.put("reasonCode", c.reasonCode());
        fields.put("externalRef", c.externalRef());
        String fingerprint = Fingerprints.of(fields);
        BudgetMapper.CostLog existing = mapper.costLogByEventKey(c.eventKey());
        boolean reserveNamespace = c.eventKey().startsWith(LEGACY_RESERVE_PREFIX)
                || c.eventKey().startsWith(HASHED_RESERVE_PREFIX);
        // Continue to replay any historical non-RESERVE events that already used these prefixes.
        if (reserveNamespace && (existing == null || "RESERVE".equals(existing.entryType()))) {
            throw ApiException.validation("eventKey", "uses a namespace reserved for reservation entries");
        }
        if (production) {
            return doApplyEvent(c, entryType, fingerprint, true, rule);
        }
        if (c.eventKey().startsWith(PRODUCTION_PREFIX)
                && (existing == null || existing.requestKey().startsWith(PRODUCTION_PREFIX)
                        && mapper.productionManaged(existing.reservationId()))) {
            throw ApiException.validation("eventKey", "uses a production settlement namespace");
        }
        if (existing != null) {
            return replayEvent(existing, fingerprint);
        }
        try {
            return transactions.execute(() -> doApplyEvent(c, entryType, fingerprint, false, rule));
        } catch (DuplicateKeyException e) {
            BudgetMapper.CostLog raced = mapper.costLogByEventKey(c.eventKey());
            if (raced == null) {
                throw e;
            }
            return replayEvent(raced, fingerprint);
        }
    }

    private BudgetMapper.Reservation replayEvent(BudgetMapper.CostLog existing, String fingerprint) {
        if (!existing.eventFingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The event key was already used with different content.");
        }
        return mapper.reservation(existing.reservationId());
    }

    private BudgetMapper.Reservation doApplyEvent(EventCommand c, String entryType, String fingerprint, boolean production,
            TransitionRule rule) {
        Instant now = clock.instant();
        if (!production) {
            controlLock.shared();
        }
        List<Long> bucketIds = mapper.reservationBucketIds(c.reservationId());
        if (!bucketIds.isEmpty()) {
            mapper.lockBuckets(bucketIds);
        }
        BudgetMapper.Reservation r = mapper.lockReservation(c.reservationId());
        if (r == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!production) {
            rejectProductionMutation(r);
        }
        BudgetMapper.CostLog existing = mapper.costLogByEventKey(c.eventKey());
        if (existing != null) {
            return replayEvent(existing, fingerprint);
        }
        SettlementMath.State state = new SettlementMath.State(r.estimateKrw(), r.reservedRemainingKrw(),
                r.recognizedCostKrw(), r.recognizedCostOriginal(), r.fxRate());
        Transition t = rule.apply(r, state);
        Deltas d = t.deltas();
        BigDecimal newReserved = r.reservedRemainingKrw().add(d.reserveDelta());
        BigDecimal newCost = r.recognizedCostKrw().add(d.costDelta());
        BigDecimal newCostOriginal = r.recognizedCostOriginal().add(d.costDeltaOriginal());
        if (newReserved.signum() < 0 || newCost.signum() < 0 || newCostOriginal.signum() < 0) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "Recognized cost or reservation cannot become negative.");
        }
        boolean krwEvent = Money.KRW.equals(t.eventCurrency());
        mapper.insertCostLog(new BudgetMapper.CostLog(null, c.eventKey(), fingerprint, r.id(), r.requestKey(),
                entryType, t.reasonCode(), t.eventCurrency(), Money.original(d.amountOriginal()),
                krwEvent ? Money.rate(BigDecimal.ONE) : r.fxRate(), krwEvent ? null : r.exchangeRateId(),
                Money.krw(d.amountKrw()), Money.krw(d.reserveDelta()), Money.krw(d.costDelta()),
                Money.original(d.costDeltaOriginal()), c.priceVersion(), c.externalRef(), actorType(c.actor()),
                c.actor().userId(), MDC.get("traceId"), c.occurredAt() == null ? now : c.occurredAt(), now));
        String reconciliationReason = SETTLED.equals(t.newStatus()) || RELEASED.equals(t.newStatus())
                || ACTIVE.equals(t.newStatus()) ? null : r.reconciliationReason();
        mapper.updateReservation(r.id(), t.newStatus(), newReserved, newCost, newCostOriginal, reconciliationReason,
                t.closedAt(), now);
        for (Long bucketId : bucketIds) {
            mapper.applyBucketDeltas(bucketId, d.reserveDelta(), d.costDelta(), now);
        }
        if (newCost.compareTo(r.estimateKrw()) > 0 && d.costDelta().signum() > 0) {
            log.warn("Reservation {} cost {} exceeds estimate {}", r.id(), newCost, r.estimateKrw());
            audit.record(c.actor(), "BUDGET_OVERRUN", AuditService.Outcome.SUCCESS, "BUDGET_RESERVATION", r.id(), null,
                    Map.of("estimateKrw", r.estimateKrw().toPlainString(), "costKrw", newCost.toPlainString()));
        }
        if (c.actor().type() == AuditService.ActorType.ADMIN) {
            audit.record(c.actor(), "BUDGET_LEDGER_" + entryType, "BUDGET_RESERVATION", r.id(),
                    Map.of("eventKey", c.eventKey(), "note", Objects.toString(c.note(), "")));
        }
        return mapper.reservation(r.id());
    }

    // ================================================================ 조회
    @Transactional(readOnly = true)
    public BudgetMapper.Reservation get(long id) {
        BudgetMapper.Reservation r = mapper.reservation(id);
        if (r == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<Long> bucketIds(long reservationId) {
        return mapper.reservationBucketIds(reservationId);
    }

    @Transactional(readOnly = true)
    public List<BudgetMapper.CostLog> events(long reservationId) {
        return mapper.costLogsOfReservation(reservationId);
    }

    @Transactional(readOnly = true)
    public List<BudgetMapper.Reservation> list(String status, Long channelId, PageQuery page) {
        return mapper.reservations(status, channelId, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long count(String status, Long channelId) {
        return mapper.countReservations(status, channelId);
    }

    // ================================================================ helpers
    private static String actorType(Actor actor) {
        return switch (actor.type()) {
            case ADMIN -> "ADMIN";
            case CLI -> "CLI";
            default -> "SYSTEM";
        };
    }

    private static void requireStatus(BudgetMapper.Reservation r, String... allowed) {
        for (String status : allowed) {
            if (status.equals(r.status())) {
                return;
            }
        }
        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                "The reservation status " + r.status() + " does not allow this operation.");
    }

    private static void validateKey(String field, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.validation(field, "must match ^[A-Za-z0-9:._-]{8,128}$");
        }
    }

    private static void requirePositiveOrZero(BigDecimal amount, String field) {
        if (amount == null || amount.signum() < 0) {
            throw ApiException.validation(field, "must be 0 or greater");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

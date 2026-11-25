package com.aistudio.budget;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.budget.BudgetResponses.BucketResponse;
import com.aistudio.budget.BudgetResponses.ConsistencyReportResponse;
import com.aistudio.budget.BudgetResponses.ConsistencyStateResponse;
import com.aistudio.budget.BudgetResponses.ControlResponse;
import com.aistudio.budget.BudgetResponses.CostLogResponse;
import com.aistudio.budget.BudgetResponses.ExchangeRateResponse;
import com.aistudio.budget.BudgetResponses.PolicyResponse;
import com.aistudio.budget.BudgetResponses.PolicyRevisionResponse;
import com.aistudio.budget.BudgetResponses.ReservationDetailResponse;
import com.aistudio.budget.BudgetResponses.ReservationResponse;
import com.aistudio.budget.BudgetResponses.UsageResponse;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.CurrentActor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ApiId;
import com.aistudio.shared.api.PageResponse;
import com.aistudio.shared.api.PageResponse.PageQuery;

/** 예산·환율·예약·원장·대시보드 API (docs/api/openapi.yaml). */
final class BudgetControllers {

    private BudgetControllers() {
    }

    // ------------------------------------------------------------------ 환율
    @RestController
    static class ExchangeRateController {

        record ExchangeRateCreateRequest(@NotNull @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
                @NotNull @Pattern(regexp = "^(0|[1-9][0-9]{0,13})(\\.[0-9]{1,10})?$") String rate,
                Instant effectiveFrom, @Size(max = 500) String note) {
        }

        private final ExchangeRateService service;

        ExchangeRateController(ExchangeRateService service) {
            this.service = service;
        }

        @GetMapping("/api/v1/exchange-rates")
        PageResponse<ExchangeRateResponse> list(
                @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            PageQuery query = new PageQuery(page, size);
            return PageResponse.of(service.list(baseCurrency, query), query, service.count(baseCurrency),
                    ExchangeRateResponse::from);
        }

        @PostMapping("/api/v1/exchange-rates")
        ResponseEntity<ExchangeRateResponse> create(@Valid @RequestBody ExchangeRateCreateRequest body) {
            BudgetMapper.Rate rate = service.create(body.baseCurrency(),
                    Money.parse("rate", body.rate(), Money.RATE_SCALE, 14), body.effectiveFrom(), body.note(),
                    CurrentActor.actor());
            return ResponseEntity.created(URI.create("/api/v1/exchange-rates/" + rate.id()))
                    .body(ExchangeRateResponse.from(rate));
        }

        @GetMapping("/api/v1/exchange-rates/current")
        ExchangeRateResponse current(@RequestParam @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency) {
            return ExchangeRateResponse.from(service.current(baseCurrency));
        }

        @GetMapping("/api/v1/exchange-rates/{rateId}")
        ExchangeRateResponse get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String rateId) {
            return ExchangeRateResponse.from(service.get(ApiId.parse("rateId", rateId)));
        }
    }

    // ------------------------------------------------------------------ 정책
    @RestController
    static class BudgetPolicyController {

        record PolicyCreateRequest(@NotNull @Pattern(regexp = "SYSTEM|CHANNEL|CONTENT_TYPE") String scope,
                @Pattern(regexp = ApiId.PATTERN) String channelId, @Pattern(regexp = "SHORTS|LONGFORM") String contentType,
                @NotNull @Pattern(regexp = "DAY|MONTH|LIFETIME") String periodType,
                @NotNull @Pattern(regexp = "^(0|[1-9][0-9]{0,17})(\\.[0-9]{1,6})?$") String limitKrw,
                @NotBlank @Size(max = 64) String timezone, @Size(max = 500) String changeNote) {
        }

        record RevisionCreateRequest(@NotNull @Min(1) Integer baseRevisionNo,
                @NotNull @Pattern(regexp = "^(0|[1-9][0-9]{0,17})(\\.[0-9]{1,6})?$") String limitKrw,
                @NotBlank @Size(max = 64) String timezone, @Size(max = 500) String changeNote) {
        }

        record VersionRequest(@NotNull @Min(0) Long version) {
        }

        private final BudgetPolicyService service;

        BudgetPolicyController(BudgetPolicyService service) {
            this.service = service;
        }

        @GetMapping("/api/v1/budget-policies")
        PageResponse<PolicyResponse> list(
                @RequestParam(required = false) @Pattern(regexp = "SYSTEM|CHANNEL|CONTENT_TYPE") String scope,
                @RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String channelId,
                @RequestParam(required = false) @Pattern(regexp = "ACTIVE|DISABLED") String status,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            PageQuery query = new PageQuery(page, size);
            Long channel = ApiId.optional("channelId", channelId);
            return PageResponse.of(service.list(scope, channel, status, query), query,
                    service.count(scope, channel, status), PolicyResponse::from);
        }

        @PostMapping("/api/v1/budget-policies")
        ResponseEntity<PolicyResponse> create(@Valid @RequestBody PolicyCreateRequest body) {
            BudgetPolicyService.PolicyView view = service.create(body.scope(), ApiId.optional("channelId", body.channelId()),
                    body.contentType(), body.periodType(), Money.parse("limitKrw", body.limitKrw(), Money.KRW_SCALE, 18),
                    body.timezone(), body.changeNote(), CurrentActor.actor());
            return ResponseEntity.created(URI.create("/api/v1/budget-policies/" + view.policy().id()))
                    .body(PolicyResponse.from(view));
        }

        @GetMapping("/api/v1/budget-policies/{policyId}")
        PolicyResponse get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId) {
            return PolicyResponse.from(service.get(ApiId.parse("policyId", policyId)));
        }

        @GetMapping("/api/v1/budget-policies/{policyId}/revisions")
        PageResponse<PolicyRevisionResponse> revisions(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            long id = ApiId.parse("policyId", policyId);
            PageQuery query = new PageQuery(page, size);
            return PageResponse.of(service.revisions(id, query), query, service.countRevisions(id),
                    PolicyRevisionResponse::from);
        }

        @PostMapping("/api/v1/budget-policies/{policyId}/revisions")
        ResponseEntity<PolicyResponse> revise(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId,
                @Valid @RequestBody RevisionCreateRequest body) {
            BudgetPolicyService.PolicyView view = service.revise(ApiId.parse("policyId", policyId), body.baseRevisionNo(),
                    Money.parse("limitKrw", body.limitKrw(), Money.KRW_SCALE, 18), body.timezone(), body.changeNote(),
                    CurrentActor.actor());
            return ResponseEntity.status(201).body(PolicyResponse.from(view));
        }

        @PostMapping("/api/v1/budget-policies/{policyId}/enable")
        PolicyResponse enable(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId,
                @Valid @RequestBody VersionRequest body) {
            return PolicyResponse.from(service.setStatus(ApiId.parse("policyId", policyId), true, body.version(),
                    CurrentActor.actor()));
        }

        @PostMapping("/api/v1/budget-policies/{policyId}/disable")
        PolicyResponse disable(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId,
                @Valid @RequestBody VersionRequest body) {
            return PolicyResponse.from(service.setStatus(ApiId.parse("policyId", policyId), false, body.version(),
                    CurrentActor.actor()));
        }

        @GetMapping("/api/v1/budget-policies/{policyId}/buckets")
        PageResponse<BucketResponse> buckets(@PathVariable @Pattern(regexp = ApiId.PATTERN) String policyId,
                @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            long id = ApiId.parse("policyId", policyId);
            BudgetMapper.Policy policy = service.get(id).policy();
            PageQuery query = new PageQuery(page, size);
            return PageResponse.of(service.buckets(id, from, to, query), query, service.countBuckets(id, from, to),
                    bucket -> BucketResponse.from(bucket, policy));
        }
    }

    // ------------------------------------------------------------------ 사용량·제어·정합성
    @RestController
    static class BudgetController {

        record SpendingChangeRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 500) String reason,
                @NotNull @Min(0) Long version) {
        }

        private final BudgetUsageService usage;
        private final BudgetControlService control;
        private final java.time.Clock clock;

        BudgetController(BudgetUsageService usage, BudgetControlService control, java.time.Clock clock) {
            this.usage = usage;
            this.control = control;
            this.clock = clock;
        }

        @GetMapping("/api/v1/budget/usage")
        UsageResponse usage(@RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String channelId) {
            List<BucketResponse> buckets = usage.currentUsage(ApiId.optional("channelId", channelId)).stream()
                    .map(BucketResponse::from).toList();
            return new UsageResponse(clock.instant(), channelId, buckets);
        }

        @GetMapping("/api/v1/budget/control")
        ControlResponse control() {
            return ControlResponse.from(control.get());
        }

        @PostMapping("/api/v1/budget/control/spending")
        ControlResponse spending(@Valid @RequestBody SpendingChangeRequest body) {
            return ControlResponse.from(control.setSpending(body.enabled(), body.reason(), body.version(),
                    CurrentActor.actor()));
        }

        @PostMapping("/api/v1/budget/consistency-checks")
        ConsistencyReportResponse consistency() {
            return ConsistencyReportResponse.from(control.runConsistencyCheck(CurrentActor.actor()));
        }
    }

    // ------------------------------------------------------------------ 예약·원장
    @RestController
    static class ReservationController {

        record ResolveRequest(@NotNull @Pattern(regexp = "CHARGED|NOT_CHARGED") String outcome,
                @Pattern(regexp = "^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,12})?$") String finalAmountOriginal,
                @Size(max = 128) String externalRef, @NotBlank @Size(max = 500) String note) {
        }

        record AdjustRequest(@NotNull @Pattern(regexp = "ADJUSTMENT|REFUND") String entryType,
                @NotNull @Pattern(regexp = "ORIGINAL|KRW") String basis,
                @NotNull @Pattern(regexp = "^-?(0|[1-9][0-9]{0,11})(\\.[0-9]{1,12})?$") String amount,
                @NotNull @Pattern(regexp = "^[A-Z_]{2,32}$") String reasonCode, @Size(max = 128) String externalRef,
                @NotBlank @Size(max = 500) String note) {
        }

        private final BudgetReservationService service;

        ReservationController(BudgetReservationService service) {
            this.service = service;
        }

        @GetMapping("/api/v1/budget-reservations")
        PageResponse<ReservationResponse> list(
                @RequestParam(required = false)
                @Pattern(regexp = "ACTIVE|RECONCILIATION_REQUIRED|SETTLED|RELEASED") String status,
                @RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String channelId,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            PageQuery query = new PageQuery(page, size);
            Long channel = ApiId.optional("channelId", channelId);
            return PageResponse.of(service.list(status, channel, query), query, service.count(status, channel),
                    ReservationResponse::from);
        }

        @GetMapping("/api/v1/budget-reservations/{reservationId}")
        ReservationDetailResponse get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String reservationId) {
            return detail(service.get(ApiId.parse("reservationId", reservationId)));
        }

        @PostMapping("/api/v1/budget-reservations/{reservationId}/resolve")
        ReservationDetailResponse resolve(@PathVariable @Pattern(regexp = ApiId.PATTERN) String reservationId,
                @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9:._-]{8,128}$") String idempotencyKey,
                @Valid @RequestBody ResolveRequest body) {
            long id = ApiId.parse("reservationId", reservationId);
            BudgetMapper.Reservation result;
            if ("CHARGED".equals(body.outcome())) {
                if (body.finalAmountOriginal() == null) {
                    throw ApiException.validation("finalAmountOriginal", "is required when outcome is CHARGED");
                }
                result = service.confirm(new BudgetReservationService.EventCommand(id, idempotencyKey,
                        Money.parse("finalAmountOriginal", body.finalAmountOriginal(), Money.ORIGINAL_SCALE, 12),
                        body.externalRef(), null, "OUTCOME_CONFIRMED", body.note(), null, CurrentActor.actor()));
            } else {
                result = service.release(new BudgetReservationService.EventCommand(id, idempotencyKey, null,
                        body.externalRef(), null, "NOT_CHARGED", body.note(), null, CurrentActor.actor()));
            }
            return detail(result);
        }

        @PostMapping("/api/v1/budget-reservations/{reservationId}/adjustments")
        ReservationDetailResponse adjust(@PathVariable @Pattern(regexp = ApiId.PATTERN) String reservationId,
                @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9:._-]{8,128}$") String idempotencyKey,
                @Valid @RequestBody AdjustRequest body) {
            BudgetReservationService.AdjustBasis basis = BudgetReservationService.AdjustBasis.valueOf(body.basis());
            int scale = basis == BudgetReservationService.AdjustBasis.KRW ? Money.KRW_SCALE : Money.ORIGINAL_SCALE;
            BudgetMapper.Reservation result = service.adjust(new BudgetReservationService.EventCommand(
                    ApiId.parse("reservationId", reservationId), idempotencyKey, Money.parse("amount", body.amount(), scale, 12),
                    body.externalRef(), null, body.reasonCode(), body.note(), null, CurrentActor.actor()),
                    body.entryType(), basis);
            return detail(result);
        }

        private ReservationDetailResponse detail(BudgetMapper.Reservation r) {
            return ReservationDetailResponse.from(r, service.bucketIds(r.id()), service.events(r.id()));
        }
    }

    @RestController
    static class CostLedgerController {

        private final BudgetMapper mapper;

        CostLedgerController(BudgetMapper mapper) {
            this.mapper = mapper;
        }

        @GetMapping("/api/v1/cost-ledger")
        PageResponse<CostLogResponse> list(@RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String channelId,
                @RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String reservationId,
                @RequestParam(required = false) @Size(max = 128) String requestKey,
                @RequestParam(required = false)
                @Pattern(regexp = "RESERVE|RELEASE|PROVISIONAL|CONFIRM|ADJUSTMENT|REFUND") String entryType,
                @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
            PageQuery query = new PageQuery(page, size);
            Long channel = ApiId.optional("channelId", channelId);
            Long reservation = ApiId.optional("reservationId", reservationId);
            List<BudgetMapper.CostLog> rows = mapper.costLogs(channel, reservation, requestKey, entryType, from, to,
                    query.offset(), query.size());
            long total = mapper.countCostLogs(channel, reservation, requestKey, entryType, from, to);
            return PageResponse.of(rows, query, total, CostLogResponse::from);
        }
    }

    // ------------------------------------------------------------------ 대시보드
    @RestController
    static class DashboardController {

        record ChannelCounts(long active, long paused, long archived) {
        }

        record DashboardSummary(ChannelCounts channelCounts, List<BucketResponse> systemUsage,
                long reservationsRequiringReconciliation, boolean spendingEnabled,
                ConsistencyStateResponse consistency, Instant generatedAt) {
        }

        private final ChannelService channels;
        private final BudgetUsageService usage;
        private final BudgetControlService control;
        private final java.time.Clock clock;

        DashboardController(ChannelService channels, BudgetUsageService usage, BudgetControlService control,
                java.time.Clock clock) {
            this.channels = channels;
            this.usage = usage;
            this.control = control;
            this.clock = clock;
        }

        /** SYSTEM 예산만 집계하고 CHANNEL 예산을 더하지 않는다(ADR-052). */
        @GetMapping("/api/v1/dashboard/summary")
        DashboardSummary summary() {
            BudgetControlService.ControlView c = control.get();
            return new DashboardSummary(
                    new ChannelCounts(channels.countByStatus(Channel.ACTIVE), channels.countByStatus(Channel.PAUSED),
                            channels.countByStatus(Channel.ARCHIVED)),
                    usage.systemUsage().stream().map(BucketResponse::from).toList(),
                    usage.reconciliationRequiredCount(), c.spendingEnabled(),
                    new ConsistencyStateResponse(c.consistencyStatus(), c.consistencyCheckedAt(), c.issueCount()),
                    clock.instant());
        }
    }
}

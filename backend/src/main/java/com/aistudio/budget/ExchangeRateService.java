package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.PageResponse.PageQuery;

/**
 * 수동 환율 (ADR-027, ADR-046). 불변 등록, effectiveFrom은 now−5분 ~ now+7일, 가장 최근 발효 환율이 만료되면
 * 이전 환율로 fallback하지 않는다.
 */
@Service
public class ExchangeRateService {

    static final Duration MAX_PAST = Duration.ofMinutes(5);
    static final Duration MAX_FUTURE = Duration.ofDays(7);

    private final BudgetMapper mapper;
    private final AuditService audit;
    private final Clock clock;
    private final Duration validity;

    ExchangeRateService(BudgetMapper mapper, AuditService audit, Clock clock,
            @Value("${ai-studio.budget.exchange-rate-validity:PT72H}") Duration validity) {
        if (validity.isNegative() || validity.isZero()) {
            throw new IllegalStateException("ai-studio.budget.exchange-rate-validity must be positive");
        }
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        this.validity = validity;
    }

    @Transactional
    public BudgetMapper.Rate create(String baseCurrency, BigDecimal rate, Instant effectiveFrom, String note,
            Actor actor) {
        Money.currency("baseCurrency", baseCurrency);
        if (Money.KRW.equals(baseCurrency)) {
            throw ApiException.validation("baseCurrency", "KRW rate is fixed at 1 and cannot be registered");
        }
        if (rate.signum() <= 0) {
            throw ApiException.validation("rate", "must be greater than 0");
        }
        Instant now = clock.instant();
        Instant effective = effectiveFrom == null ? now : effectiveFrom;
        if (effective.isBefore(now.minus(MAX_PAST)) || effective.isAfter(now.plus(MAX_FUTURE))) {
            throw ApiException.validation("effectiveFrom", "must be between 5 minutes ago and 7 days ahead");
        }
        BudgetMapper.Rate row = new BudgetMapper.Rate(null, baseCurrency, Money.KRW, Money.rate(rate), effective,
                effective.plus(validity), "MANUAL", note, actor.userId(), now);
        BudgetMapper.Generated generated = new BudgetMapper.Generated();
        try {
            mapper.insertRate(row, generated);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "An exchange rate with the same currency and effective time already exists.");
        }
        audit.record(actor, "EXCHANGE_RATE_CREATE", "EXCHANGE_RATE", generated.id,
                Map.of("baseCurrency", baseCurrency, "rate", Money.rate(rate).toPlainString()));
        return mapper.rate(generated.id);
    }

    /** 시각 at의 유효 환율. 가장 최근 발효 건이 만료면 비어 있다(이전 환율 사용 금지). */
    @Transactional(readOnly = true)
    public Optional<BudgetMapper.Rate> validAt(String baseCurrency, Instant at) {
        BudgetMapper.Rate latest = mapper.latestEffectiveRate(baseCurrency, at);
        if (latest == null || !latest.validUntil().isAfter(at)) {
            return Optional.empty();
        }
        return Optional.of(latest);
    }

    @Transactional(readOnly = true)
    public BudgetMapper.Rate current(String baseCurrency) {
        Money.currency("baseCurrency", baseCurrency);
        return validAt(baseCurrency, clock.instant()).orElseThrow(() -> new ApiException(
                ErrorCode.PREREQUISITE_NOT_MET, "No valid exchange rate for " + baseCurrency + "."));
    }

    @Transactional(readOnly = true)
    public BudgetMapper.Rate get(long id) {
        BudgetMapper.Rate rate = mapper.rate(id);
        if (rate == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return rate;
    }

    @Transactional(readOnly = true)
    public List<BudgetMapper.Rate> list(String baseCurrency, PageQuery page) {
        return mapper.rates(baseCurrency, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long count(String baseCurrency) {
        return mapper.countRates(baseCurrency);
    }
}

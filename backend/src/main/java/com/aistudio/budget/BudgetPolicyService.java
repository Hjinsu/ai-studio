package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.PeriodCalculator.PeriodType;
import com.aistudio.budget.PeriodCalculator.TimezoneSegment;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.PageResponse.PageQuery;
import com.aistudio.shared.db.BudgetControlLock;

/**
 * 예산 정책과 revision (ADR-026, ADR-029, ADR-038, ADR-049). 정책 변경은 control X → policy X → 열린 bucket X 순서.
 * 한도 변경은 열린 bucket에 즉시, 시간대 변경은 다음 기간부터(효력 시각을 revision에 저장).
 */
@Service
public class BudgetPolicyService {

    public static final String SYSTEM = "SYSTEM";
    public static final String CHANNEL = "CHANNEL";
    public static final String CONTENT_TYPE = "CONTENT_TYPE";

    public record PolicyView(BudgetMapper.Policy policy, BudgetMapper.PolicyRevision currentRevision) {
    }

    private final BudgetMapper mapper;
    private final BudgetControlLock controlLock;
    private final ChannelService channels;
    private final AuditService audit;
    private final Clock clock;

    BudgetPolicyService(BudgetMapper mapper, BudgetControlLock controlLock, ChannelService channels, AuditService audit,
            Clock clock) {
        this.mapper = mapper;
        this.controlLock = controlLock;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PolicyView create(String scope, Long channelId, String contentType, String periodType, BigDecimal limitKrw,
            String timezone, String changeNote, Actor actor) {
        validateShape(scope, channelId, contentType, periodType);
        validateTimezone(timezone);
        BigDecimal limit = validLimit(limitKrw);
        controlLock.exclusive();
        if (channelId != null) {
            Channel channel = channels.lockShared(channelId);
            if (Channel.ARCHIVED.equals(channel.status())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Archived channels cannot get new policies.");
            }
        }
        Instant now = clock.instant();
        BudgetMapper.Generated generated = new BudgetMapper.Generated();
        try {
            mapper.insertPolicy(new BudgetMapper.Policy(null, scope, channelId, contentType, null, periodType, "ACTIVE", 1,
                    0, now, now), generated);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A policy with the same scope, target and period type already exists.");
        }
        mapper.insertRevision(new BudgetMapper.PolicyRevision(null, generated.id, 1, limit, timezone, now, changeNote,
                actor.userId(), now));
        audit.record(actor, "BUDGET_POLICY_CREATE", "BUDGET_POLICY", generated.id,
                Map.of("scope", scope, "periodType", periodType, "limitKrw", limit.toPlainString()));
        return get(generated.id);
    }

    @Transactional
    public PolicyView revise(long policyId, int baseRevisionNo, BigDecimal limitKrw, String timezone, String changeNote,
            Actor actor) {
        validateTimezone(timezone);
        BigDecimal limit = validLimit(limitKrw);
        controlLock.exclusive();
        BudgetMapper.Policy policy = mapper.lockPolicy(policyId);
        if (policy == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (policy.currentRevisionNo() != baseRevisionNo) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The policy was changed by another request. Reload and retry.");
        }
        Instant now = clock.instant();
        List<BudgetMapper.PolicyRevision> revisions = mapper.allRevisions(policyId);
        BudgetMapper.PolicyRevision current = revisions.getLast();
        Instant timezoneEffectiveFrom;
        if (current.timezone().equals(timezone)) {
            timezoneEffectiveFrom = current.timezoneEffectiveFrom();
        } else if (PeriodType.valueOf(policy.periodType()) == PeriodType.LIFETIME) {
            timezoneEffectiveFrom = now;
        } else {
            timezoneEffectiveFrom = PeriodCalculator.timezoneEffectiveFrom(segments(revisions),
                    PeriodType.valueOf(policy.periodType()), now);
        }
        int revisionNo = current.revisionNo() + 1;
        mapper.insertRevision(new BudgetMapper.PolicyRevision(null, policyId, revisionNo, limit, timezone,
                timezoneEffectiveFrom, changeNote, actor.userId(), now));
        mapper.updatePolicyRevision(policyId, revisionNo, policy.version(), now);
        for (BudgetMapper.Bucket bucket : mapper.lockOpenBuckets(policyId, now)) {
            mapper.updateBucketLimit(bucket.id(), limit, revisionNo, now);
        }
        audit.record(actor, "BUDGET_POLICY_REVISE", "BUDGET_POLICY", policyId,
                Map.of("revisionNo", revisionNo, "limitKrw", limit.toPlainString(), "timezone", timezone,
                        "timezoneEffectiveFrom", timezoneEffectiveFrom.toString()));
        return get(policyId);
    }

    @Transactional
    public PolicyView setStatus(long policyId, boolean enabled, long version, Actor actor) {
        controlLock.exclusive();
        BudgetMapper.Policy policy = mapper.lockPolicy(policyId);
        if (policy == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (policy.version() != version) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The policy was changed by another request. Reload and retry.");
        }
        String target = enabled ? "ACTIVE" : "DISABLED";
        if (policy.status().equals(target)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The policy is already " + target + ".");
        }
        mapper.updatePolicyStatus(policyId, target, version, clock.instant());
        audit.record(actor, enabled ? "BUDGET_POLICY_ENABLE" : "BUDGET_POLICY_DISABLE", "BUDGET_POLICY", policyId,
                null);
        return get(policyId);
    }

    @Transactional(readOnly = true)
    public PolicyView get(long policyId) {
        BudgetMapper.Policy policy = mapper.policy(policyId);
        if (policy == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new PolicyView(policy, mapper.revision(policyId, policy.currentRevisionNo()));
    }

    @Transactional(readOnly = true)
    public List<PolicyView> list(String scope, Long channelId, String status, PageQuery page) {
        return mapper.policies(scope, channelId, status, page.offset(), page.size()).stream()
                .map(p -> new PolicyView(p, mapper.revision(p.id(), p.currentRevisionNo())))
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String scope, Long channelId, String status) {
        return mapper.countPolicies(scope, channelId, status);
    }

    @Transactional(readOnly = true)
    public List<BudgetMapper.PolicyRevision> revisions(long policyId, PageQuery page) {
        get(policyId);
        return mapper.revisions(policyId, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long countRevisions(long policyId) {
        return mapper.countRevisions(policyId);
    }

    @Transactional(readOnly = true)
    public List<BudgetMapper.Bucket> buckets(long policyId, Instant from, Instant to, PageQuery page) {
        get(policyId);
        return mapper.buckets(policyId, from, to, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long countBuckets(long policyId, Instant from, Instant to) {
        return mapper.countBuckets(policyId, from, to);
    }

    static List<TimezoneSegment> segments(List<BudgetMapper.PolicyRevision> revisionsInOrder) {
        return PeriodCalculator.segments(revisionsInOrder.stream()
                .map(r -> new TimezoneSegment(r.timezoneEffectiveFrom(), r.timezone()))
                .toList());
    }

    private static void validateShape(String scope, Long channelId, String contentType, String periodType) {
        boolean dayOrMonth = "DAY".equals(periodType) || "MONTH".equals(periodType);
        switch (scope) {
            case SYSTEM -> {
                if (channelId != null || contentType != null || !dayOrMonth) {
                    throw ApiException.validation("scope", "SYSTEM policies need DAY or MONTH and no channel/content type");
                }
            }
            case CHANNEL -> {
                if (channelId == null || contentType != null || !dayOrMonth) {
                    throw ApiException.validation("scope", "CHANNEL policies need channelId and DAY or MONTH");
                }
            }
            case CONTENT_TYPE -> {
                if (contentType == null || !"LIFETIME".equals(periodType)) {
                    throw ApiException.validation("scope", "CONTENT_TYPE policies need contentType and LIFETIME");
                }
                if (!contentType.equals("SHORTS") && !contentType.equals("LONGFORM")) {
                    throw ApiException.validation("contentType", "must be SHORTS or LONGFORM");
                }
            }
            default -> throw ApiException.validation("scope", "must be SYSTEM, CHANNEL or CONTENT_TYPE");
        }
    }

    private static BigDecimal validLimit(BigDecimal limitKrw) {
        if (limitKrw == null || limitKrw.signum() < 0) {
            throw ApiException.validation("limitKrw", "must be 0 or greater");
        }
        return Money.krw(limitKrw);
    }

    private static void validateTimezone(String timezone) {
        try {
            if (timezone == null || timezone.length() > 64) {
                throw new DateTimeException("invalid");
            }
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.validation("timezone", "must be a valid IANA time zone");
        }
    }
}

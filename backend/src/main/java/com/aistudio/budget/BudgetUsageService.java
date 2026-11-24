package com.aistudio.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.budget.PeriodCalculator.Period;
import com.aistudio.budget.PeriodCalculator.PeriodType;
import com.aistudio.channel.ChannelService;

/**
 * 현재 기간 사용량. 정책별 bucket을 그대로 보여 주고 SYSTEM과 CHANNEL을 합산하지 않는다(ADR-052).
 * bucket이 아직 없으면 0으로 계산한 가상 값(id=null).
 */
@Service
public class BudgetUsageService {

    public record UsageBucket(Long id, BudgetMapper.Policy policy, Instant periodStart, Instant periodEnd,
            String timezone, BigDecimal limitKrw, BigDecimal reservedKrw, BigDecimal spentKrw) {

        public BigDecimal remainingKrw() {
            return limitKrw.subtract(reservedKrw).subtract(spentKrw);
        }
    }

    private final BudgetMapper mapper;
    private final ChannelService channels;
    private final Clock clock;

    BudgetUsageService(BudgetMapper mapper, ChannelService channels, Clock clock) {
        this.mapper = mapper;
        this.channels = channels;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UsageBucket> currentUsage(Long channelId) {
        if (channelId != null) {
            channels.get(channelId);
        }
        Instant now = clock.instant();
        return mapper.activePolicies(channelId).stream()
                .filter(p -> !"LIFETIME".equals(p.periodType()))
                .map(p -> usage(p, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UsageBucket> systemUsage() {
        return currentUsage(null).stream().filter(u -> "SYSTEM".equals(u.policy().scope())).toList();
    }

    private UsageBucket usage(BudgetMapper.Policy policy, Instant now) {
        BudgetMapper.Bucket bucket = mapper.bucketCovering(policy.id(), now);
        if (bucket != null) {
            return new UsageBucket(bucket.id(), policy, bucket.periodStart(), bucket.periodEnd(), bucket.timezone(),
                    bucket.limitKrw(), bucket.reservedKrw(), bucket.spentKrw());
        }
        List<BudgetMapper.PolicyRevision> revisions = mapper.allRevisions(policy.id());
        Period period = PeriodCalculator.periodAt(BudgetPolicyService.segments(revisions),
                PeriodType.valueOf(policy.periodType()), now);
        BigDecimal zero = BigDecimal.ZERO.setScale(Money.KRW_SCALE);
        return new UsageBucket(null, policy, period.start(), period.end(), period.timezone(),
                revisions.getLast().limitKrw(), zero, zero);
    }

    @Transactional(readOnly = true)
    public long reconciliationRequiredCount() {
        return mapper.countReconciliationRequired();
    }
}

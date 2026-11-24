package com.aistudio.budget;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.BudgetControlLock;

/**
 * 전역 예산 제어: 지출 허용/긴급 차단(ADR-061, 기본 차단)과 정합성 검사 결과(ADR-054).
 */
@Service
public class BudgetControlService {

    private static final Logger log = LoggerFactory.getLogger(BudgetControlService.class);

    public record ControlView(boolean spendingEnabled, Instant spendingChangedAt, Long spendingChangedBy,
            String spendingChangeReason, String consistencyStatus, Instant consistencyCheckedAt, int issueCount,
            long version, Instant updatedAt) {
    }

    public record ConsistencyResult(Instant checkedAt, List<BudgetMapper.ConsistencyIssue> issues) {
        public boolean consistent() {
            return issues.isEmpty();
        }
    }

    private final BudgetMapper mapper;
    private final BudgetControlLock controlLock;
    private final AuditService audit;
    private final Clock clock;

    BudgetControlService(BudgetMapper mapper, BudgetControlLock controlLock, AuditService audit, Clock clock) {
        this.mapper = mapper;
        this.controlLock = controlLock;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ControlView get() {
        return view(mapper.control());
    }

    /** 지출 허용/차단. 잠금: control X(진행 중 예약과 직렬화). */
    @Transactional
    public ControlView setSpending(boolean enabled, String reason, long version, Actor actor) {
        controlLock.exclusive();
        BudgetMapper.Control control = mapper.control();
        if (control.version() != version) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "Budget control was changed by another request. Reload and retry.");
        }
        mapper.updateSpending(enabled, actor.userId(), reason, version, clock.instant());
        audit.record(actor, enabled ? "BUDGET_SPENDING_ENABLE" : "BUDGET_SPENDING_DISABLE", "BUDGET_CONTROL", 1,
                Map.of("reason", reason));
        return view(mapper.control());
    }

    /** 정합성 검사 실행. 잠금: control X(검사 중 예약·정산 정지). 결과를 기록하고 ERROR면 신규 예약이 막힌다. */
    @Transactional
    public ConsistencyResult runConsistencyCheck(Actor actor) {
        controlLock.exclusive();
        Instant now = clock.instant();
        List<BudgetMapper.ConsistencyIssue> issues = mapper.consistencyIssues(now);
        mapper.updateConsistency(issues.isEmpty() ? "OK" : "ERROR", issues.size(), now);
        if (!issues.isEmpty()) {
            log.error("Budget consistency check found {} issue(s); new reservations are blocked", issues.size());
        }
        audit.record(actor, "BUDGET_CONSISTENCY_CHECK", issues.isEmpty() ? AuditService.Outcome.SUCCESS
                : AuditService.Outcome.FAILURE, "BUDGET_CONTROL", 1, null, Map.of("issues", issues.size()));
        return new ConsistencyResult(now, issues);
    }

    private static ControlView view(BudgetMapper.Control c) {
        return new ControlView(c.spendingEnabled(), c.spendingChangedAt(), c.spendingChangedBy(),
                c.spendingChangeReason(), c.consistencyStatus(), c.consistencyCheckedAt(), c.consistencyIssueCount(),
                c.version(), c.updatedAt());
    }
}

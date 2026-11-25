package com.aistudio.shared.db;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전역 잠금 순서의 첫 자원인 budget_control 단일 행 잠금 (ADR-055). 채널 상태 변경과 예산 트랜잭션이 함께 쓰므로
 * 모듈 순환을 피하려고 shared에 둔다. 반드시 진행 중인 트랜잭션 안에서 호출한다.
 */
@Component
public class BudgetControlLock {

    @Mapper
    interface LockMapper {
        @Select("SELECT id FROM budget_control WHERE id = 1 LOCK IN SHARE MODE")
        Integer lockShared();

        @Select("SELECT id FROM budget_control WHERE id = 1 FOR UPDATE")
        Integer lockExclusive();
    }

    private final LockMapper mapper;

    BudgetControlLock(LockMapper mapper) {
        this.mapper = mapper;
    }

    /** 예약·정산·채널 상태 변경: 서로는 동시에, 정책 변경·지출 차단과는 배타적으로. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void shared() {
        require(mapper.lockShared());
    }

    /** 정책 생성·변경·지출 허용 변경·정합성 검사. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exclusive() {
        require(mapper.lockExclusive());
    }

    private static void require(Integer id) {
        if (id == null) {
            throw new IllegalStateException("budget_control 행이 없습니다(V5 migration 확인).");
        }
    }
}

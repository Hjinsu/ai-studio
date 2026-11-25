# T03 C/D 공통 인터페이스

기준 `dab7013c4557ba87d4440dfc86417344bb2c4e1d`. 작업 트리는 `ai-studio-t03-d`, 브랜치는 `t03/d-budget`. 공통 Java 소스는 Codex가 소유한다. 이 명세와 Java 선언은 같은 미커밋 변경에 있으며, C와 대조가 끝난 공통 배포 SHA는 아직 없다.

| 파일 및 방향 | 계약 |
|---|---|
| `com.aistudio.operation.contract.OperationCostPort` (D 구현, C 소비) | `Optional<CostPhase> phase(JobOwner)`, `Optional<Receipt> terminalReceipt(JobOwner, UUID)`, `RunCostState runCostState(long contentId, long runId)`. PRE/INFLIGHT/NONE은 승인된 상태 의미를 따른다. 조회는 dispatch 권한을 발행하지 않는다. 부재나 일치하지 않는 소유권만으로 호출 미발생을 확정하지 않는다. |
| `com.aistudio.operation.contract.QueueExecutionPort` (C 구현, D 소비) | `Optional<LockedQueueState> lockForDispatch(AttemptFence)`는 TX4에 참여한다. `ApplyResult applyReceipt(Receipt)`는 독립 TX7이다. APPLIED/ALREADY_APPLIED로 동일 receipt의 큐 효과를 구분한다. |
| `com.aistudio.operation.contract.OperationLifecyclePort` (D 구현, C 소비) | `Prepared prepare(Prepare)` TX1, `void reserveAndLink(JobOwner, UUID)` TX1b/TX2/TX3, `Optional<DispatchPermit> authorizeDispatch(AttemptFence, UUID)` TX4, `void recordEvidence(JobOwner, UUID, Evidence)` TX5, `void requestUnsentReservationClose(JobOwner, UUID)` 회수 진입. 어느 호출도 활성 큐 TX 안에서 실행할 수 없다. TX5b 채택·TX6 영수증·관리자 판정은 D 내부 책임이다. |

`JobOwner`는 channel/content/run/job의 양의 signed BIGINT다. Attempt fence는 같은 Job의 attempt ID·번호 및 UUID lease token을 함께 사용한다. `LockedQueueState`는 DB UTC 현재 시각, lease·deadline, 현 attempt, 취소, fail-fast, dispatch 상태를 담는다. D가 현재 attempt의 완전한 일치와 RUNNING, 마감 전, dispatch 활성 및 취소·fail-fast 해소를 확인한다. 없거나 불일치하면 권한을 발행하지 않는다.

## 참조 키와 소유권

- B에서 확인한 키는 `content(id,channel_id,content_type)`, `content_run(content_id,id)`다.
- C의 V7 요구 키는 `automation_job(id)`와 초안의 `automation_job(run_id,id)`, `job_attempt(job_id,id)`, 현 attempt 참조용 `job_attempt(job_id,attempt_no)`다. Job의 channel/content/run 연쇄 소유권 및 런타임 컬럼은 V7 실물에서 확인한다. `queue_worker_instance`를 `job_attempt`보다 먼저 생성한다.
- 이번 배정에서 D는 job_operation, 예약 연결·의도, dispatch, append-only evidence, 비용 receipt 및 정산 복구를 관리한다. C는 Job/Attempt 상태·lease, 큐 결과와 적용 완료의 펜스를 관리한다. 이전 B 인수인계의 개괄 표현보다 이번 작업 배정의 책임 경계를 따른다.
- terminal receipt의 영속 ID는 `operation_id`다. TX6은 불변 outcome·원장 이벤트 참조를 비용 확정과 원자적으로 저장한다. TX7에는 서버에서 읽은 소유권, operation 버전, terminal outcome, terminal event sequence, UTC 확정 시각을 전달한다. 검증된 산출물과 청구 판정은 독립이며 채택 근거는 D의 TX6에서 검증한다.

- C의 미커밋 `V7__create_queue_engine.sql`에서 worker 선행 생성, Job의 `(id,run_id,content_id,channel_id)` 대체 키, `(run_id,id)`, attempt의 두 대체 키 및 현 attempt FK를 확인했다. V7 적용·adapter·공통 배포 SHA가 대조될 때까지 잠정 참조다.

## 트랜잭션 및 오류

TX4는 D의 최상위 READ COMMITTED에서 budget_control → intent → channel → policy → bucket → reservation을 선행하고 C adapter가 queue_control → content → content_run → job까지 참여한다. 이후 D가 job_operation을 잠그고 attempt 및 dispatch 제약을 확인한다. 현 attempt 번호와 lease는 잠근 job으로 읽는다. attempt의 명시 잠금 또는 FK로 인한 부모 락은 operation 이후에 확인한다. 중첩 재시도·큐→비용 역진입은 금지한다. 확정 커밋을 직접 확인한 실행자에게만 단일 dispatch를 허가한다.

TX7은 비용 receipt를 사전 조회하고 독립 큐 TX에서 Job owner, receipt, operation 버전/적용 펜스를 재검증한다. 비용 reservation과 원장은 쓰거나 잠그지 않는다. R10은 C-G01~10을 따르며 미확정 비용·Operation 또는 미적용 receipt가 있으면 종료를 거절한다. R10 재검증에 필요한 Operation 락은 큐 순서 뒤에 두고 결제 트랜잭션을 호출하지 않는다. C/D 실제 adapter와 latch 테스트로 FK 및 고유 인덱스의 락 순서를 검증해야 한다.

교차 소유권 404 RESOURCE_NOT_FOUND, 상태/lease 위반 409 INVALID_STATE_TRANSITION, 동일 키 다른 지문 409 RESOURCE_CONFLICT, 예산/정책 전제 누락 409 PREREQUISITE_NOT_MET, 정책 차단 422 POLICY_VIOLATION, 한도 초과 422 BUDGET_EXCEEDED. 재계획 503은 허가 전 롤백이 확인된 경로만 동일 키로 재시도하고, 커밋·외부 결과가 불명확하면 조회 또는 복구 상태로 전환한다.

## C/D 검증 게이트

| ID | 계약과 합격 조건 |
|---|---|
| CD-G01 | 빈 phase·receipt 조회, 미확인 owner, 실패 조회는 미실행·무비용 판정으로 승격되지 않는다. NONE은 효과가 없는 작업 또는 승인된 증거로 해소된 경우다. Run 검사 불명은 종료를 차단한다. |
| CD-G02 | C 어댑터는 D가 연 최상위 READ COMMITTED TX4에 참여한다. 활성 TX 부재나 격리 수준 불일치를 거부한다. 별도 TX·중첩 retry 없이 바깥 실패가 전체 작업을 롤백하는지 검증한다. |
| CD-G03 | TX7은 Job 상태·이벤트 변경과 receipt 적용 완료 기록을 한 TX에 쓴다. 상태 변경 직후와 marker 기록 직후 실패를 각각 주입해 둘 다 남지 않는지 확인한다. 동시 전달은 효과 1회다. |
| CD-G04 | TX7은 잠금 아래 불변 receipt의 operation ID·전체 소유권·operation 버전·terminal event sequence(null 포함)·outcome·확정 시각을 검증한다. 같은 검증 완료 receipt 재생은 ALREADY_APPLIED다. 같은 식별자의 내용 불일치는 409 RESOURCE_CONFLICT, 교차 소유권은 404, 선행 비용 확정 증거 부재는 적용 차단이다. |
| CD-G05 | TX1과 R10은 공통 content → content_run 잠금을 사용한다. TX1은 선행 잠금 후 활성 포인터와 Run 비종료를 다시 검사하고 Job → Operation 순서로 진행한다. R10은 같은 Run X 유지 중 최종 목록을 재조회한다. 먼저 TX1이 커밋하면 R10이 미해결 효과를 보고 종료를 거부한다. R10이 먼저 커밋하면 TX1은 새 효과를 만들지 않는다. 두 커넥션으로 두 순서 모두 검사한다. |

C 쪽 미커밋 V7의 FK 및 대체 키 형태는 요구와 일치했다. 대조 시점에 C 작업 트리에는 pipeline 및 상태 전이 코드가 있었고 실제 QueueExecutionPort 구현 및 고정 V7 커밋은 확인되지 않았다.

## 공통 커밋 전달 절차

1. 사용자가 커밋을 승인하면 D에서 Java 선언 3개와 이 문서만 명시 경로로 stage한다. staged 목록·whitespace·비밀정보 검사를 통과한 뒤 `feat: define T03 C-D operation contracts`로 커밋한다.
2. Codex가 전체 SHA, 4개 경로와 파일별 blob SHA를 전달한다. 같은 로컬 저장소의 worktree라면 C가 별도 fetch 없이 `git show --name-status <SHA>`, `git ls-tree -r <SHA> -- <4개 경로>`로 대조한다. 다른 저장소라면 사용자 승인된 전달 수단으로 커밋을 제공한다.
3. Claude가 자기 작업 트리에서 충돌할 경로 또는 staged 변경을 먼저 확인하고, 안전하면 `git cherry-pick <SHA>`한다. 충돌하면 자기 변경을 지우지 않고 중단하며, 적용 후 네 blob의 동일성 및 C adapter 빌드를 알려준다. Codex는 C 작업 트리를 직접 수정하지 않는다.

## 작업 가능 범위

키·소유권·adapter·공통 소스 배포 SHA가 C 측에서 확인되기 전 V7 FK, 통합 Q0/R10, TX4, P12 실행 경로는 BLOCKED다. 지금 D는 기존 예산의 순수 정책 선택 및 C 의존성이 없는 비용 영역에 착수한다. V8 적용은 실제 V7을 확보한 뒤 별도 loopback 임시 MariaDB에서 빈 DB 및 V5/V6/V7 업그레이드로 검증한다. 임시 호환 테이블은 사용하지 않는다.

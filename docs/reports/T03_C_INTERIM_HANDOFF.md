# T03-C 중간 인수인계 — V7 참조 키와 큐 상태 전이 인터페이스

## 0. 작업 종료 체크포인트(2026-11-26, 다른 PC에서 재개)

**상태: T03-C 개발 중(WIP)**. IMPLEMENTED·DONE 아님. 커밋 `wip: checkpoint T03-C queue engine development`로 `origin/t03/c-queue`에 보존했다(SHA는 이 커밋 자체에 기록할 수 없으므로 `git log -1 origin/t03/c-queue`로 확인).

| 항목 | 상태 |
|---|---|
| 구현 완료 | V7 큐 스키마(§2), C→D 참조 키(§3), 순수 상태 전이 `JobTransitions`·`RetryBackoff`, FAKE pipeline 2종과 기동 검증기, V7 운영 권한, 스키마·업그레이드 IT |
| 테스트(마지막 실행) | 단위 169/169, 통합 163/163 binlog OFF, 163/163 binlog ON(§7) |
| 미완료 | worker 인스턴스·스케줄러, Q0 Run 생성·CREATE_RUN 멱등, claim·heartbeat·sweep·fencing, FAKE 실행기, 완료·fan-in 승격, 취소·수동 재개, R10, reconciler·기동 고아 감지(C-G09), TX4 adapter·TX7 P12, 늦은 증거, Run/Job/Queue API, C-G08 장애 주입, 잠금 실측, 최종 보고서 `T03_C_REPORT.md` |
| V7 체크섬(LF SHA-256) | `5cff506d1bddda8f98489a6ab5e5419a3a8e276f8a0d675e61457b0ba12f5d81`(MigrationConventionTest 등록값과 일치). V7은 미배포·격리 DB에만 적용 |
| 공통 계약 `6cd0c4e` | **미반영**. `6cd0c4e2c7618f90249f670f000938113c37d456 feat: define T03 C-D operation contracts`는 확인 시점에 로컬 `t03/d-budget` 브랜치에만 있고 origin에는 없다. 4개 경로: `operation/contract/{OperationCostPort,OperationLifecyclePort,QueueExecutionPort}.java`, `docs/plans/t03-final/T03_CD_INTERFACE_CONTRACT.md`. 계약 문서의 전달 절차(Codex가 SHA·blob 제공 → C가 충돌 확인 후 cherry-pick)에 따라 다음 작업에서 반영한다 |
| C/D 의존 | `QueueExecutionPort`(C 구현: `lockForDispatch` TX4 참여, `applyReceipt` TX7), `OperationCostPort`(D 구현: `phase`, `terminalReceipt`, `runCostState`), `OperationLifecyclePort`(D 구현: TX1~TX5·회수, 활성 큐 TX 밖에서 호출). CD-G01~05 게이트. §5의 Q1(적용 펜스 위치)은 CD-G03이 "Job 상태·이벤트와 receipt 적용 완료 기록을 한 TX"로 규정했으나 기록 위치(D 테이블 컬럼 또는 C 표)는 V8과 함께 확정 필요 |
| 임시 DB | 세션 scratchpad의 임시 MariaDB(3307/3308)는 종료. 다른 PC는 [로컬 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md)로 새로 만든다 |

### 다음 작업 재개 순서

1. `git fetch origin && git worktree add -b t03/c-queue ../ai-studio-t03-c origin/t03/c-queue`(또는 기존 worktree에서 `git switch t03/c-queue && git pull --ff-only`). `git log -1` 확인.
2. 격리 임시 MariaDB를 띄우고 `AI_STUDIO_IT_DB_*` 지정 → `gradlew.bat :backend:test`, `:backend:integrationTest`로 체크포인트 재검증(3306 개발 DB 사용 금지).
3. 공통 계약 반영: Codex가 `6cd0c4e`(또는 갱신 SHA)를 origin에 올리면 `git show --name-status <SHA>`·`git ls-tree -r <SHA> -- <4개 경로>`로 대조하고 사용자 승인 후 `git cherry-pick <SHA>`. 충돌 시 중단·보고.
4. D 비의존 2단계 구현: 설정 검증 → worker 인스턴스·heartbeat → Q0 Run 생성(C-G01~03, 멱등, 장애 주입) → claim·sweep·fencing·FAKE 실행기·fan-in → 취소·재개 → reconciler·기동 고아 감지(C-G09) → Run/Job/Queue API.
5. 계약 반영 후: `QueueExecutionPort` adapter(TX4/TX7, CD-G02~04), R10(C-G04~08, `runCostState` 불명 시 종료 거절, CD-G01/05), 늦은 증거는 `recordEvidence`.
6. 두 커넥션 latch 잠금 실측(V7 FK·UNIQUE, Q0/R10/TX1/TX4 경합), binlog ON/OFF 전체 회귀, bootJar → `docs/reports/T03_C_REPORT.md` 작성 후 IMPLEMENTED 제출.

작성 2026-11-26 · 담당 Claude Code · 대상 Codex(T03-D·C/D 공통 인터페이스 소유자) · **T03-C 진행 중(IMPLEMENTED 아님)** · 커밋 없음

## 1. 기준

| 항목 | 값 |
|---|---|
| 작업 / 브랜치 / worktree | T03-C / `t03/c-queue` / `ai-studio-t03-c`(별도 worktree) |
| 기준 SHA | `dab7013c4557ba87d4440dfc86417344bb2c4e1d` = `origin/t03/integration`(PR #3 병합, T03-B DONE 효력 조건 충족) |
| 다른 worktree | `ai-studio-t03-d`의 Codex 미커밋 초안 `T03_CD_INTERFACE_CONTRACT.md`·`operation/contract/*`는 **읽기만** 했다. integration worktree의 미추적 리뷰 파일은 건드리지 않았다 |
| Migration | `backend/src/main/resources/db/migration/V7__create_queue_engine.sql`(파일명은 문서에 확정값이 없어 이 이름으로 작성. 통합 담당자 확인 요청), LF SHA-256 `5cff506d1bddda8f98489a6ab5e5419a3a8e276f8a0d675e61457b0ba12f5d81` |

1단계 목표는 사용자 지시에 따라 **D가 의존할 V7 참조 키·Job/Attempt 스키마·P12 입력·큐 상태 전이 인터페이스를 먼저 제공**하는 것이다. 비용·Operation 내부 구현은 하지 않았다.

## 2. V7 생성 순서와 범위

1. `queue_control`(단일 행 seed: dispatch_enabled TRUE, max_running_jobs 2) → 2. `queue_worker_instance` → 3. `automation_job` → 4. `job_attempt` → 5. `automation_job.fk_job_current_attempt` 역참조 FK → 6. `job_dependency` → 7. `job_event` → 8. 트리거.

- FV-01: `queue_worker_instance`를 `job_attempt`(FK 사용)보다 먼저 생성.
- `queue_command_log`는 V6 것을 재사용(중복 생성 없음).
- **D 테이블 미생성**: job_operation, reservation_intent, operation_dispatch, operation_evidence, operation_event_intent, operation_receipt(DatabaseBootstrapIT가 부재를 단언). V1~V6 무변경, 기존 테이블에 제약 추가 없음(QueueUpgradeIT가 기존 18개 테이블의 제약·인덱스·트리거 diff 0을 단언).

## 3. C가 D에 제공하는 참조 키(V7 실물)

| 키 | 컬럼 | 용도 제안 |
|---|---|---|
| `automation_job` PK | `(id)` | job_operation.job_id 기본 FK |
| `uk_job_owner` | `(run_id, id)` | run 소유 확인, job_dependency·job_event FK |
| `uk_job_owner_chain` | `(id, run_id, content_id, channel_id)` | `JobOwner`(channel/content/run/job) 전체 일치 FK — 초안의 "연쇄 소유권 확인" 요구에 맞춰 추가 |
| `uk_attempt_owner` | `job_attempt(job_id, id)` | job_operation.created_attempt_id, operation_dispatch.attempt_id FK |
| `uk_attempt_no` | `job_attempt(job_id, attempt_no)` | 현재 attempt 참조(`automation_job(id, current_attempt_no)` FK 대상) |
| `uk_attempt_token` | `job_attempt(lease_token)` | lease 토큰 전역 유일(fencing) |
| B 키(V6) | `content(id,channel_id,content_type)`, `content_run(content_id,id)` | 기존 |

`automation_job.channel_id/content_id/run_id`는 복합 FK(`fk_job_run (content_id,run_id)→content_run`, `fk_job_content (content_id,channel_id)→content`)와 불변 트리거로 연쇄 소유가 강제된다.

### TX4·P12가 읽을 V7 컬럼(Codex 초안 `LockedQueueState` 대응)

| 상태 | 위치 |
|---|---|
| job 상태·사유 | `automation_job.status`(10개), `status_reason` |
| 현재 attempt·fence | `automation_job.current_attempt_no`, `lease_token`, `lease_owner`, `lease_expires_at`, `heartbeat_at` |
| 실행 기한 | `job_attempt.execution_deadline_at`(heartbeat로 연장 불가 — lease는 `LEAST(now+lease, deadline)`) |
| 취소 의도 | `content_run.cancel_requested_at/by/reason`(Run 단위), `automation_job.cancel_requested_at`(RUNNING/RECOVERY_REQUIRED job 표시) |
| fail-fast | `content_run.fail_fast_at` |
| dispatch 스위치 | `queue_control.dispatch_enabled`, `max_running_jobs`(1~2) |
| 외부 효과 여부 | `automation_job.requires_operation`(T03-C FAKE v1은 전부 FALSE) |
| DB 시각 | 모든 큐 비교는 `UTC_TIMESTAMP(6)` |

## 4. 큐 상태 전이 인터페이스(순수 함수, 1단계 구현)

`com.aistudio.queue.JobTransitions`(DB·잠금 없음, 단위 테스트 10건). C 서비스(Q1/Q2/sweep/R10/TX7)는 잠금·fencing 뒤 이 판정을 적용한다.

| 입력(D 제공 필요 여부) | 값 | 용도 |
|---|---|---|
| `Completion`(실행기·비용 계층 분류) | SUCCEEDED, RETRYABLE, ACTION_REQUIRED, FATAL, OUTCOME_UNKNOWN, CANCELLED | 완료 보고 J10~J18 |
| `OperationPhase` (**D**: `CostPhase phase(JobOwner)`) | NONE, PRE, INFLIGHT | sweep 만료 J19/J20, 취소 시 PRE 회수 요청 |
| `Resolution` (**D**: terminal receipt) | EXECUTED_RESULT_ADOPTED, EXECUTED_FAILED, NOT_EXECUTED | P12(J22, J25~J27) |

판정 규칙(최종 명세 우선):
- 자동 실행 최초 포함 3회(FD-09), 수동 재개 2회(FD-10), job 행에 불변 저장.
- BUDGET_EXCEEDED·POLICY_VIOLATION·PREREQUISITE_NOT_MET은 ACTION_REQUIRED, **자동 재시도 없음**.
- OUTCOME_UNKNOWN은 `requires_operation` job만 RECOVERY_REQUIRED(그 외는 계약 위반 FATAL). DB CHECK `ck_job_recovery`도 같은 규칙.
- lease/deadline 만료: INFLIGHT → RECOVERY_REQUIRED(재호출 금지), NONE/PRE → 재시도 규칙.
- **RECOVERY_REQUIRED에서 NOT_EXECUTED가 확인돼도 같은 operation을 재시도하지 않는다** → 취소 의도면 CANCELLED, 아니면 FAILED(A3 J23/J24 대체, FD-04/05).
- R10 판정: 모든 job SUCCEEDED → SUCCEEDED, 아니면 취소 의도 → CANCELLED, 아니면 FAILED. **비용·Operation 종료 확인은 R10 호출자가 D 포트로 확인**(아래 Q3).
- backoff: `min(300s, 5s×2^(n−1))` equal jitter, Retry-After가 더 길면 사용, 3600초 초과는 ACTION_REQUIRED(RETRY_AFTER_TOO_LONG). (`RetryBackoff`)

### P12 입력으로 C가 요구하는 필드(TX7)

operation_id(UUID), JobOwner(channel/content/run/job — 서버가 읽은 값), 생성 attempt_id, operation 버전, terminal outcome(위 `Resolution`으로 사상 가능해야 함), terminal_event_seq, committed_at(UTC). C는 TX7에서 비용 행을 쓰거나 잠그지 않고, 큐 순서(queue_control → content → content_run → job → operation)로 재검증한 뒤 같은 receipt의 반복 적용을 `ALREADY_APPLIED`로 0효과 처리한다. 초안의 `QueueExecutionPort.applyReceipt` 반환(APPLIED/ALREADY_APPLIED)과 일치한다.

## 5. Codex 결정 요청(공통 인터페이스 확정 전 BLOCKED 항목)

| # | 항목 | C 측 근거·영향 |
|---|---|---|
| Q1 | **receipt 적용 펜스 위치** | 초안 SQL은 `job_operation.queue_applied_at`(D 테이블). C의 TX7이 큐 잠금 순서 마지막(job → job_operation)에서 이 컬럼을 갱신해야 하는지, C 소유 별도 표를 둘지 결정 필요. 앱 권한(UPDATE)과 트리거 소유도 함께 |
| Q2 | TX4 참여 방식 | `lockForDispatch`는 D의 최상위 TX 안에서 `Propagation.MANDATORY`로 queue_control X → content → content_run → job 잠금만 수행(자체 TX·재시도 없음)하는 것으로 구현 예정. 확인 요청 |
| Q3 | R10의 비용 확인 | R10은 큐 잠금 뒤 `RunCostState runCostState(contentId, runId)`를 같은 TX에서 호출. 포트가 MANDATORY·쓰기 없음이며 job_operation 잠금이 필요하면 큐 순서 뒤(S)인지 확인 요청. 포트 부재·오류는 **종료 거절**(비용 해소로 추정하지 않음) |
| Q4 | requires_operation pipeline | T03-C는 FAKE v1(전부 FALSE)만 등록. A3 INT-01의 `fake-*-v2`(visual·narration=TRUE) 추가 주체·시점(D 통합 시) 확인 |
| Q5 | 늦은 증거(TX5) | 만료 worker의 결과는 C가 job 상태를 바꾸지 않고 D의 증거 접수 포트로 넘긴다. 포트 시그니처 필요(현재 초안에 없음) |
| Q6 | 공통 소스 배포 | `com.aistudio.operation.contract`는 Codex 소유. 배포 SHA가 정해지면 C가 adapter(`QueueExecutionPort` 구현)와 소비(`OperationCostPort`)를 연결 |
| Q7 | V7 추가 제약 검토 | 초안 대비 추가: `uk_job_owner_chain`, `ck_job_recovery`, `ck_job_token`, `ck_attempt_token`, `ck_worker_instance_id`, `ck_worker_seen`, `ck_queue_control_change`, `ck_event_admin`, 트리거(job 생성은 fresh WAITING만, 카운터 감소·취소 의도 변경·WAITING 복귀 금지, attempt는 열린 상태로만 생성, worker 인스턴스 식별·정지 후 불변). 근거는 V7 주석 및 승인 계약(FD-09/10, Q-INV) |

INSERT 트리거에서 다른 테이블을 조회하지 않는다(T03-B-FIX에서 INSERT 문 안 조회가 S 잠금이 되어 S→X 교착이 실측됨). 예외는 설계 승인된 `job_dependency` 후행 WAITING 확인뿐이며, 그 행은 같은 TX가 이미 X로 보유한다(Run 생성·확장 TX).

## 6. 1단계 변경 파일(기준 `dab7013` 대비, 커밋 없음)

신규 제품: `V7__create_queue_engine.sql`(303줄), `queue/{JobStatus,AttemptOutcome,JobTransitions,RetryBackoff}.java`, `queue/pipeline/{PipelineDefinition,PipelineValidator,PipelineRegistry}.java`.
신규 테스트: `queue/{JobTransitionsTest,RetryBackoffTest,QueueFixtures,QueueSchemaIT,QueueUpgradeIT}`, `queue/pipeline/PipelineValidatorTest`.
수정: `infra/mariadb/prod-app-grants.sql`(+7: queue_control SELECT/UPDATE, worker·job·attempt SELECT/INSERT/UPDATE, dependency·event SELECT/INSERT), `MigrationConventionTest`(V7 체크섬·bin 컬럼 7개), `DatabaseBootstrapIT`(V1~V7, 테이블·bin 목록), `DatabaseUpgradeIT`(V1~V7), `ProductionGrantsIT`(불변 테이블에 job_dependency·job_event), **B 테스트 `ContentUpgradeIT`**(V5→V6 전용이므로 `target("6")` 고정 — V7 추가로 버전 수 단언이 깨진 것을 범위 유지로 수정).

```
 .../src/test/java/com/aistudio/DatabaseBootstrapIT.java   | 15 ++++++++++-----
 backend/src/test/java/com/aistudio/DatabaseUpgradeIT.java |  2 +-
 .../test/java/com/aistudio/content/ContentUpgradeIT.java  |  4 +++-
 .../java/com/aistudio/security/ProductionGrantsIT.java    |  4 +++-
 .../com/aistudio/shared/db/MigrationConventionTest.java   | 13 +++++++++++--
 infra/mariadb/prod-app-grants.sql                         |  7 +++++++
 6 files changed, 35 insertions(+), 10 deletions(-)
```
`git diff --check` 문제 없음.

## 7. 1단계 테스트 실제 결과

격리 임시 MariaDB 11.8.2(127.0.0.1:3307 binlog OFF, 3308 binlog ON, 세션 scratchpad 데이터·임시 비밀번호). 사용자 3306 DB 접속 없음.

| 명령 | 결과 |
|---|---|
| `:backend:test` | 24 suites / 169 tests, 실패 0(신규 JobTransitions 10, RetryBackoff 3, PipelineValidator 5, MigrationConvention V7 등록 포함) |
| `:backend:integrationTest --rerun-tasks` binlog OFF | 20 suites / 163 tests, 실패 0 |
| 같은 명령 binlog ON | 20 suites / 163 tests, 실패 0 |

신규 IT: QueueSchemaIT 9(키·FK 구조, queue_control 단일 행·상한 2, job/attempt/dependency/event 소유권, lease·상태·카운터 CHECK, 트리거), QueueUpgradeIT 1(V6 실데이터 → V7, 기존 테이블 체크섬·구조 diff 0, 기존 활성 run에 job·attempt 연결). 기존 T01/T02/T03-B 전체 통과.

## 8. 2단계(남은 T03-C) 계획과 의존

| 항목 | D 인터페이스 의존 | 착수 |
|---|---|---|
| worker 인스턴스 등록·heartbeat·종료, 설정 검증(`AI_STUDIO_QUEUE_*`, prod에서 FAKE 금지) | 없음 | 즉시 가능 |
| Q0 Run 생성(channel S → queue_control X → content X, DAG WAITING INSERT → 간선 → 루트 READY → 포인터 `active_run_id IS NULL` 1행), CREATE_RUN 멱등, 장애 주입 | 없음 | 즉시 가능 |
| Dispatcher·Q1 claim(FIFO, DB 상한 2, fencing), heartbeat, sweep(J19 NONE 경로), FAKE 실행기, 완료 J10~J18, fan-in 승격, 취소(P8), 수동 재개(J21), reconciler·기동 고아 감지(C-G09) | requires_operation=FALSE 경로는 없음 | 즉시 가능 |
| R10 종료(C-G04~08) | **Q3**(`runCostState`) | FAKE v1은 operation 0이 구조상 보장되지만, 포트 없이 "비용 없음"으로 추정하지 않는다. 포트 확정 전에는 R10의 비용 확인 단계를 **fail closed 스텁(항상 미확정이 아님을 입증할 수 없으면 거절)**으로 둘지 Codex와 합의 필요 |
| TX4 adapter, TX7 P12 적용, 늦은 증거, INFLIGHT sweep(J20) | **Q1/Q2/Q5/Q6** | 공통 소스 배포 후 |
| Run·Job·Queue API(목록·상세·취소·재개·dispatch 제어) | 없음 | OpenAPI 반영은 Codex 요청 |

## 9. 미검증 사항(1단계)

- V7 FK·UNIQUE의 **암묵 잠금 실측**(claim·완료·확장 경로)은 서비스 구현과 함께 2단계에서 두 커넥션 latch로 측정한다.
- CI(Testcontainers 11.8.8) 미실행(커밋 전), 운영 binlog ON migration 계정(배포 게이트, T03-B와 동일).
- 큐 서비스·스케줄러·API 미구현(2단계).

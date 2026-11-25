# T03-C 중단 인수인계 — 개인용 영상 자동 제작 MVP로 방향 전환

작성 2026-11-26 · 담당 Claude Code · 대상: 다음 세션(회사 PC)의 Claude Code, Codex(기획 검토·통합 담당)

## 0. 요약

- **상태: T03-C 중단(PAUSED, WIP 보존)**. IMPLEMENTED·DONE 아님. 추가 구현 금지.
- 사유: 개발 방향이 **개인용 영상 자동 제작 MVP**로 변경됨. Codex가 새 기획서를 검토 중이며, 검토 결과와 사용자 승인 전까지 T03-C 코드는 수정하지 않는다.
- 보존: 기존 1단계 구현 전체가 `origin/t03/c-queue`에 커밋돼 있다(`e7ef738 wip: checkpoint T03-C queue engine development`). 이 문서는 그 위의 문서 전용 커밋으로 추가된다.
- 상세 기술 내용(V7 스키마, 참조 키, 상태 전이 규칙, Codex 결정 요청 Q1~Q7)은 [T03_C_INTERIM_HANDOFF.md](T03_C_INTERIM_HANDOFF.md)를 그대로 유효한 기록으로 본다.

## 1. 기준 상태

| 항목 | 값 |
|---|---|
| 브랜치 / worktree | `t03/c-queue` / `ai-studio-t03-c`(별도 worktree) |
| 기준 커밋 | `dab7013`(PR #3 병합, T03-B DONE) = `origin/t03/integration` |
| WIP 커밋 | `e7ef738`(제품 코드·테스트·V7 migration) + 이 문서 커밋 |
| 작업 트리 | 중단 시점 clean(미커밋 변경 없음) |
| PR | 없음(main·integration 병합 금지 — 방향 결정 전) |
| DB migration | `V7__create_queue_engine.sql`은 **미배포**. 세션 임시 격리 MariaDB에만 적용했고 그 DB는 종료·폐기됨. 개발 DB(3306)·업무 DB 적용 없음 |
| 공통 파일 | STATE.md·TASKS.md·DECISIONS.md·OpenAPI는 수정하지 않았다(통합 담당자 Codex 소유). 반영 제안은 §5 |

## 2. 완료된 것(1단계, 테스트 통과)

| 영역 | 내용 |
|---|---|
| V7 스키마 | `queue_control`, `queue_worker_instance`, `automation_job`, `job_attempt`, `job_dependency`, `job_event`, 불변·상태 트리거, CHECK 제약 |
| C→D 참조 키 | `uk_job_owner`, `uk_job_owner_chain`, `uk_attempt_owner`, `uk_attempt_no`, `uk_attempt_token` |
| 순수 로직 | `JobTransitions`(FD-09/10 재시도 횟수, OUTCOME_UNKNOWN·만료 처리), `RetryBackoff` |
| 파이프라인 | FAKE pipeline 2종, `PipelineRegistry`, 기동 `PipelineValidator` |
| 권한·테스트 | V7 운영 권한(`infra/mariadb/prod-app-grants.sql`), QueueSchemaIT·QueueUpgradeIT, 기존 IT V7 대응 |

마지막 실제 테스트 결과(2026-11-26, 격리 MariaDB 11.8.2): `:backend:test` 169/169, `:backend:integrationTest` binlog OFF 163/163, binlog ON 163/163. **이번 중단 커밋에서는 코드 변경이 없어 테스트를 재실행하지 않았다.** CI는 이 브랜치에서 실행된 적 없다.

## 3. 미완료 기능(T03-C 2단계, 착수 안 함)

| # | 기능 | 비고 |
|---|---|---|
| 1 | 큐 설정 검증(`AI_STUDIO_QUEUE_*`, prod FAKE 금지) | D 비의존 |
| 2 | worker 인스턴스 등록·heartbeat·종료, 스케줄러 | D 비의존 |
| 3 | Q0 Run 생성·CREATE_RUN 멱등(C-G01~03) | D 비의존 |
| 4 | Dispatcher·claim·fencing·sweep·FAKE 실행기·완료(J10~J18)·fan-in 승격 | D 비의존 |
| 5 | 취소(P8)·수동 재개(J21) | D 비의존 |
| 6 | reconciler·기동 고아 Run 감지(C-G09) | D 비의존 |
| 7 | R10 Run 종료(C-G04~08) | D `runCostState` 필요(Q3) |
| 8 | TX4 `QueueExecutionPort` adapter, TX7 P12 receipt 적용, INFLIGHT sweep(J20), 늦은 증거(TX5) | 공통 계약 필요(Q1/Q2/Q5/Q6) |
| 9 | Run·Job·Queue API, OpenAPI 반영 | OpenAPI는 Codex |
| 10 | C-G08 장애 주입, C-G10 경합·잠금 실측(두 커넥션 latch) | 서비스 구현 후 |
| 11 | 최종 보고서 `T03_C_REPORT.md`, bootJar 검증 | 마지막 |

공통 계약 `6cd0c4e feat: define T03 C-D operation contracts`는 이제 `origin/t03/d-budget`에 있다(중단 시점 확인). **t03/c-queue에는 미반영**이며, 반영은 아래 §4 결정 이후로 보류한다.

## 4. 재개 전 결정 필요(사용자·Codex)

새 MVP 기획 검토 결과에 따라 다음 중 하나를 사용자가 결정한다. 결정 전에는 이 브랜치의 코드를 수정하지 않는다.

| 선택지 | T03-C 처리 |
|---|---|
| A. 큐 엔진 유지(축소) | 개인용 단일 사용자 기준으로 범위를 줄여 §3의 D 비의존 항목부터 재개. 비용·Operation 연동(§3-7·8) 범위 재정의 필요 |
| B. 큐 엔진 재설계 | V7이 미배포이므로 V7 파일 자체를 교체할 수 있는지 통합 담당자가 판단(AGENTS.md: 적용 후 수정 금지 — 현재 어떤 공유 DB에도 미적용). 새 설계 승인 후 새 작업 ID로 진행 |
| C. 폐기·보관 | 브랜치를 보관용으로 남기고 병합하지 않는다. 삭제는 사용자 승인 필요 |

함께 확인할 것: T03-D(`t03/d-budget`, Codex WIP `3a976e1`)의 존속 여부 — C/D 계약(Q1~Q7)이 같이 영향을 받는다.

## 5. 공통 파일 반영 제안(통합 담당자용)

- TASKS.md: T03-C 상태를 `PAUSED`(방향 전환, WIP `origin/t03/c-queue`)로 표기하고 이 문서를 링크.
- STATE.md: "T03-C 중단, 새 MVP 기획 검토 대기, V7 미배포" 기록.
- DECISIONS.md: 방향 전환 결정과 T03-C/D 처리(§4 선택 결과)를 ADR로 기록.

## 6. 회사 PC 재개 절차

선행 조건: Java 21, Git, 격리 테스트용 MariaDB(또는 Docker/Testcontainers) — [로컬 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md). 비밀값은 Git에 없으므로 환경변수로 새로 지정한다.

```bash
git clone https://github.com/Hjinsu/ai-studio.git   # 이미 있으면 생략
cd ai-studio
git fetch origin
git worktree add -b t03/c-queue ../ai-studio-t03-c origin/t03/c-queue
#  (이미 로컬 브랜치가 있으면: git switch t03/c-queue && git pull --ff-only)
cd ../ai-studio-t03-c
git log --oneline -3          # 최상단이 이 문서 커밋, 그 아래 e7ef738 인지 확인
```

1. README.md, AGENTS.md, STATE.md, DECISIONS.md, TASKS.md, `docs/guides/CODEX_COLLABORATION.md`, 이 문서를 읽는다.
2. Codex의 새 MVP 기획 검토 결과와 사용자 결정(§4)을 확인한다. **결정·새 작업 배정이 없으면 코드 작업을 시작하지 않는다.**
3. (선택) 체크포인트 재검증 — 격리 DB에서만, 3306 개발 DB 사용 금지:
   ```bash
   # AI_STUDIO_IT_DB_* 환경변수를 격리 DB로 지정한 뒤
   ./gradlew :backend:test
   ./gradlew :backend:integrationTest --rerun-tasks
   ```
   Windows는 `gradlew.bat`. 기대값: 단위 169, 통합 163 전부 통과.
4. 선택지 A로 재개 시 [T03_C_INTERIM_HANDOFF.md](T03_C_INTERIM_HANDOFF.md) "다음 작업 재개 순서" 3~6단계를 따르되, 범위는 새 기획 승인안 기준으로 다시 확인한다.

## 7. 미검증·주의

- 이번 중단 커밋은 문서만 추가 — 테스트 재실행 없음(§2 결과는 `e7ef738` 기준).
- CI·Testcontainers 미실행, 운영 binlog ON migration 계정 미검증.
- V7 암묵 잠금 실측 미수행(서비스 미구현).
- 비밀정보: 이 문서와 커밋에 비밀값·토큰·DB 덤프 없음.

# T02 백엔드 독립 교차 리뷰 (Claude Code)

- 담당 Claude Code · 검토일 2026-11-25 · 작업 ID T02-RV(교차 리뷰)
- 기준: 수정 전 `fa32535` → 수정 후 `6a32c64`(`fix/t02-backend`, Draft PR #2). 파일·줄은 `6a32c64` 기준.
- 검토 방식: `git diff fa32535..6a32c64` 전체(제품 코드 11개 파일, 테스트 8개, 계약·문서 6개) 정독 + 별도 검토 전용 worktree(`../ai-studio-review-6a32c64`, detached `6a32c64`)에서 독립 재실행. Codex worktree(`../ai-studio-t02-fix`)와 사용자 개발 DB(3306)는 읽기만 했다/사용하지 않았다.
- 참조 문서: `6a32c64:docs/reports/T02_CODEX_FIX_REPORT.md`, `6a32c64:docs/reports/T02_CODEX_EXT_FIX_REPORT.md`. **`docs/reviews/T02_CODEX_REVIEW.md`는 커밋 `6a32c64`와 Codex worktree에 없다.** Claude worktree에만 미추적 파일로 존재하며(RV-01~06 원 리뷰, 기준 `fa32535`) 그 내용을 참조했다.

## 1. 최종 판정

**통합 승인 권고(조건부).** RV-01~RV-09는 모두 원래 결함을 해결했으며 이번 검토에서 새로 확인된 결함(재현되는 오동작)은 없다. 아래 잠재 위험 2건(§3)은 현 T02 범위에서 수용 가능하나 운영 배포·T03 설계 전에 결정이 필요하다. 통합 조건은 §6.

## 2. RV-01~RV-09 개별 판정

| ID | 판정 | 근거(코드) | 확인한 회귀 테스트 |
|---|---|---|---|
| RV-01 동시 요청 멱등성 HIGH | **해결** | `BudgetReservationService.doReserve`가 control S → channel S(`lockForReservation`) → 정책 X 잠금 **후** `reservationByRequestKey`를 다시 읽는다(READ COMMITTED라 선행 커밋을 본다). 한도·상태 검사보다 앞서 재응답/409 판정. `doApplyEvent`는 bucket X → reservation X 잠금 후 `costLogByEventKey` 재확인. 전역 UNIQUE + `DuplicateKeyException` 재조회는 유지 | `BudgetConcurrencyIT` `concurrentReplaySucceedsAfterFirstRequestConsumesRemainingLimit`, `concurrentConfirmAndReleaseReplayAfterTerminalTransition`, `concurrentDifferentReservationAndFingerprintCannotReuseEventKey`, `concurrentDifferentReserveFingerprintReturnsConflictBeforeLimitCheck` |
| RV-02 신규 예약 판정 시각 HIGH | **해결** | 후보 bucket을 정책 잠금 아래 찾고 bucket X 획득 **후** `now`를 읽어 모든 bucket이 `now`를 포함하는지 확인, 아니면 `DecisionPeriodChanged`로 트랜잭션 전체 롤백 후 재계획(최대 3회 → 503 `SERVICE_UNAVAILABLE`). 환율 `validAt(currency, now)`도 잠금 후 시각 사용. 예약 `created_at`=판정 시각이라 RV-07 검사와 일치 | `decisionUsesNewDayAndMonthAfterWaitingForPolicyChange`, `decisionReplansBucketsAfterWaitingForAnExistingSettlement`, `decisionRejectsLatestExpiredRateAndStillReplaysExistingReservation` |
| RV-03 원장 내부 키 충돌 MEDIUM | **해결** | 새 RESERVE 키 `reserve-v2:` + SHA-256(requestKey) — 최대 75자, request key마다 고유. 기존 `reserve:` 행은 재작성하지 않음. 정산 이벤트가 두 접두사를 새로 쓰는 것은 400(기존 비 RESERVE 이벤트 재요청은 허용) | `BudgetLedgerIT` 120~128자 경계, 동일 내용 다른 키, 레거시 키 fixture |
| RV-04 KRW 정산 API 계약 MEDIUM | **해결** | `adjust` 금액 파싱을 정수 12자리로 제한(`DECIMAL(24,12)` 원장 범위), KRW 기준 소수 6자리. OpenAPI `if basis=KRW then pattern`, KRW 예약은 원본 비용도 함께 변함을 명시. 구현 동작은 기존과 동일(설계와 이미 일치) | `OpenApiContractTest`, `BudgetLedgerIT` 기준별 조정·환불, 13자리·소수 7자리 거부, 최대 금액 |
| RV-05 로그인 실패 시 세션 안전성 MEDIUM→HIGH | **해결** | `AdminAccountService.recordSuccessfulLogin`(last_login + 감사, 한 트랜잭션) 커밋 **후** 세션 ID 교체·컨텍스트 저장. 이후 예외 시 `SecurityContextHolder.clearContext()` + 세션 무효화 후 재throw. DB 커밋 후 세션 실패 시 감사 SUCCESS는 남는다(설계 문서에 의미 명시) | `AuthIT` DB 실패 주입 2종 후 보호 API 401, `AuthControllerTest` 세션 저장 후 실패 |
| RV-06 BIGINT 범위 MEDIUM | **해결** | 공통 `ApiId.parse`(양수·정규형·`Long` 범위)로 채널·예산 경로·쿼리·본문 ID를 변환, 범위 밖은 400 `VALIDATION_FAILED`. OpenAPI 설명에 범위 명시 | `BudgetLedgerIT` 0·음수·비숫자·최대값·최대값+1, 경로·쿼리 |
| RV-07 필수 bucket 연결 정합성 HIGH | **해결(현 스키마 범위)** | `BudgetMapper.reservationBucketIssues`가 예약마다 예약 시각을 포함하는 SYSTEM DAY·MONTH 각 1건, CHANNEL DAY·MONTH 각 ≤1건, 무효 연결 0건을 검사하고 `RESERVATION_BUCKET_LINK_MISMATCH`로 보고, 기존 합계 검사와 결정적 순서로 결합해 ERROR 기록(신규 예약 차단). DB CHECK `ck_budget_policy_scope`가 SYSTEM/CHANNEL을 DAY·MONTH로 제한하므로 정상 예약이 `period_type IN ('DAY','MONTH')` 조건에 걸리지 않음을 확인 | `BudgetLedgerIT` 링크 오류 fixture 4종, `BudgetTimeIT` 과거 revision·시간대, `BudgetFailClosedIT` 차단·기존 정산 |
| RV-08 동시 로그인 처리량 MEDIUM | **해결(잠재 위험 §3-1)** | `LoginAdmission`: login 1·IP 2·전체 4 동시 인증, 대기열 없이 429 `Retry-After: 1`, `finally`에서 해제(누수 없음), `synchronized` 상태. 차단 확인을 입장 전후 두 번 수행, 포화 응답은 실패 카운터에 미반영 | `AuthControllerTest` 세 한도·복구·예외, `AuthIT` 실제 HTTP |
| RV-09 트랜잭션 재시도 경계 HIGH | **해결** | `LockRetryingTransactions.execute`가 활성 상위 트랜잭션이면 콜백 전에 `IllegalStateException`. 제품 호출 경로 3곳(`reserve`, `markReconciliationRequired`, `applyEvent`)은 모두 비트랜잭션 컨트롤러에서 진입함을 확인(`grep`) — 기존 기능 영향 없음 | `rejectsParticipatingRetryBeforeCallbackOrSql`, `retriesRealDeadlockAfterRollingBackVictimWrites`, `realLockTimeoutExhaustsRetriesAndRollsBackEveryAttempt` |

### 회귀 테스트 적절성
- RV-09 적용으로 기존 동시성 테스트의 "외부 트랜잭션으로 잠금 유지" 방식이 금지되자, Codex는 테스트용 `beforeCommit` 일시정지로 **독립 트랜잭션의 커밋 직전**을 잡도록 바꿨다. 기존 11개 테스트(일시정지·보관·지출 차단·정책 생성/수정/비활성 대 예약, 이벤트 키 동시성, 강제 롤백 2종)는 이름·`assertBlocked` 대기 확인(9곳)이 모두 유지되어 **검증 강도가 약해지지 않았다.**
- 수정 전 커밋에서 실패를 재현한 뒤 수정 후 통과를 보인 기록(보고서)과 테스트 구성이 일치한다.

## 3. 잠재 위험 (재현된 결함 아님)

| ID | 심각도 | 위치 | 내용 | 권고 |
|---|---|---|---|---|
| CR-01 | MEDIUM(운영 배포 시) | `security/LoginAdmission.java:17-38`, `AuthController.java:91` | 인스턴스 전체 동시 인증 4건 상한. IP는 `remoteAddr`만 쓰므로(ADR-045) **역방향 프록시 뒤에 배포하면 모든 요청이 같은 IP** → IP당 2건·IP 실패 30회 차단이 관리자 전체에 적용되어 정상 로그인도 429/차단될 수 있다. 직접 노출 상태에서도 여러 IP가 슬롯 4개를 계속 점유하면 관리자 로그인이 429를 받는다(실패 30회/IP 차단으로 IP당 지속 시간은 제한됨, 대략 IP당 15분에 BCrypt 30건 — 상시 점유에는 다수 IP 필요). | 운영 배포 설계(신뢰 프록시·실제 클라이언트 IP)와 함께 상한을 재검토. 현 로컬·단일 관리자 T02 범위에서는 수용 |
| CR-02 | MEDIUM(운영 절차) | `BudgetMapper.reservationBucketIssues`, `BudgetControlService.runConsistencyCheck` | `RESERVATION_BUCKET_LINK_MISMATCH`가 한 번 발생하면 **자동 수정·관리 화면·복구 절차가 없어** 신규 지출이 계속 차단된다(설계상 자동 수정 금지는 맞음). 구버전 코드(`fa32535`)는 잠금 대기 전 시각으로 예약했으므로, 그 사이 CHANNEL 정책이 생성된 드문 경합으로 만들어진 기존 예약은 업그레이드 후 `p.created_at <= r.created_at` 조건에 걸려 ERROR가 될 수 있다(코드 분석, 실행 재현 안 함). 사용자 개발 DB는 UI로 예약을 만들 수 없어 해당 데이터가 없을 것으로 보이나 조회하지 않았다. | 운영 전 "링크 오류 진단·수동 복구 절차"(조사 쿼리, 승인된 보정 migration 또는 관리자 명령)를 ADR로 정의 |

### 개선 제안(결함 아님)
- `reservationBucketIssues`는 정합성 검사 때마다 전체 예약을 스캔하고 그동안 control X로 모든 예약·정산을 멈춘다. 예약 수가 커지는 T03 이후 검사 시간 측정·범위(기간) 지정 검토.
- 예약 판정 시각 재계획 3회 초과 시 503 — 내부 API이므로 T03 호출자가 503을 재시도 가능 오류로 다뤄야 한다.

## 4. 직접 실행한 검증

| 명령/작업 | 환경 | 결과 |
|---|---|---|
| `./gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --no-build-cache --rerun-tasks --continue` (DEBUG=false) | 검토 worktree `6a32c64`, 임시 MariaDB 11.8.2 `127.0.0.1:3307`(scratch datadir, `utf8mb4_unicode_ci`, UTC, deadlock detect ON) | **단위 133/133, 통합 97/97**, 실패·오류·skip 0, bootJar 성공 |
| 프론트 통합 시뮬레이션: 검토 worktree에 Claude UI(`5d53065`의 `frontend/`) + Codex 생성 타입(`6a32c64`) 결합 후 `npm ci`, `lint`, `typecheck`, `test`, `build`, `e2e`(Edge), 타입 도구 `npm ci && npm run check` | 같은 worktree | lint·typecheck 통과, **vitest 36/36**, build 성공, **Playwright E2E 8/8**, 생성 타입이 `6a32c64` 명세와 일치 |
| `git merge-tree --write-tree 5d53065 6a32c64` 및 변경 파일 교집합 | — | **충돌 없음, 양쪽이 함께 수정한 파일 0개** |
| `gh run view 45549256094` (Codex 최종 HEAD CI) | GitHub Actions | 6/6 success, gitleaks `19 commits scanned`, `no leaks found` |
| 제품 코드에서 재시도 경계 호출자 검색 | `6a32c64` | 3곳 모두 비트랜잭션 진입(§2 RV-09) |

실행하지 못한 것: 운영과 같은 프록시·CPU에서의 로그인 부하(CR-01), 구버전 데이터 업그레이드 경합 재현(CR-02), 실제 대화형 콘솔 관리자 CLI(사용자 검증 완료 보고 외), 운영 DB(binlog ON 트리거·권한).

## 5. API 계약 변경 영향과 UI 통합 주의사항

| 변경 | UI 영향 | 통합 시 조치 |
|---|---|---|
| `ConsistencyReport.issues[].checkCode`에 `RESERVATION_BUCKET_LINK_MISMATCH` 추가 | UI는 `lib/labels.ts`의 문자열 키 매핑(`CHECK_CODE`)이라 미등록 코드는 원 코드 그대로 표시(오류 없음, 위 시뮬레이션으로 확인) | `CHECK_CODE`에 "예산 예약 연결 불일치" / "비용 예약에 필요한 예산 연결이 누락되었거나 올바르지 않습니다. 신규 비용 예약이 차단되었습니다." 추가, 해당 화면 테스트 1건 추가 |
| 조정 금액: 정수부 최대 12자리, KRW 기준 소수 6자리 | Claude UI는 이미 `matchesAmount(amount, 12, basis==='KRW' ? 6 : 12)`로 같은 규칙 | 변경 없음 |
| KRW 예약의 KRW 기준 조정은 원본 비용도 변함 | UI 안내 "원본 통화 비용 불변" 문구는 **외화 예약일 때만** 표시하도록 이미 조건 처리 | 변경 없음 |
| ID 범위 초과 → 400 `VALIDATION_FAILED`(이전 500) | 원장 화면 "예약 번호" 입력은 형식만 검사, 범위 초과 시 서버 400이 공통 오류로 표시됨 | 선택: 입력 단계 범위 검사 추가 |
| 로그인 동시 한도 429 `Retry-After: 1` | 로그인 화면은 429를 "시도 횟수를 넘었습니다…"로 표시 — 의미가 "동시 요청 과다"와 다를 수 있음 | 선택: 문구를 "잠시 후 다시 시도하세요"로 일반화 |
| `schema.d.ts` 재생성(Codex 브랜치만 수정) | Claude 브랜치는 생성 파일을 수정하지 않음 | 통합 후 `npm run generate`/`check` 재실행만 |

## 6. 통합 조건과 계획
1. PR #1(`wip/t02-…`, UI) 위에 `fix/t02-backend`(`6a32c64`)를 병합(파일 교집합 없음 확인).
2. 통합 HEAD에서 `CHECK_CODE` 레이블 추가 + 테스트, `frontend/tools/openapi-typescript`의 `npm run check`, 백엔드 단위·통합, 프론트 전체, E2E, gitleaks(CI 7개 job) 재검증.
3. CR-01·CR-02는 운영 배포·T03 설계 승인 안건으로 DECISIONS에 기록(코드 변경 없음).

## 7. T03 설계 제약
- **예약 링크 검사 확장 필수**: T03에서 CONTENT_TYPE(LIFETIME) 정책 bucket이나 `content_id`가 있는 bucket을 예약에 연결하면 현 `reservationBucketIssues`가 이를 무효 연결(`valid_link=0`)로 판정해 **모든 신규 지출이 차단**된다. 콘텐츠 예산 연결 규칙과 검사 SQL을 함께 설계·변경한다.
- **트랜잭션 경계**: `LockRetryingTransactions.execute`는 상위 트랜잭션 안에서 호출할 수 없다(RV-09). T03 작업 상태 전이와 예산 예약·정산을 한 트랜잭션으로 묶을 수 없으므로, 작업 큐 상태와 예산 이벤트 사이의 원자성은 멱등 키(request/event key) + 재시도·정합성 검사로 설계한다(아웃박스 등 별도 ADR).
- **이벤트 키 이름 공간**: `reserve:`·`reserve-v2:` 접두사는 예약 전용이다. provider 정산 이벤트 키는 다른 접두사를 쓴다.
- **예약 판정 503**: 경계 재계획 소진 시 503 — 작업 큐 재시도 정책에 포함.
- **다중 인스턴스**: 로그인 제한(`LoginRateLimiter`, `LoginAdmission`)은 인스턴스 메모리다. 정책 `created_at`과 예약 `created_at` 비교(RV-07)는 같은 시계를 전제로 하므로 다중 인스턴스 배포 시 시계 동기 또는 DB 시각 사용을 검토.
- 정합성 검사의 전체 스캔·control X 정지 시간(§3 제안).

## 8. gitleaks fingerprint 예외(P-T02-02) 범위
- `.gitleaksignore`에는 커밋·경로·규칙·줄이 고정된 fingerprint 2건만 있다: `4b29e7b…:BudgetMapper.java:generic-api-key:485`(정책 bucket 중첩 SQL의 컬럼 비교), `e0ae07e…:.gitleaksignore:generic-api-key:2`(그 비교를 인용했던 주석). 규칙 비활성·경로 allowlist 없음.
- 정확히 해당 오탐만 제외됨을 CI 이력으로 확인했다: run 45525957400(예외 없음) leaks 1건 = 첫 fingerprint, run 45526405063(첫 fingerprint만 예외) leaks 1건 = 둘째 fingerprint(첫 건은 제외됨), run 45526843601 이후 0건. fingerprint가 커밋 단위라 이후 커밋·다른 파일의 같은 패턴은 다시 탐지된다. Codex `6a32c64` CI도 `no leaks found`.
- 판정: 범위 적절. P-T02-02 승인 권고(Codex 리뷰와 동일 결론).

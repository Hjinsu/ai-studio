# T03 구현 인수인계

A6 명세 승인 완료. B만 조건부 착수 승인, C~F BLOCKED. 기준 47ef697. Codex가 공통 migration 번호, OpenAPI 및 인터페이스 통합을 담당한다. 작업별 브랜치·worktree 및 고정 커밋을 착수 승인 후 지정한다.

| 작업 | 담당 | 선행 및 허용 경로 | 완료 조건 |
|---|---|---|---|
| B | Claude Code | 착수 승인, backend 도메인·해당 테스트. 공통 migration은 Codex 조율 | ownership 3열, topic·revision·run/활성 포인터, 신규/V5 DB 검증 |
| C | Claude Code | B의 참조 키와 D의 고정 operation 인터페이스. backend 큐 및 해당 테스트 | claim/sweep/heartbeat, 동시 Lease, P12와 R10·DAG 회귀 |
| D | Codex | B/C 참조 키 및 V5의 비용·DB·OpenAPI 계약. backend budget/operation 및 해당 테스트와 공통 파일 조율 | 전체 비용 안전성, 영속 정산, 구 API, 장애·권한·성능 검증 |
| E | Claude Code | OpenAPI 동결 및 타입 생성. frontend 및 해당 테스트 | 관리자 UI, 세션 세대, 금액/권한·fake·정산 recovery E2E |
| F | Codex | 승인된 B~E 검증 가능 HEAD | 독립 리뷰, CI 및 통합 테스트, 실제 결과 보고. Codex 작성분 리뷰는 별도 Codex 세션 |

B/C의 content(id,channel_id,content_type), automation_job(id), job_attempt(job_id,id) 대체 키를 D가 참조한다. D는 큐에 operation 분류, append-only dispatch/evidence, TX6 receipt와 P12 입력을 제공한다. 쓰기 트랜잭션을 모듈 경계로 중첩하지 않는다. C/D 병렬 작업은 고정 인터페이스 및 worktree 분리 후 가능하다. FK migration은 B/C 이후, UI mock 작업은 API 확정 이후에 가능하다. 동일 파일·공통 lockfile 변경은 통합 담당자에게 요청한다.

허용 경로 밖 변경, V1~V5 수정, 개발 DB 테스트, 실제 API/지출 허용·업로드는 금지한다. 제품 구현 후에도 DONE·main 병합은 CI/독립 리뷰/ChatGPT 최종 검토와 사용자 승인 이후다. Claude에게 별도 설계·독립 리뷰를 배정하지 않는다.

## T03-B 조건부 착수 배정 (2026-11-25)

ChatGPT 최종 검토 APPROVE. 사용자가 T03-A6 최종 명세와 아래 검증 조건을 포함한 T03-B 착수를 승인했다. FD-01~16과 ADR-067~076을 유지한다. C~F는 선행 조건 해소와 별도 착수 승인 전까지 BLOCKED다. Codex는 공통 Migration 번호·OpenAPI·인터페이스 통합 소유자로 유지한다.

- **담당 및 기준** — Claude Code는 `t03/b-domain`에서 구현한다. 시작 기준은 `t03/integration`에 기록한 `docs: freeze T03 design baseline and prepare T03-B` 설계 기준선 커밋이다. 제품 비교 기준은 `main@47ef69731a599c570846c1482dcabf8ead8fe2e4`. 별도 worktree를 사용하고 integration에서 구현하지 않는다.
- **V6 배정** — `backend/src/main/resources/db/migration/V6__create_content_domain.sql` 한 파일. V4 channel_config의 `UNIQUE(channel_id,id)` 추가 → topic → content → content_revision → content_run → current_revision_id/active_run_id/base_run_id 역참조 FK → B 범위 불변 트리거. Topic/Content/Revision 작성 멱등성에 필요한 공용 `queue_command_log`는 B의 V6에서 생성한다. V5에 대한 수정 및 비용/큐 실행 테이블 선행 구현은 금지한다.
- **후속 번호 예약** — C는 V7, D는 V8부터 사용 예정. 이 번호는 미래 배정을 위한 예약이다. C/D가 추가 Migration을 필요로 하면 작성 전에 Codex와 번호를 조율한다. C의 queue_control·queue_worker_instance는 automation_job·job_attempt 생성보다 먼저 준비한다. 특히 `job_attempt.worker_instance_id` FK 대상인 queue_worker_instance를 job_attempt보다 먼저 만든다(FV-01). D는 B/C의 참조 키가 존재한 뒤 FK를 추가한다.
- **수정 허용** — `backend/src/main/java/com/aistudio/{topic,content}/**`와 해당 테스트. 채널 보관·상태 전이의 FD-16 및 budget_control X 계약에 직접 필요한 `channel/ChannelService.java`, `channel/ChannelMapper.java`와 해당 테스트. V6, `MigrationConventionTest`, `DatabaseBootstrapIT`, `DatabaseUpgradeIT`, `ProductionGrantsIT`의 V6 검증 범위와 `infra/mariadb/prod-app-grants.sql`의 B 테이블 권한. 공통 파일은 Codex와 조율한다. `docs/reports/T03_B_REPORT.md`에 실제 결과를 기록한다.
- **기존 계약과 참조 키** — 사용자/채널 ID signed BIGINT, 기존 ADMIN 세션·CSRF, 문자열 ID, UTC, 19개 공통 오류 및 Idempotency-Key 규격을 유지한다. 채널 상태 전이는 budget_control X→channel X, 활성 Run 보관 거절은 409 INVALID_STATE_TRANSITION. B가 제공할 키는 `content(id,channel_id)`, `content(id,content_type)`, `content(id,channel_id,content_type)`, `content_revision(content_id,id)`, `content_run(content_id,id)`, `channel_config(channel_id,id)`. C가 제공할 `automation_job(id)`, `job_attempt(job_id,id)`는 D 의존성이다. 실행 가능한 Run 생성/Dispatch와 작업 큐 API 연결은 C 승인 이후, 비용·정산은 D 승인 이후다.
- **수정 금지** — V1~V5, V7 이후, 공통 OpenAPI·생성 타입, 예산·원장 코드, frontend, 워크플로와 사용자 개발 DB. B API 계약 변경이나 추가 공유 인터페이스가 필요하면 Codex에 근거와 영향을 전달한다. 실제 공급자, 지출 전환, 업로드는 이번 범위에서 실행하지 않는다.

### 필수 구현 검증 조건

| 조건 | B에서 확인할 범위 | 후속 게이트 |
|---|---|---|
| FV-01 | V6 범위와 C 선행 순서를 구분 | C에서 queue_worker_instance→job_attempt 신규 및 업그레이드 적용 확인 |
| FV-02 | B 불변 트리거, 운영 앱 최소 권한, 허용 경로/거부 경로 테스트 | 후속 테이블 동일 원칙, 배포 전 binlog ON 검증 |
| FV-03 | B 복합 FK·UNIQUE 및 channel/content 락 경합을 두 DB 커넥션 barrier로 검증 | C/D 공통 암묵/명시 락 매트릭스 확장 |
| FV-04 | Run 수명주기 소유권·포인터 제약 준비 | C/D에서 receipt당 P12 한 번, 비용 미확정 terminal Run 0 |
| FV-05 | B Controller의 공통 계약 회귀 및 OpenAPI 반영 요청 기록 | Codex가 통합 OpenAPI/생성 타입과 CI diff 검증 |
| FV-06 | Testcontainers 또는 별도 임시 MariaDB 전용, ai_studio 작업 DB/원장 접근 금지 | C/D 격리 FAKE 비용 테스트에서 실제 호출 0 및 원장 보호 |

Windows 저장소 루트에서 `gradlew.bat :backend:test`, `gradlew.bat :backend:integrationTest`, `gradlew.bat :backend:bootJar`를 실행한다. Linux는 `./gradlew`를 사용한다. [로컬 테스트 가이드](../../guides/LOCAL_TEST_GUIDE.md)의 Testcontainers 또는 별도 임시 서버만 사용한다. DB의 신규/V5 업그레이드, MigrationConventionTest V1~V5 해시 보존 및 V6 등록, 동일 채널 topic 중복과 교차 소유권 FK, NULL 우회 거절, Revision UPDATE/DELETE 거절, 활성 Run 포인터 원자성·동시성, 채널 보관 차단, 멱등 키 같은 내용 재생/다른 내용 409, 기존 권한/인증 회귀가 합격 조건이다. 필요한 V6 코드·테스트·권한을 완료하고 실제 결과와 미실행 항목을 보고한 뒤 IMPLEMENTED로 제출한다. Codex 독립 리뷰 후 통합 승인 절차를 진행한다.

## T03-B 계약 통합 및 V6 이력

RV-B-01~06 수정 기준은 `b5ffc73d61e100dbf4d9a847360aea404efd8e7d`이다. [초기 리뷰](../../reviews/T03_B_CODEX_REVIEW.md), [표적 재리뷰](../../reviews/T03_B_FIX_CODEX_REVIEW.md), [제품 계약 통합](../../reports/T03_B_INTEGRATION_REPORT.md)을 보존한다. 사용자가 승인한 미출시 V6 일회성 예외의 최종 LF 정규화 SHA-256은 `304900fda20821c83a9f3f3a56c9568f4215544158020569bd6f36e9d2412588`이다. C는 V7, D는 V8부터 새 Migration을 사용한다.

## T03-C 필수 인수 조건

[결함 수정 보고서 §3](../../reports/T03_B_FIX_REPORT.md)의 DB 검증 결과를 실제 C 서비스 완료 조건에 연결한다. C는 별도 승인 전 BLOCKED다.

| 조건 | 완료 기준 |
|---|---|
| C-G01 | Q0와 R10은 `LockRetryingTransactions`가 소유하는 최상위 READ COMMITTED 트랜잭션에서 실행한다. Run 쓰기 경로에 REPEATABLE READ를 사용하지 않는다. |
| C-G02 | Q0는 channel S → queue_control X → content X 순으로 획득한다. 채널·콘텐츠 상태, 고아 Run, 기존 비종료 Run 및 포인터를 content 잠금 아래 재검증한다. |
| C-G03 | Q0는 content X 획득 후 QUEUED Run을 INSERT하고 `active_run_id IS NULL` 조건으로 활성 포인터를 갱신한다. 행 수 1개를 요구하며 실패 또는 0행이면 Run 생성까지 같은 TX에서 전부 롤백한다. |
| C-G04 | R10 후보는 비잠금으로 찾는다. content X → content_run X → 후속 job·operation 등 승인된 정순으로 획득하고 동종 행은 PK 순서로 잠근 뒤 후보를 다시 검증한다. |
| C-G05 | R10은 모든 Job 종료, 미해결 비용·Operation 0 및 필수 P12 반영을 같은 잠금 범위에서 확인한다. |
| C-G06 | R10은 같은 TX에서 content 포인터 해제, DRAFT 전환 및 version 증가 후 Run을 종료한다. 각 UPDATE 영향 행은 정확히 1개여야 한다. 오류·0행·기대 외 행 수는 예외로 전파하여 전체 롤백한다. |
| C-G07 | R10의 감사·이벤트·카운터 및 필수 P12 반영을 같은 TX에 포함한다. |
| C-G08 | 실제 C 서비스에서 Q0 포인터 갱신 실패 및 R10 포인터 해제 직후 실패, 종료 0행, 감사 실패, 커밋 직전 실패를 주입한다. Run·포인터·상태·부수 효과의 전체 롤백을 검증한다. B의 SQL 계약 테스트와 별도로 수행한다. |
| C-G09 | 기동·Claim·Reconciler에서 `nonterminal_content_id`와 `active_run_id` 불일치를 탐지하고 실행을 차단하며 진단을 기록한다. 자동 수정·삭제는 금지한다. |
| C-G10 | `nonterminal_content_id` generated UNIQUE를 유지한다. 제거된 Run INSERT 트리거의 content 조회는 복원 금지다. 실제 C 서비스에서 채널 보관·Run 생성 및 Q0/R10 경합과 명시·암묵 잠금 순서를 검증한다. |

## T03-C 착수 배정 준비

- **상태 및 기준** — BLOCKED. 사용자가 별도 착수 승인한 후 Claude Code에 배정한다. 기준은 [PR #3의 실제 병합 SHA](https://github.com/Hjinsu/ai-studio/pull/3)인 `t03/integration`이다. 제안 브랜치는 `t03/c-queue`이며 별도 worktree에서 사용한다.
- **허용 범위** — T03-C 작업 엔진의 신규 백엔드 큐·Run 실행 패키지, C 전용 작업 엔진 테스트·fixture 및 V7 신규 마이그레이션. 승인된 Run 포인터 갱신을 연결하는 기존 content 계층의 최소 수정은 Q0/R10 경로에 한정한다. 기존 공통 파일에 필요한 변경은 Codex에게 경로와 최소 diff를 인계한다.
- **공통 경계 및 금지 범위** — V1~V6, 비용·원장 및 D 마이그레이션(V8부터), 프론트엔드, 공통 OpenAPI·생성 타입·빌드·CI 및 승인 설계는 제외한다. Codex가 공통 API와 C/D 인터페이스 통합을 맡는다. C는 큐의 Operation·Attempt·lease·dispatch·증거 및 P12 적용 추적을 책임진다. D는 영속 비용 예약·정산·receipt 및 복구를 책임진다. 공통 operation ID, 정순 잠금, receipt 적용과 불확실 상태의 책임은 승인된 [비용 및 복구](T03_FINAL_COST_RECOVERY.md)와 [잠금·트랜잭션](T03_FINAL_LOCK_TRANSACTION.md)을 따른다. 실제 공급자·업로드·지출 허용·개발 DB 마이그레이션은 금지한다.
- **마이그레이션** — V7에서 `queue_worker_instance`를 FK를 사용하는 `job_attempt`보다 먼저 생성한다. 빈 DB와 V6 업그레이드, FK/UNIQUE의 암묵 잠금, 불변 트리거와 앱 최소 권한을 검증한다. 운영 binlog ON의 migration 계정 및 DBA 트리거 생성은 배포 게이트다.
- **필수 테스트** — 격리 DB에서 C-G01~10 전체, Q0/R10 실제 장애 주입, 기동·Claim·Reconciler 고아 Run 차단, 채널 상태와 Q0 경합, lease·취소·지연 증거와 재호출 차단, 미확정 비용으로 인한 종료 차단, receipt당 P12 한 번 및 C/D 경계의 장애 복구를 검증한다. D의 영속 정산 복구는 구현 후 C/D 통합에서 검증하며 C의 대역 테스트로 대체하지 않는다. 테스트 명령은 기존 backend:test, backend:integrationTest, backend:bootJar를 사용한다.
- **완료 조건** — C 서비스의 Q0/R10 원자성, 불변성, 격리 FAKE 비용 테스트, 기존 회귀, 통합 OpenAPI·생성 타입 검증과 독립 리뷰 및 최신 CI 결과를 보고한다. 사용자 최종 승인 전 IMPLEMENTED 상태를 적용한다. D의 모든 콘텐츠 조회 경로 확인 후에만 budgetSummary를 required로 전환한다.

# 작업 상태
기준선: v0.1 설계 패키지 + ADR-006~062 승인(2026-11-25). 실제 계정 및 API 미연동.
현재 작업: T02 (IMPLEMENTED — CI 전 job 통과, 최종 설계 승인 대기, DONE 아님). 보고서 [docs/reports/T02_REPORT.md](docs/reports/T02_REPORT.md). T01 DONE(2026-11-25 승인). 기준: [docs/plans/T02_PLAN.md](docs/plans/T02_PLAN.md).
| ID | 상태 | 작업 |
|---|---|---|
| T01 | DONE (2026-11-25 승인, 커밋 55e33ec, CI run 45440907314) | 프로젝트 bootstrap, 버전 고정, DB/Flyway, CI, health check |
| T02 | IMPLEMENTED (CI run 45526843601 전 job 통과, 최종 설계 승인 대기) | 인증, 채널, 설정, 비용 원장 |
| T03 | TODO | 콘텐츠 revision, DB 큐, lease, 재시도 |
| T04 | TODO | fake provider, 조사/대본/스토리보드 |
| T05 | TODO | 이미지/TTS/자막/FFmpeg 로컬 렌더링 |
| T06 | TODO | 검수/승인/관리자 UI |
| T07 | TODO | 실제 provider 소액 검증 (허가 필요) |
| T08 | TODO | OAuth/private upload/예약 (허가 필요) |
| T09 | TODO | 성과 분석, 단일 채널 실험 |
| T10 | TODO | 트렌드 및 멀티채널 확장 |
## 인수인계 기록
작업 ID / 상태 / 변경 파일 / 테스트 명령 및 실제 결과 / 미해결 문제 / 다음 작업 / 필요 환경변수. 커밋 해시는 생성된 경우만 기록.

### T02 — 2차 재개 세션: 설계 승인 반영·검증 완료 (2026-11-25, IMPLEMENTED, DONE 아님)
요구사항: P-T02-01 승인(→ADR-062), T02 미완료 항목(T02_PLAN §2 단계 7~10), 예산 필수 검증 14항목, ADR-035 관리자 UI. T03 미착수. 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(커밋·푸시 승인, main 병합 금지).

**커밋**: `77b4680`(OpenAPI 평탄화·계약 검증기 테스트·예산 IT), `b64fa3e`(예산 동시성·시간·롤백 IT, 운영 권한 IT), `6677f38`(관리자 UI·생성 타입 도구·CI job), `beb7e8f`(보고서·가이드), `e0ae07e`·`93bbde3`(gitleaks 오탐 예외), CI 결과 기록 커밋(`git log`로 확인).

**구현·변경**
- OpenAPI: allOf 10곳 평탄화(ADR-062, JSON 구조 불변). `unevaluatedProperties` 방식은 검증기의 중첩 스키마 미정의 필드 누락을 실측해 기각. 이전 테스트 헬퍼의 명세 변형(`strictAllOf`) 제거.
- 테스트(백엔드): `OpenApiContractTest`(단위 4), `budget/BudgetConcurrencyIT`(11), `budget/BudgetTimeIT`(5, MutableClock), `security/ProductionGrantsIT`(2), 이전 세션 추가분 `BudgetFailClosedIT`(1)·`BudgetLedgerIT`(6). Gradle 테스트 입력에 `docs/api/openapi.yaml`, `infra/mariadb/prod-app-grants.sql` 선언.
- 프론트: `frontend/tools/openapi-typescript`(격리 도구, 자체 lockfile), `src/api/generated/schema.d.ts`(생성물), `src/api/{http,admin}.ts`, `src/ui/*`, `src/pages/*`(T02 화면 전체), `routes.tsx`, `Layout.tsx`, `queryClient.ts`, 테스트 3파일. react-router 8.5.3, @testing-library/user-event 14.6.13 고정.
- CI: `openapi-contract` job(Redocly lint + 생성 타입 diff).
- 문서: DECISIONS(ADR-062, P-T02-01 승인), docs/reports/T02_REPORT.md, docs/guides/LOCAL_TEST_GUIDE.md(임시 DB 실행·종료 절차), docs/16_SOURCES.md, docs/00_INDEX.md, .gitignore(`backend/bin/`).

**실제 테스트 결과**
| 명령 | 결과 |
|---|---|
| `./gradlew.bat :backend:test :backend:integrationTest --rerun-tasks` (임시 MariaDB 11.8.2 :3307) | 단위 125/125, 통합 69/69, 실패·skip 0 |
| frontend `npm run lint`·`typecheck`·`test`·`build` | 성공, vitest 26/26 |
| tools/openapi-typescript `npm ci`·`lint:openapi`·`check` | Redocly 유효(경고 2, 의도), 생성 타입 일치 |
| 백엔드(임시 인스턴스의 `ai_studio`)+Vite 프록시 API 스모크 | 전 단계 기대대로(T02_REPORT §5) |
| 변이 검사 4종 | 모두 검출, 원복 확인 |
| 비밀정보 | gitleaks 로컬 미설치(다운로드는 권한 정책으로 미실행) → 수동 패턴 검사(누수 없음), CI gitleaks job에 위임. 커밋 대상에 `.env`·node_modules·dist·빌드 산출물 없음 |

**미검증**: GitHub Actions(아래), Docker/Testcontainers 경로, 실제 브라우저 조작, 운영 DB(prod profile, binlog ON 트리거).

**CI**: Draft PR [#1](https://github.com/Hjinsu/ai-studio/pull/1)(main 대상, 사용자 승인, **병합 금지**, 워크플로 트리거 미변경)로 실행. run 45525957400·45526405063은 gitleaks 오탐으로 실패(상세 T02_REPORT §7) → `.gitleaksignore` fingerprint 예외(커밋 e0ae07e, 93bbde3, DECISIONS P-T02-02 승인 대기) → **run 45526843601(93bbde3) 전 job 통과**(Backend Linux unit+Testcontainers integration+image, Windows, Frontend ×2, OpenAPI contract, gitleaks). CI 통합 테스트 개수는 로그에 출력되지 않아 미확인.

**DB 상태**: 3306 작업 DB `ai_studio`는 변경하지 않음(여전히 migration 미적용). 임시 인스턴스(3307, 세션 scratchpad)는 테스트 종료 후 정상 종료. 절차: [docs/guides/LOCAL_TEST_GUIDE.md](docs/guides/LOCAL_TEST_GUIDE.md).

**주의(이 PC)**: vitest는 대문자 드라이브 경로(`C:\...`)에서 실행해야 한다(소문자 `c:\` 작업 경로에서는 모듈 이중 로드로 전 파일 실패). IDE의 Java 오류 표시는 `backend/bin` 오래된 classpath 기준이라 무시(Gradle 빌드 기준).

**다음 작업**: (1) 설계 담당 최종 검토(T02_REPORT, ADR-062, P-T02-02) → 승인 시 T02 DONE·PR 병합 여부 결정 (2) T03은 승인 전 시작 금지.

### T02 — 1차 재개 세션 (2026-11-25, 다른 PC, 당시 IN_PROGRESS)
요구사항: T02_PLAN §2 단계 6 검증·§4 테스트, ADR-026~061, T02_BUDGET_LEDGER §2~§6. T03 미착수. 커밋 없음(사용자 지시 대기). 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(origin에서 checkout, 4b29e7b 기준).

**환경 (이 PC)**: Windows 11, Gradle JDK 17.0.8 + toolchain JDK 21.0.13, Node 24.21.0, MariaDB 11.8.2 서비스(3306), Docker 없음. 루트 `.env` 존재(필수 변수 3개 설정). 로컬 `ai_studio` DB: 비어 있음(`flyway_schema_history` 없음, collation `utf8mb4_unicode_ci`, 계정 `ai_studio`@localhost ALL on ai_studio). **V1~V5 미적용 — 의도적**: P-T02-01 결정 전 migration을 로컬 DB에 적용하지 않음(적용 후 수정 금지 규칙). 적용은 `./gradlew.bat :backend:bootRun`(local profile) 시 자동.

**통합 테스트 실행 방법 (Docker 없는 PC)**: 작업 DB 서버가 아닌 **임시 MariaDB 인스턴스**를 띄우고 `AI_STUDIO_IT_DB_*`로 지정한다(TestDatabases가 `ai_studio_test_*` DB 생성).
```
"C:/Program Files/MariaDB 11.8/bin/mariadb-install-db.exe" --datadir=<임시폴더>/itdb --port=3307 --password=<임시>
"C:/Program Files/MariaDB 11.8/bin/mariadbd.exe" --defaults-file=<임시폴더>/itdb/my.ini --bind-address=127.0.0.1 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --default-time-zone=+00:00 --max-connections=300 --console
AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<임시> ./gradlew.bat :backend:integrationTest
```
(이번 세션은 세션 scratchpad에 생성, 저장소 밖. 사용 후 프로세스 종료·폴더 삭제 가능.)

**ChannelIT 실패 원인 분석 (이전 기록의 2건)**
- 두 건 모두 500이 아니라 **OpenAPI 계약 검증 실패**였다(이전 기록의 "500"은 이 PC에서 재현되지 않음, line 97은 contract matcher).
- (1) `createListGetAndUpdateChannelWithContract`: 검증 라이브러리(openapi-request-validator 3.0.0)가 응답 스키마에 `additionalProperties:false`를 자동 주입하는데 `allOf[PageMeta, {items}]` 하위 스키마마다 주입되어 정상 페이지 응답을 거부. `withResolveCombinators(true)`는 3.1 스펙에서 효과 없음(확인). 1차 조치였던 테스트 헬퍼 `strictAllOf`는 중첩 위반을 숨기는 것으로 확인되어 **제거**(P-T02-01). → 명세 평탄화로 해결, 통과.
- (2) `configRevisionsAreImmutableHistoryWithOptimisticBase`: **스펙 자체 모순** — `ChannelConfigSettings`가 `additionalProperties:false`를 명시하므로 `ChannelConfig`(allOf) 응답이 어떤 경우에도 유효할 수 없음. DECISIONS.md `P-T02-01` 제안 → **승인(ADR-062)** → `openapi.yaml` 평탄화(allOf 10곳 제거, JSON 구조 불변)로 해결, 통과. 상세는 DECISIONS P-T02-01.

**추가한 테스트 (예산 DB 통합, 이전 기록 "다음 작업 2")**
- `budget/BudgetFailClosedIT`(1): 지출 차단 기본값→POLICY_VIOLATION, SYSTEM 정책 없음/DAY만/MONTH 비활성, 환율 없음, PAUSED 채널→PREREQUISITE_NOT_MET, 0원 정책→BUDGET_EXCEEDED, SYSTEM 한도 초과·한도와 같은 금액 허용, 정합성 ERROR 시 차단·OK 후 해제, 지출 차단 중 정산 허용. 실패 시 예약·원장·bucket 기록 0건 확인.
- `budget/BudgetLedgerIT`(6): 설계 §2 USD 계산 예시 전체 단계 값 일치(PROVISIONAL 증감, CONFIRM 초과+BUDGET_OVERRUN audit, KRW 전용 ADJUSTMENT 후 C₀ 유지, 원본 REFUND), 연결 bucket 동일 반영, 불변식 위반 422 무기록, SETTLED→PROVISIONAL 409 / PROVISIONAL 없는 CONFIRM, RELEASE, 지연 과금(근거 필수, LATE_CHARGE), 비용 인식 후 RELEASE 거부, RECONCILIATION_REQUIRED 자동 해제 없음 / request_key 대소문자 구분·같은 키 다른 내용 409·event_key 멱등 / 20스레드 동시 예약(한도 1000, 100원씩) → 정확히 10건 성공·bucket 1개·reserved=1000, 같은 key 10스레드 동시 → 1건 / SYSTEM 사용량 중복 합산 없음, 대시보드·사용량·정책 목록·정합성 검사·환율 등록 API 계약 / cost_log·budget_reservation_bucket·exchange_rate·budget_policy_revision·budget_control 트리거 차단. 각 테스트 끝 정합성 검사 0건.
- 변이 확인: `BudgetReservationService` 한도 검사를 임시 무력화 → 2건 실패 검출 → `git checkout`으로 원복(diff 없음 확인).

**변경 파일**: `backend/src/test/java/com/aistudio/ApiTestSupport.java`(수정), `backend/src/test/java/com/aistudio/budget/{BudgetFailClosedIT,BudgetLedgerIT}.java`(신규), `DECISIONS.md`(P-T02-01 제안), `STATE.md`. main 코드·migration·openapi.yaml 변경 없음.

**실행한 테스트 (실제 결과)**
| 명령 | 결과 |
|---|---|
| `./gradlew.bat :backend:test` | 121 tests, 실패 0, skip 0 |
| `./gradlew.bat :backend:integrationTest`(임시 MariaDB 11.8.2 :3307) | **51 tests, 실패 1**(ChannelIT config revision — P-T02-01 대기), 나머지 50 통과: ActuatorExposureIT 13, DatabaseBootstrapIT 8, DatabaseUpgradeIT 1, ErrorDispatchIT 5, AdminBootstrapIT 2, AuthIT 9, ChannelIT 5/6, BudgetFailClosedIT 1, BudgetLedgerIT 6 |
- 미실행: CI(push 안 함), Docker/Testcontainers 경로, 프론트(변경 없음), gitleaks(미설치 — 변경은 테스트 코드·문서뿐이며 비밀값 없음, 임시 DB 비밀번호는 저장소 밖).

**미해결**
- P-T02-01 승인 필요 → 승인 시 openapi.yaml 대안 A 반영 후 ChannelIT 재실행.
- 예산 미검증 항목(T02_PLAN §4): 정책 revision/시간대 변경·채널 일시정지·지출 차단과 예약의 **동시** 실행, 예약 도중 강제 예외 rollback, 운영 앱 계정 권한(`prod-app-grants.sql`) 적용 계정 검증, 환율 만료 시 fallback 없음(DB IT), 채널 설정 revision 동시 생성.
- IDE(Eclipse/VS Code Java) 진단은 `backend/bin`의 오래된 classpath 기준이라 오류 표시가 실제 Gradle 빌드와 다름(`backend/bin/` untracked, 커밋 금지).

**다음 작업**: (1) P-T02-01 결정 (2) 위 예산 미검증 항목 IT 추가 (3) 서버 검증 완료 후 T02_PLAN §2 단계 7(관리자 UI)·8(타입 생성 도구). T03 시작 금지.

### T02 — WIP 인수인계 (2026-11-25 02:54 KST, IN_PROGRESS 유지)
이번 작업은 인수인계·저장만 수행했으며 신규 기능 코드는 수정하지 않았다. T02는 DONE이 아니고 T03는 시작하지 않았다.

**구현 확인 (코드 존재 기준, 완료 승인 아님)**
- 단계 2~3: Flyway V3~V5 신규 파일, `MigrationConventionTest` 해시 등록, Clock/lock retry/page response/audit 공통 코드가 작업 트리에 있다. V1·V2 파일은 변경되지 않았다.
- 단계 4: 관리자 CLI, 세션/CSRF 인증, rate limit, credential version 검증, audit 코드와 단위·통합 테스트가 있다.
- 단계 5: 채널 CRUD·상태 전이·설정 revision 및 `ChannelIT`가 있다.
- 단계 6: 환율·정책·예산 bucket/예약·정산·원장·정합성 검사·대시보드 백엔드와 계산 단위 테스트가 있다. 다만 예산 DB 통합 테스트는 아직 없다.
- 검증/기반: API 오류 처리 변경, OpenAPI 검증 테스트 의존성 및 일부 인증·채널 contract test가 있다.

**미완료 / 미검증**
- T02_PLAN.md §2 단계 7~10: 관리자 UI, 격리된 OpenAPI 타입 생성 도구·CI 검사, 전체 계약 검증, T02_REPORT 및 전체 CI 결과가 없다. 프론트엔드 파일은 이번 작업에서 변경되지 않았다.
- `ChannelIT` 최근 결과 XML(2026-11-25 02:48): 6건 중 4건 통과, 2건 실패. `configRevisionsAreImmutableHistoryWithOptimisticBase`는 응답의 `channelId`가 OpenAPI schema에 정의되지 않았다는 contract 오류. `createListGetAndUpdateChannelWithContract`는 요청 중 예상 200 대신 500. 원인 해결 전 통합 테스트 통과로 간주하지 않는다.
- 이외 AuthIT/AdminBootstrapIT/DatabaseUpgradeIT 및 예산 동시성·정산 DB 통합 검증은 성공 결과가 확인되지 않았다. 통합 테스트용 격리 DB 환경변수는 설정되지 않았고 Docker 명령도 사용할 수 없어 이번 인수인계 중 통합 테스트를 재실행하지 않았다.
- 현재 로컬 `ai_studio` DB는 MariaDB 11.8.2, Flyway V1·V2 성공 이력만 확인했다(읽기 전용 JDBC 조회). V3~V5는 미적용이며 이후에도 migration 파일을 수정하지 않는다.
- 실행한 명령: `.\gradlew.bat :backend:test` — 121 tests, 실패 0, 오류 0, skipped 0; 컴파일 포함 BUILD SUCCESSFUL.
- 민감정보/산출물: gitleaks는 설치되지 않았다. 변경 소스의 비밀키·일반적인 credential 패턴 수동 검색 결과 없음. 스테이징 목록 재확인에서도 `.env`, 인증 파일, build/node_modules/dist, 로그, 미디어, DB dump는 없었다. `.env`와 build/node_modules/dist는 Git ignore 처리되어 있다.

**현재 변경 및 저장**
- 기반 브랜치 `main`은 원격 `origin/main`보다 1개 커밋 앞서 있고, 그 커밋 `e1cef2c`는 T02 설계 기준선이다. 기존 작업 변경은 backend T02 코드·테스트와 V3~V5 migration이다.
- 변경은 별도 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`에 커밋 메시지 `wip: checkpoint T02 implementation`으로 저장·푸시한다. `.env`, 인증 비밀정보, 빌드 산출물은 포함하지 않는다.
- 다음 작업자는 해당 브랜치를 checkout/pull하고 이 기록 및 `docs/plans/T02_PLAN.md`를 먼저 확인한다.

**정확한 다음 작업**
1. `ChannelIT`의 위 두 실패를 재현해 원인을 해결한다(contract schema의 `channelId` 누락 여부와 500 발생 경로를 각각 확인).
2. 예산 정책·예약 동시성·정산/원장·정합성 검사를 격리 MariaDB 기반 통합 테스트로 추가하고 `.\gradlew.bat :backend:integrationTest`를 실행한다. 로컬 `ai_studio`를 테스트 대상으로 사용하지 않는다.
3. 인증·관리자 생성·V2→V5 upgrade 통합 테스트 결과까지 확인한 뒤, 서버 측 검증이 끝나면 T02_PLAN.md §2 단계 7(관리자 UI)을 이어간다. 그 전에는 T03를 시작하지 않는다.

### T02 — IN_PROGRESS (2026-11-25 구현 승인, ADR-039~061 반영)
설계 기준선: ADR-026~061, docs/design/*, docs/api/openapi.yaml, docs/plans/T02_PLAN.md, infra/mariadb/prod-app-grants.sql.

#### (이전 기록) 설계 단계
요구사항: ADR-026~038(D-01~D-13). 이번 단계는 설계 문서·DDL 초안·OpenAPI 초안만 작성, **도메인 코드 없음**. 커밋 없음.
- 산출물: docs/design/{T02_DATA_MODEL.md, T02_DDL_DRAFT.sql, T02_BUDGET_LEDGER.md, T02_AUTH.md}, docs/api/openapi.yaml, docs/plans/T02_PLAN.md(갱신), docs/reports/T02_DESIGN_REPORT.md, DECISIONS.md(ADR-026~038), docs/00, 03, 04, 09, 12, 13, 14, 16
- 검증(scratchpad, 저장소에 코드·도구 추가 없음):
  - DDL: 별도 임시 MariaDB 11.8.2(127.0.0.1:3307, scratch datadir, 기존 서버·데이터 미사용, 검증 후 삭제)에 V1→V2→초안 적용, 제약·트리거 42/42, 권한 분리(앱 계정 원장 UPDATE/DELETE 거부) 확인
  - OpenAPI: `@redocly/cli@2.83.0 lint` 유효(경고 1, 의도)
  - 계약 검증: `openapi-request-validator(-mockmvc) 3.0.0` + Boot 4.1.3 BOM 스파이크 17/17
  - 타입 생성: `openapi-typescript@7.13.0`은 frontend 설치 시 ERESOLVE(peer TS ^5), TS 5.9.3 격리 생성물은 TS 6.0.3 strict 통과
- 미해결: T2-Q1~Q14(T02_PLAN 5절), 동시성·Flyway 경유 적용은 구현 시 검증
- 다음 작업: T02_PLAN 2절 순서로 구현

### T01 — DONE (2026-11-25 설계 담당 승인)
완료 승인 근거: 커밋 55e33ec, GitHub Actions run 45440907314 전 job 성공. 유지되는 한계: CI job 로그를 독립적으로 확인하지 못함(인증 필요), 실제 운영 DB(prod profile·migration 계정 분리·binlog ON 트리거) 미검증.
요구사항: ADR-006~014, ADR-023(loopback), ADR-024, ADR-025, docs/02, 03, 04, 13, 14, 15. 설계 검토용 보고서: [docs/reports/T01_REPORT.md](docs/reports/T01_REPORT.md).

**커밋·CI**
- 커밋 `55e33ecb94b00e5384b10e04f7f88a7b918fb0fe` "feat: complete T01 foundation and design hardening"(82개 파일, `gradlew` 100755) → origin/main 푸시.
- 커밋 전 검사: 스테이징 대상에 `.env`·build·node_modules·dist·로그·영상·덤프 없음, `.env` 비밀번호 문자열 미포함, `gitleaks git --staged` 누수 없음.
- GitHub Actions run [45440907314](https://github.com/Hjinsu/ai-studio/actions/runs/45440907314) (2026-11-24T16:45:55Z~16:48:51Z UTC) **전 job success**:
  | job | 단계 결과 |
  |---|---|
  | Backend (Linux, unit + integration) | Unit tests ✓, Integration tests(Testcontainers MariaDB) ✓ 23초, Build boot jar ✓, Validate Docker Compose ✓, Build backend image ✓ |
  | Backend (Windows, unit only) | `gradlew.bat :backend:test :backend:bootJar` ✓ |
  | Frontend (ubuntu-24.04) / (windows-2025) | npm ci, lint, typecheck, test, build ✓ |
  | Secret scan (gitleaks) | ✓ |
- 한계: job 로그는 GitHub 인증이 필요해(API 403) 테스트별 개수를 직접 확인하지 못함. 통합 단계 성공은 Gradle 9가 테스트 미발견 시 실패하는 동작과 단계 소요(23초)로 DatabaseBootstrapIT·DatabaseUpgradeIT 실행을 간접 확인. 필요 시 Actions 화면에서 로그 확인.
- 커밋 이후 변경(미커밋): 이 CI 결과 기록, T02 설계 문서 일체.

**3차 변경 (설계 확정 반영)**
- 오류 처리(ADR-012, 024): ErrorCode 19개, `ErrorCode.fromStatus`(미분류 상태 유지), `ErrorResponses`(JSON Content-Type 고정 → 406도 본문), `ApiErrorController`(`/error`: 필터·sendError 오류 공통 형식), `GlobalExceptionHandler` 수정
- DB 보호(ADR-024, 025): `DatabaseGuardConfiguration`(구 LocalDatabaseGuardConfiguration, 모든 profile에서 Flyway 직전 검사), `RequiredDatabaseEnvironmentPostProcessor`(local/docker/prod, prod는 migration 계정 필수, expected-database 미설정 시 중단), profile별 expected-database(local/docker/prod=`ai_studio`, test=`ai_studio_test`), prod Flyway 계정 분리 설정
- `V2__fix_database_collation.sql`(ADR-014), local `server.address=127.0.0.1`(ADR-023), Vite 프록시 기본값 `127.0.0.1:8080`, Compose `--collation-server=utf8mb4_unicode_ci`
- CI: Windows backend job을 `shell: cmd` + `gradlew.bat`으로 변경(pwsh에서 POSIX `gradlew` 실행 불가 문제)
- 테스트 추가: ErrorDispatchTest, DatabaseGuardConfigurationTest, MigrationConventionTest, DatabaseUpgradeIT, TestDatabases, DatabaseGuardTestAccess. 수정: ErrorCodeTest, GlobalExceptionHandlerTest, RequiredDatabaseEnvironmentPostProcessorTest, DatabaseBootstrapIT
- 문서: DECISIONS.md(ADR-012 승인, 014~025, 이슈 상태표), docs/00, 02, 03, 04, 06, 07, 08, 09, 10, 12, 13, 14, README, .env.example, docs/plans/T02_PLAN.md(신규), docs/reports/T01_REPORT.md

**전체 변경 파일 (T01 누적)**
- 빌드: settings.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*, gradlew, gradlew.bat, backend/build.gradle.kts
- 백엔드 main: com/aistudio/AiStudioApplication.java, shared/api/{ErrorCode,ErrorDetail,ErrorResponse,ErrorResponses,ApiException,ApiErrorController,TraceIdFilter,GlobalExceptionHandler}.java, shared/db/{DatabaseTarget,RequiredDatabaseEnvironmentPostProcessor,DatabaseGuardConfiguration}.java, resources/META-INF/spring.factories, resources/application{,-local,-test,-docker,-prod}.yml, resources/db/migration/V1__initial_baseline.sql, V2__fix_database_collation.sql
- 백엔드 test: ActuatorExposureTest, ErrorDispatchTest, DatabaseBootstrapIT, DatabaseUpgradeIT, TestDatabases, shared/api/{ErrorCodeTest,TraceIdFilterTest,GlobalExceptionHandlerTest}, shared/db/{DatabaseTargetTest,RequiredDatabaseEnvironmentPostProcessorTest,DatabaseGuardConfigurationTest,MigrationConventionTest,DatabaseGuardTestAccess}
- 프론트: frontend/{package.json, package-lock.json, .nvmrc, tsconfig.json, tsconfig.node.json, vite.config.ts, eslint.config.js, index.html}, src/{main.tsx, App.tsx, api/client.ts, api/health.ts, pages/HealthPage.tsx, pages/HealthPage.test.tsx, test/setup.ts}
- 인프라/CI: infra/docker-compose.yml, infra/backend.Dockerfile, infra/mariadb/create-local-database.sql, .dockerignore, .github/workflows/ci.yml, .gitignore, .gitattributes
- 로컬 전용(Git 제외): `.env`

**실행한 테스트 (2026-11-25, Windows 10, Gradle JDK 17 + toolchain JDK 21.0.13, Node 24.19.0, 로컬 MariaDB 11.8.2)**
| 명령 | 결과 |
|---|---|
| `./gradlew :backend:test --rerun-tasks` (커밋 직전) | 성공. 단위 103개 실패 0, skip 0 (ActuatorExposure 13, ErrorDispatch 5, ErrorCode 18, GlobalExceptionHandler 35, TraceIdFilter 2, DatabaseGuardConfiguration 6, DatabaseTarget 11, MigrationConvention 4, RequiredDatabaseEnvironmentPostProcessor 9). deprecation 경고 없음 |
| MigrationConventionTest 변이 확인(임시 V99 CHARSET-only 파일 추가 후 실행, 즉시 삭제) | 규칙 위반·미등록 검출로 실패 확인 → 삭제 후 통과. 이 과정에서 `classpath:` 스캔 누락 버그 발견·수정(`classpath*:`) |
| 로컬 V1 DB 업그레이드: `./gradlew :backend:bootRun`(local) | `Verified connected database 'ai_studio'` → `Current version: 1` → `Migrating ... "2 - fix database collation"` → `now at version v2`. DB 기본 collation `utf8mb4_unicode_ci` 확인, history v1·v2 success |
| 재기동 | `Current version: 2`, `up to date`, health UP |
| DDL 규칙 확인(로컬 임시 테이블) | CHARSET/COLLATE 생략 → unicode_ci, `DEFAULT CHARSET=utf8mb4`만 → **uca1400**, COLLATE 명시 → unicode_ci |
| loopback 바인딩 | LISTEN `127.0.0.1:8080`만, LAN IP 접속 실패(000) 확인 |
| Vite 프록시 E2E(dev server 127.0.0.1:5173 → backend) | `/actuator/health` 200 UP, `/api/v1/nope` 404 |
| `cd frontend && npm run lint / typecheck / test / build` | 모두 exit 0, vitest 4/4 |
| `actionlint` 1.7.12 | 오류 없음 |
| `gitleaks dir`(커밋 대상) / `gitleaks git` v8.30.1 | 누수 없음. `.env` ignored 확인 |
| `git ls-files -s gradlew` | `100755`(staged) |

**로컬 미실행 → CI에서 실행된 테스트**
- `./gradlew :backend:integrationTest` — 로컬은 Docker 없음으로 실패. **CI Linux job에서 성공**(DatabaseBootstrapIT, DatabaseUpgradeIT; 테스트별 개수는 로그 인증 필요로 미확인).
- `docker build` / `docker compose config` — 로컬 미실행, **CI에서 성공**. Compose 실제 기동(up)은 미실행.
- GitHub Actions — run 45440907314 전 job 성공(Windows `gradlew.bat` 포함).
- prod profile(migration 계정 분리) — 단위 테스트만, 실제 운영 DB 미검증. 검증 범위: (1) 기동 전 URL의 DB 이름(두 계정 공통 URL) (2) Flyway 직전 migration 계정 `SELECT DATABASE()` (3) 계정이 분리된 경우 애플리케이션 계정 `SELECT DATABASE()`도 Flyway 전에 확인(DatabaseGuardConfigurationTest 6).

**미해결 / 주의**
- Flyway 12.4.0 "MariaDB 11.8 미검증(11.7까지)" 경고 — 조건부 허용(ADR-014), 로그에 그대로 출력.
- `flyway_schema_history` 테이블 collation: 로컬 unicode_ci. CI 컨테이너도 collation-server=unicode_ci로 맞춤(통합 테스트에서 미검증).
- 사용자 로컬 `.env`의 `AI_STUDIO_BACKEND_URL`은 이전 값(localhost) — Vite는 OS 환경변수만 읽으므로 영향 없음.
- Actuator health 하위 경로는 빈 본문 404(ADR-024 별도 계약).

**다음 작업**: 없음(T01 완료). T02 진행.

**필요 환경변수 (ADR-013, `AI_STUDIO_` 접두사만)**: 필수(local/docker/prod) `AI_STUDIO_DB_URL`, `AI_STUDIO_DB_USER`, `AI_STUDIO_DB_PASSWORD`; prod 추가 필수 `AI_STUDIO_DB_MIGRATION_USER`, `AI_STUDIO_DB_MIGRATION_PASSWORD`; 선택 `AI_STUDIO_BACKEND_URL`(기본 `http://127.0.0.1:8080`). 선행 조건: 루트 `.env`, JDK 17+, 인터넷(최초 JDK 21/의존성), Node 24 LTS, MariaDB 11.8 + 전용 DB/계정, Docker(선택).

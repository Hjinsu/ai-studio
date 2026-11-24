# 작업 상태
기준선: v0.1 설계 패키지 + ADR-006~038 승인(2026-11-25). 실제 계정 및 API 미연동.
현재 작업: T02 (IN_PROGRESS, 구현 승인 2026-11-25). T01 DONE(2026-11-25 승인). 기준: [docs/plans/T02_PLAN.md](docs/plans/T02_PLAN.md).
| ID | 상태 | 작업 |
|---|---|---|
| T01 | DONE (2026-11-25 승인, 커밋 55e33ec, CI run 45440907314) | 프로젝트 bootstrap, 버전 고정, DB/Flyway, CI, health check |
| T02 | IN_PROGRESS (설계 기준선 확정, 구현 중) | 인증, 채널, 설정, 비용 원장 |
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

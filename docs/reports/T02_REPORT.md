# T02 구현 보고서 (설계 검토용)
작성 2026-11-25 · 작성 Claude Code · 브랜치 `wip/t02-implementation-checkpoint-2026-11-25` · 상태: **IMPLEMENTED — PR #1에 PR #2 통합 완료(§11), Codex UI 리뷰·사용자 실제 화면 검증·최종 승인 대기(DONE 아님)** · T03 미착수

## 1. 범위와 결과 요약
| 영역 | 상태 | 근거 |
|---|---|---|
| Migration V3~V5(관리자·감사, 채널, 예산·원장) | 구현(이전 세션), V1~V5 신규·V1→V5 업그레이드 검증 | DatabaseBootstrapIT, DatabaseUpgradeIT, MigrationConventionTest |
| 인증(세션·CSRF·rate limit·credential_version·CLI) | 구현(이전 세션), 검증 | AuthIT 9, AdminBootstrapIT 2, 단위 테스트 |
| 채널·설정 revision | 구현(이전 세션), ChannelIT 실패 2건 해결 | ChannelIT 6/6 |
| 예산·환율·예약·정산·원장·정합성·대시보드 | 구현(이전 세션), **이번 세션 DB 통합 검증 추가** | Budget*IT 23, ProductionGrantsIT 2 |
| OpenAPI 계약 | **P-T02-01(ADR-062) 평탄화**, 검증기 동작 테스트 | OpenApiContractTest 4, 컨트롤러 계약 matcher |
| 생성 TS 타입 + CI 검사 | **이번 세션 구현** | `frontend/tools/openapi-typescript`, CI `openapi-contract` job |
| 관리자 UI(ADR-035) | **이번 세션 구현** | 프론트 테스트 26, 빌드, 프록시 스모크 |

## 2. 설계 변경 사항
- **ADR-062(P-T02-01 승인)**: OpenAPI `allOf` 10곳(*Page 8, ChannelConfig, ReservationDetail)을 평탄화. 요청·응답 JSON 구조·백엔드·DB 변경 없음.
  - 1차 검토한 `unevaluatedProperties: false`는 검증 라이브러리(openapi-request-validator 3.0.0)에서 **중첩 스키마의 미정의 필드(예: 페이지 items[] 내부)를 놓침**을 실측 → 승인 조건의 대안(평탄화) 채택.
  - 이전 세션 테스트 헬퍼(`ApiTestSupport.strictAllOf`)는 같은 이유로 계약 위반을 숨길 수 있어 제거. 헬퍼는 원본 명세를 변형 없이 사용.
  - 중복 정의 보호: allOf/anyOf 금지, *Page ⊇ PageMeta, ChannelConfig ⊇ ChannelConfigSettings, ReservationDetail ⊇ Reservation(속성 동일·required 포함)을 테스트로 강제.
- 그 외 설계 변경 없음. 의존성 추가: react-router 8.5.3, @testing-library/user-event 14.6.13(테스트) — T02_PLAN §3 "react-router 최신 안정 버전 확인·고정"에 따름(docs/16_SOURCES.md).

## 3. 예산 필수 검증 매핑
| 지시 항목 | 테스트 | 방법 |
|---|---|---|
| 채널 일시정지·보관 vs 신규 예약 | BudgetConcurrencyIT `pauseCommittedFirst…`, `reservationCommittedFirstThenPause…`, `archiveCommittedFirst…` | 한쪽 트랜잭션을 잠금 보유 상태로 열어 두고 상대가 대기함을 확인 후 커밋(결정적 인터리빙) |
| 긴급 지출 차단 vs 신규 예약 | `spendingDisableCommittedFirst…`, `reservationInFlightDelaysSpendingDisable` | 동일. 차단 중 기존 정산 허용 확인 |
| 정책 생성·수정·비활성화 vs 신규 예약 | `policyCreated…`, `policyLimitLowered…`, `policyDisabled…` | 동일. 열린 bucket 한도 갱신 확인 |
| 동일 멱등 키 동시 요청 | BudgetLedgerIT(예약 10스레드), BudgetConcurrencyIT `concurrentSameEventKeyAppliesOnce` | 1건만 기록 |
| 예약 실패 시 전체 롤백 | `reservationFailureAtLastStepRollsBackEverything`, `settlementFailureRollsBack…` | 테스트 DB에 임시 트리거로 마지막 단계(원장 INSERT) 강제 실패 → bucket 생성·예약·연결·집계 모두 롤백 |
| 잠정 비용 감소 후 복원, 증가·감소 반복 | BudgetLedgerIT `usdSettlementFollowsDesignExample…` | 설계 §2 계산 예시 값 전 단계 일치 |
| 해제 이후 지연 과금 | BudgetLedgerIT `confirmWithoutProvisionalReleaseAndLateCharge` | 근거 필수, LATE_CHARGE |
| 기존 기간 bucket 지연 정산 | BudgetTimeIT `lateSettlementIsAppliedToTheOriginalPeriodBucket` | 시계 이동 후 원래 기간 bucket 반영, 새 기간은 별도 bucket |
| 환율 누락·만료 | BudgetFailClosedIT, BudgetTimeIT `exchangeRate…WithoutFallback` | 누락·미래 발효·만료(이전 환율 fallback 없음)·등록 범위, 기존 예약은 고정 환율 정산 |
| 시간대 변경·DST | BudgetTimeIT `dstDays…`, `timezoneChange…WithoutBuckets`, `limitRevision…` | 23h/25h, 설계 §7 예시(bucket 미생성 구간 포함), 닫힌 기간 한도 유지 |
| 원본 통화 vs KRW 전용 조정 | BudgetLedgerIT | KRW 전용 ADJUSTMENT 후 C₀ 유지, 원본 REFUND |
| 운영 앱 계정 최소 권한 | ProductionGrantsIT | `prod-app-grants.sql`을 그대로 적용한 계정으로 T02 전체 흐름 실행 + 모든 테이블 DELETE·불변 테이블 UPDATE·TRUNCATE·DROP·ALTER·CREATE·TRIGGER·GRANT 거부 |
| 원장·불변 테이블 수정·삭제 차단 | BudgetLedgerIT `ledgerAndImmutableTablesRejectUpdateAndDelete`, ProductionGrantsIT | 트리거(관리자 계정)와 권한(앱 계정) 이중 확인 |
| Fail Closed(지출 차단 기본·정책 누락·비활성·0원·정합성 ERROR) | BudgetFailClosedIT | 실패 시 기록 0건 |

변이 검사(테스트가 실제 결함을 잡는지): 예약 한도 검사 무력화 → 2건 실패, 예약 경로 control 잠금 제거 → 2건 실패, 권한 템플릿에 DELETE 추가 → 실패, 명세 ChannelConfig에 additionalProperties: true·required 제거 → 실패. 모두 원복 후 diff 없음 확인.

## 4. 관리자 UI (ADR-035)
| 화면 | 기능 |
|---|---|
| 로그인 | CSRF 토큰 발급 후 로그인, 로그인·로그아웃 후 토큰 재취득, 실패 사유 비구분, 세션 만료(401) 시 로그인 화면 복귀 |
| 대시보드 | 지출 허용 여부, 정합성 상태, 정산 확인 필요 건수, 채널 수, SYSTEM 예산(합산 없음) |
| 채널 | 목록(상태 필터)·등록·기본 정보 수정·일시정지·재개·보관(확인 대화상자), 현재 설정·새 revision·이력 |
| 예산 정책 | 목록·생성·한도/시간대 revision·활성화/비활성화(확인)·revision 이력·기간별 bucket |
| 예산 사용량 | SYSTEM + 채널 정책 bucket(실제/예약/잔여 구분) |
| 지출 제어·정합성 | 긴급 지출 차단/허용(사유 필수, 확인), 정합성 검사 실행·이슈 표시 |
| 예약·정산 확인 | 목록(확인 필요 필터)·상세·과금 확정/미과금 해제·지연 과금(근거 필수)·조정/환불(원본/KRW 기준), 작업마다 멱등 키 유지 |
| 비용 원장 | 필터 조회, 예약 증감/비용 증감 구분 |
| 환율 | 목록·등록(발효 시각 선택) |
공통: 금액·환율은 API 문자열 그대로, 시각은 채널/bucket 시간대(없으면 브라우저 시간대) 표시, 로딩·빈 상태·오류(코드별 안내·추적 ID) 표준화, 위험 작업 확인 대화상자, 레이블·힌트 접근성(aria-describedby).

## 5. 테스트 실제 결과 (2026-11-25, Windows 11, JDK 21.0.13 toolchain, Node 24.21.0, 임시 MariaDB 11.8.2 :3307)
| 명령 | 결과 |
|---|---|
| `./gradlew.bat :backend:test :backend:integrationTest --rerun-tasks` | 단위 **125/125**, 통합 **69/69** (실패·skip 0) |
| 통합 내역 | ActuatorExposureIT 13, DatabaseBootstrapIT 8, DatabaseUpgradeIT 1, ErrorDispatchIT 5, ChannelIT 6, AuthIT 9, AdminBootstrapIT 2, ProductionGrantsIT 2, BudgetConcurrencyIT 11, BudgetFailClosedIT 1, BudgetLedgerIT 6, BudgetTimeIT 5 |
| `npm run lint` / `typecheck` / `test` / `build` (frontend) | 모두 성공, vitest **26/26** |
| `npm run lint:openapi` / `check` (tools/openapi-typescript, `npm ci`) | Redocly 유효(경고 2: 의도된 CSRF 4xx 없음, 기준 스키마 PageMeta 미참조) / 생성 타입 일치 |
| 백엔드+Vite 프록시 스모크(임시 인스턴스의 `ai_studio` DB) | V1~V5 적용 후 기동, 로그인 실패/성공, 로그인 후 이전 CSRF 403, 채널·설정·정책·환율·지출 허용·정합성·대시보드·원장·로그아웃 후 401 — 전부 기대대로 |

미실행·미검증(로컬): Docker/Testcontainers 경로와 gitleaks는 로컬에서 실행하지 못했고 CI에서 통과(§7), 실제 브라우저 화면 조작(브라우저 자동화 도구 없음 — 컴포넌트 테스트와 API 스모크로 대체), 운영 DB(prod profile·binlog ON 트리거, ADR-050).

## 6. 미완료·한계
- T02 DONE 조건: CI 전 job 통과(충족) + 최종 설계 승인(대기).
- 로컬 `ai_studio`(3306)에는 migration을 적용하지 않았다(작업 DB 변경 금지 지시). 처음 `bootRun` 시 V1~V5가 적용된다.
- 관리자 UI의 대량 데이터 성능·브라우저 호환은 미검증.

## 7. 커밋·CI
| 커밋 | 내용 |
|---|---|
| 77b4680 | OpenAPI 평탄화(ADR-062), 계약 검증기 테스트, 예산 Fail Closed·원장 IT |
| b64fa3e | 예산 동시성·시간·롤백 IT, 운영 권한 IT |
| 6677f38 | 관리자 UI, 생성 타입 도구, CI openapi-contract job |
| beb7e8f | 보고서·가이드·STATE |
| e0ae07e, 93bbde3 | gitleaks 오탐 fingerprint 예외(`.gitleaksignore`) |

CI는 Draft PR [#1](https://github.com/Hjinsu/ai-studio/pull/1)(main 대상, **병합 금지**)의 pull_request 트리거로 실행했다. 워크플로 트리거는 변경하지 않았다.
| run | 커밋 | 결과 |
|---|---|---|
| 45525957400 | beb7e8f | gitleaks 실패(오탐 1건: 커밋 4b29e7b의 BudgetMapper 정합성 SQL에서 두 bucket의 subject 컬럼 비교가 generic-api-key로 탐지). 그 외 5개 job 통과 |
| 45526405063 | e0ae07e | gitleaks 실패: 예외 파일 주석이 같은 SQL을 인용해 다시 탐지(작성 실수). 그 외 통과 |
| **45526843601** | **93bbde3** | **전 job 통과** — Backend Linux(unit + Testcontainers integration + bootJar + compose config + Docker image), Backend Windows(unit + bootJar), Frontend ubuntu/windows(lint·typecheck·test 4파일·build), OpenAPI contract(Redocly 유효, 생성 타입 일치), gitleaks(no leaks found) |

한계: Gradle 로그는 테스트별 개수를 출력하지 않아 CI의 통합 테스트 개수(로컬 69)는 직접 확인하지 못했다. 오탐 예외 방식은 DECISIONS P-T02-02(승인 대기)로 기록.

## 8. 사용자 검증 결함: 관리자 CLI 실행 실패 (2026-11-25 수정)
**증상**: `java -jar backend/build/libs/backend-0.1.0-SNAPSHOT.jar admin create --login admin` → `BeanCreationException: adminCommandRunner` / `No default constructor found` / `NoSuchMethodException: AdminCommandRunner.<init>()`.

**원인 (2건)**
1. **Bean 생성자 선택 실패**: `AdminCommandRunner`에 public 생성자(1인자, 운영용)와 package-private 생성자(3인자, 단위 테스트용)가 함께 있고 어느 쪽에도 `@Autowired`가 없었다. 생성자가 둘 이상이면 Spring은 주입할 생성자를 고르지 못하고 기본 생성자를 찾다 실패한다. 단위 테스트는 3인자 생성자를 직접 호출했고, `ai-studio.cli.admin=true`로 컨텍스트를 띄우는 테스트가 없어 누락됐다.
2. **인자 해석 불일치(1을 고친 뒤 드러남)**: Spring `ApplicationArguments`는 `--name=value`만 옵션 값으로 인식한다. 문서·사용법의 `--login admin`(공백)은 `--login`(값 없음) + 비옵션 `admin`이 되어 명령 인자가 3개 → 항상 "사용법" 거부. 즉 결함 1이 없었어도 문서대로의 명령은 동작하지 않았다.

**수정 (기본 생성자 추가 없음, 정책 완화 없음)**
- `AdminCommandRunner`: Spring 생성자를 `@Autowired`로 명시 — `(AdminAccountService, ObjectProvider<PasswordPrompt>)`. 입력 장치는 PasswordPrompt Bean이 없으면 시스템 콘솔(운영 코드에는 Bean 정의 없음, 테스트 컨텍스트에서만 숨김 입력 대체). 콘솔 없으면 거부·stdin 평문 대체 금지는 그대로.
- `AdminCommandRunner.parse`: 원본 인자를 엄격 해석 — `--login <id>`와 `--login=<id>` 모두 허용, `--key=value`(Spring 설정 재정의)는 무시, 값 없는/중복 `--login`·알 수 없는 옵션·남는 인자는 거부.
- `AiStudioApplication.adminCli()`: CLI용 비웹(`WebApplicationType.NONE`) 빌더를 분리해 main과 테스트가 같은 구성을 사용.
- `backend/build.gradle.kts`: `integrationTest`가 `bootJar`에 의존하고 jar 경로를 시스템 속성으로 전달.

**추가 테스트**
| 테스트 | 검증 |
|---|---|
| `security/AdminCliIT` (통합) | main과 같은 `adminCli()` 구성으로 실제 Spring Bean 생성 경로 실행: 비웹 컨텍스트(WebServerApplicationContext 아님), Runner Bean 1개, 콘솔 없음 거부(기록 0), 확인 불일치 거부, create 성공(BCrypt 해시·bootstrap 기록), 두 번째 create 거부, reset-password 성공(해시 변경·credential_version +1), 없는 로그인·약한 비밀번호 거부, 잘못된 명령 거부. `--login <id>`와 `--login=<id>` 모두 사용 |
| `security/AdminCliJarIT` (통합) | **실제 bootJar를 하위 프로세스로 실행**(콘솔 없음): create·reset-password 모두 Spring 컨텍스트 정상 초기화(Flyway V1~V5 적용 확인) → "콘솔 없음" 거부(종료 코드 2), BeanCreation/시작 실패 문구 없음, 웹 서버(Tomcat 등) 로그 없음, 스스로 종료, 관리자 미생성, 사용법 오류 시 종료 코드 2 |
| `AdminCommandRunnerTest` (+2) | 인자 해석: 공백/등호 형식, 설정 재정의 무시, 잘못된 형식 7종 거부(서비스 호출 없음) |
회귀 확인(변이): `@Autowired` 제거 → 두 통합 테스트가 보고된 것과 같은 `No default constructor found`/`NoSuchMethodException: AdminCommandRunner.<init>()`로 실패. 인자 해석만 이전 방식으로 되돌림 → 두 통합 테스트 실패. 모두 원복.

**실제 실행 결과 (Windows, Java 21.0.13)**
| 실행 | 결과 |
|---|---|
| bootJar + local profile(`AI_STUDIO_DB_*`를 임시 인스턴스 3307의 `ai_studio`로 지정, 콘솔 없는 하위 셸) `admin create --login admin` / `admin reset-password --login admin` / `admin create --login=admin` | DB 검증 → Flyway up to date → "콘솔이 없습니다" 거부, 종료 코드 2, 웹 서버 로그 없음 |
| 같은 조건 `admin create` | "사용법" 거부, 종료 코드 2 |
| `./gradlew.bat :backend:test :backend:integrationTest --rerun-tasks` | 단위 127/127, 통합 71/71 (AuthIT 9, AdminBootstrapIT 2, ProductionGrantsIT 2, AdminCliIT 1, AdminCliJarIT 1 포함) |
미검증: 실제 대화형 콘솔에서의 숨김 입력 성공 경로(자동화 도구에 TTY가 없음 — 성공 경로는 AdminCliIT의 실제 Bean 경로로 검증). 사용자 터미널(cmd/PowerShell)에서 `admin create --login <id>` 재확인 필요. 3306 작업 DB는 이번 검증에서 사용하지 않았다.

**CI**: 수정 커밋 `6d5cccd` → run [45530093712](https://github.com/Hjinsu/ai-studio/actions/runs/45530093712) 전 job 통과(Backend Linux: bootJar → integrationTest, Testcontainers). Draft PR #1 유지.

## 9. T02-UI: 관리자 UI·UX 개선과 디자인 시스템 (Claude Code, 커밋 `5d53065`)
**사용자 실제 화면 검증(개선 전 UI, 사용자 보고)**: 관리자 계정 생성, 로그인, 채널 등록, 설정 수정·이력, 예산 정책 등록·수정, 예산 사용량, 정합성 검사, 예약·정산, 비용 원장, 환율 등록, 서버 상태, 로그아웃·재로그인 정상. **개선 후 UI는 사용자 재검증 전**이다.

**구조 변경(전 → 후)**
- 전: 단일 `styles.css`(전역 클래스), `Layout`(평면 메뉴 9개), 화면마다 직접 만든 표·폼, 금액 문자열 원본 표시(`300000.000000 KRW`), 채널 ID 직접 입력.
- 후: Tailwind 4 토큰(라이트/다크 CSS 변수) + `src/ui`(공통 컴포넌트) + `src/layout`(AppShell: 그룹 사이드바·접기·테마·모바일 드로어, 헤더: 경로·서버 상태·계정) + `src/lib`(decimal·labels·time·theme·errors) + 화면별 지연 로딩. 금액 `₩300,000`(정밀도 유지, Number 변환 없음), 채널은 이름 선택.

**디자인 시스템**: 토큰(Primary·Secondary·Background·Surface·Text·Muted·Success·Warning·Danger·Border, 타입 스케일 12/13/14/16/20/24, 간격, 반경 6/10/pill, overlay 그림자, 브레이크포인트·컨테이너 쿼리), Pretendard 로컬 번들. 공통 컴포넌트와 사용 규칙은 [UI_DESIGN_SYSTEM.md](../design/UI_DESIGN_SYSTEM.md). 의존성·채택 이유는 ADR-063, 미채택(Recharts, SearchInput)과 이유 포함.

**화면별 개선**: 로그인(브랜드, 비밀번호 표시 전환, 진행 상태·중복 제출 방지, 세션 만료 안내) · 대시보드(지출 제어·정합성·정산 확인 필요·채널, 오늘/이번 달 예산·실제 지출·예약, 일간·월간 예산 미터, SYSTEM만 집계, 데이터 없음 표시) · 채널(한국어 상태 배지, 기본 콘텐츠 유형·최근 설정 변경, 그룹 폼과 설명, 상세 탭, revision 타임라인·직전 대비 변경·두 revision 비교) · 예산 정책(범위 배지·필터, 채널 선택(보관 제외), 한국어 범위·기간, ₩ 미리보기 입력, 0원 전면 차단 안내, 상세 탭: 기간별 사용·한도/시간대 변경·변경 이력) · 사용량(시스템/채널 탭, 미터, 소진 임박·초과, 적용 가능 잔여(참고)와 한계 명시) · 지출 제어(차단 상태 강조, 위험(risk) 버튼, 사유 필수 확인, 신규 지출 조건 체크리스트, 정합성 한국어 상태·오류 표) · 예약·정산(한국어 상태 배지·필터, 정산 확인 필요 강조, 원장 타임라인, 멱등 키 유지, 지연 과금·조정 규칙) · 원장(장부형 표, 한국어 유형, 증감 부호·화살표, 채널 선택 필터) · 환율(통화 선택, `1 USD = ₩1,500`, 적용 중/예정/만료/이전 상태, 남은 시간, 등록 제약 안내) · 서버 상태(정상/확인 중/연결 실패).

**검증(실제 결과)**: `npm run lint`·`typecheck`(앱+E2E)·`test` **36/36**·`build`(초기 번들 354KB/gzip 113KB) · Playwright(설치된 Edge, 브라우저 네트워크 계층 API 대역) **8/8**: 로그인·CSRF·로그아웃, 다크 모드 적용·유지, 채널 목록·등록·이력 비교, 정책 생성(채널명 선택·보관 제외·문자열 금액), 지출 허용 확인 절차, 모바일 390px 7개 화면 가로 넘침 없음·드로어, 나머지 화면 오류 없음, 빈 예산 실제 빈 상태. 브라우저 스크린샷으로 라이트/다크·모바일 디자인 점검 후 수정(사이드바 높이, 금액 잘림, 0 증감 표시 등). 생성 타입 `npm run check` 일치. **CI run 45554314696(PR #1, `5d53065`) 7/7 통과**(신규 `Frontend E2E (Edge, windows-2025)` 포함).
- 실제 백엔드와의 브라우저 E2E는 이번에 실행하지 않았다(API 계층·계약 불변, 이전 프록시 스모크로 API 흐름 확인). 사용자 실제 화면 재검증 필요.
- 구현하지 않은 개선(새 API 필요): P-T02-03.

## 10. 백엔드 독립 교차 리뷰 (Claude Code, `fa32535..6a32c64`)
보고서: [T02_CLAUDE_BACKEND_REVIEW.md](../reviews/T02_CLAUDE_BACKEND_REVIEW.md). RV-01~09 모두 해결 판정, **새로 확인된 결함 없음**, 잠재 위험 CR-01(프록시 뒤 로그인 제한)·CR-02(링크 오류 복구 절차 부재)는 P-T02-04로 기록. 독립 재실행: 검토 전용 worktree에서 단위 133/133·통합 97/97·bootJar 성공, UI+Codex 생성 타입 결합 시 프론트 36/36·E2E 8/8·타입 일치, `git merge-tree` 충돌·공통 수정 파일 0개. 통합 시 `CHECK_CODE`에 `RESERVATION_BUCKET_LINK_MISMATCH` 레이블 추가 필요.

## 11. T02 통합 (PR #1 ← PR #2) 및 최종 검증 (2026-11-25, Claude Code)
**승인 근거**: 사용자 T02 조건부 통합 승인(ADR-063·064·065, P-T02-02 최종 승인, P-T02-03 보류, P-T02-04 조건부 수용). T02 최종 종료·main 병합 승인은 아니다.

### 11.1 커밋
| 구분 | 커밋 | 내용 |
|---|---|---|
| 통합 전 PR #1 HEAD | `862a300` | UI `5d53065` + 교차 리뷰·문서 |
| 통합 전 PR #2 HEAD(승인) | `6a32c64` | Codex RV-01~09 (`25bcf75`, `52c6f97`, `7b7a573`, `bb865f4`, `396575c`, `6a32c64`), 작성자 정보 보존 |
| 협업 문서 | `67d50b9` | AGENTS.md·CLAUDE.md 협업 규칙(UI 규칙 보존), TASKS.md, `docs/guides/CODEX_COLLABORATION.md`, `docs/guides/LOCAL_RUN_GUIDE.md`, `docs/reviews/T02_CODEX_REVIEW.md`, ai-studio 스킬 2곳 정렬 |
| 병합 | `07f8963` | `git merge --no-ff 6a32c64` — 충돌 없음, 이력 재작성·강제 푸시 없음. 병합 후 `backend/`, `docs/api/openapi.yaml`, 생성 타입이 `6a32c64`와 동일(`git diff 6a32c64 HEAD` 해당 경로 0) |
| UI 통합 보완 | `8319672` | 정합성 코드 `RESERVATION_BUCKET_LINK_MISMATCH` 레이블("예산 예약 연결 불일치")·설명, 로그인 429 중립 안내 + `Retry-After` 대기 시간, 회귀 테스트 3건 |
| 문서 | 이 보고서 커밋 | DECISIONS, T03_PLAN, 사용자 검증 가이드, 00_INDEX, STATE, TASKS |

- PR #2의 모든 커밋이 PR #1 브랜치에 포함됨(`git merge-base --is-ancestor 6a32c64 HEAD`). PR #2는 PR #1 브랜치를 대상으로 하므로 푸시 후 GitHub에서 **MERGED로 자동 표시**되었다(2026-11-25T06:04:28Z, main 병합 아님, PR·브랜치 삭제 없음).
- Codex worktree(`../ai-studio-t02-fix`, `fix/t02-backend`)는 수정·삭제하지 않았다.

### 11.2 협업 문서와 스킬 충돌 검토
- 프로젝트 스킬(`.claude/skills/ai-studio`, `.agents/skills/ai-studio`, 동일 내용)은 필수 문서에 TASKS.md·협업 가이드가 없고 "미완료 작업 확인"·"STATE.md에 기록"만 있어 새 규칙(배정된 작업만, 병렬 시 공통 파일은 통합 담당자)과 어긋났다 → 두 사본에 같은 최소 수정(3줄). 그 외 충돌 없음. AGENTS.md의 UI 디자인 시스템 규칙(`862a300`)은 보존.
- 비밀정보 패턴 검사: 커밋한 협업 문서 8개에서 해당 없음. `.env`·DB 덤프·임시 데이터·빌드 산출물 미포함(경로 지정 `git add`만 사용).

### 11.3 통합 UI 보완과 계약 호환성
| 항목 | 결과 |
|---|---|
| `RESERVATION_BUCKET_LINK_MISMATCH` | 지출 제어 화면 정합성 오류 표에 제목·설명·원 코드·대상(예약 #id) 표시. 회귀 테스트 추가 |
| 로그인 429 | 서버는 실패 반복 차단과 동시 인증 제한(RV-08)에 같은 코드·메시지를 쓰므로 원인을 단정하지 않는 안내("실패한 로그인이 반복되었거나 동시에 처리 중인 로그인 요청이 많으면 제한됩니다.") + 계약 헤더 `Retry-After`(정수 초)로 "N초 후/약 N분 후" 안내. HTTP-date 형식은 무시. 회귀 테스트 2건 |
| KRW 정산 금액 | UI 검증 = 서버 계약(정수 12자리, KRW 기준 소수 6·원본 12). 변경 불필요 |
| BIGINT ID | UI는 서버가 준 ID만 사용. 원장 화면 예약 번호 입력의 범위 초과는 서버 400 `VALIDATION_FAILED`가 공통 오류로 표시(신규 UI 기능 미추가) |
| 생성 TypeScript 타입 | `frontend/tools/openapi-typescript`에서 `npm ci` → `npm run generate` → 변경 없음, `npm run check` 일치, Redocly 유효(기존 경고 2) |

### 11.4 통합 후보 HEAD 검증(실제 결과)
| 검증 | 환경 | 결과 |
|---|---|---|
| 백엔드 단위 | 통합 HEAD(`8319672` 제품 코드), `--no-build-cache --rerun-tasks`, DEBUG=false | **133/133** (OpenApiContractTest 5, AuthControllerTest 5 포함) |
| 백엔드 통합(동시성·보안 회귀 포함) | 격리 임시 MariaDB 11.8.2 `127.0.0.1:3307`(deadlock detect ON) | **97/97** — BudgetConcurrencyIT 21, BudgetLedgerIT 18, BudgetTimeIT 6, BudgetFailClosedIT 1, AuthIT 12, AdminCliIT 1, AdminCliJarIT 1, ProductionGrantsIT 2 등 |
| Boot JAR | 같은 실행 | 성공(`backend/build/libs/backend-0.1.0-SNAPSHOT.jar`) |
| OpenAPI 계약·생성 타입 | 위 11.3 | 통과 |
| 프론트 lint·typecheck(앱+E2E)·단위·build | `frontend` | 통과, **vitest 39/39**, 초기 번들 354KB(gzip 113KB) |
| 브라우저 E2E | Playwright, 설치된 Edge, **Mock API**(브라우저 네트워크 계층 대역) | **8/8** — 실제 백엔드 연동 검증이 아님 |
| gitleaks | 로컬 미설치(다운로드는 권한 정책상 미실행) | CI job으로 검증 |
| GitHub Actions 7개 job | 통합 HEAD `ab68286` | **run 45557933535 전 7개 job 성공**(gitleaks 포함) |

### 11.5 Codex UI 독립 리뷰
- 요청: TASKS.md `T02-UI-RV`(Codex). 기준 = 제품 코드 최종 커밋 `8319672`(이후 커밋은 문서만), 범위 `fa32535..8319672`의 `frontend/`와 `docs/design/UI_DESIGN_SYSTEM.md`. 보고서 `docs/reviews/T02_CODEX_UI_REVIEW.md` 권장.
- 상태: **완료** — [T02_CODEX_FRONTEND_REVIEW](../reviews/T02_CODEX_FRONTEND_REVIEW.md)(Git 보고서 참조 `refs/reviews/t02-ui-rv`), 종료 승인 보류 권고, MEDIUM 5·LOW 1(UI-RV-01~06), CRITICAL·HIGH 없음. 사용자가 전부 수정 승인 → §12 T02-UI-FIX.

### 11.6 사용자 실제 테스트 절차
[T02_USER_VERIFICATION.md](../guides/T02_USER_VERIFICATION.md): 기존 `ai_studio` 폴더에서 `git pull --ff-only` → `bootRun`(새 migration 없음, V5 `up to date`) → `npm.cmd ci`(새 UI 의존성) → `npm.cmd run dev`. 기존 개발 DB·관리자·IFNOVA·정책·환율 보존, **지출 차단 유지**, 읽기 전용 SELECT 점검(스키마 버전·데이터·지출 상태·링크 오류 미리보기), 화면별 확인표. "지금 검사"는 `budget_control` 정합성 상태와 감사 로그를 갱신함을 안내. 누락 연결 자동 복구·원장 수정 없음.

### 11.7 미검증 항목과 잔여 위험
- 미검증: 통합 UI의 사용자 실제 화면 검증, 실제 백엔드 대상 브라우저 E2E, Codex UI 독립 리뷰, 로컬 gitleaks(CI로 대체), 운영 프록시·부하 환경 로그인 제한(CR-01), 운영 DB(binlog ON 트리거·권한).
- 잔여 위험(조건부 수용): CR-01 운영 배포 전 해결, CR-02 실제 유료 API 연동 전 복구 절차 마련(ADR-065). P-T02-03(새 API가 필요한 UI 개선) 보류.

### 11.8 T03 필수 설계 제약
[T03_PLAN.md](../plans/T03_PLAN.md)에 9개 선행 조건 기록(콘텐츠·LIFETIME 링크 검사 확장, 상위 트랜잭션 내 재시도 경계 금지, 작업 큐–예산 멱등 일관성·복구, `reserve:`·`reserve-v2:` 접두사 예약, 판정 재계획 503 재시도, 정합성 검사 스캔·잠금 시간, 프록시·클라이언트 IP, 비용 발생 전 정합성 복구 절차, 다중 인스턴스 시계·분산 로그인 제한). `T02_BUDGET_LEDGER.md`·`T02_AUTH.md`에서 참조. T03 제품 코드는 시작하지 않았다.

### 11.9 PR #2와 임시 worktree 정리 계획(최종 승인 후, 별도 승인 범위)
1. 사용자 최종 승인 후 PR #2 상태 확인(통합으로 병합됨 표시 여부) → 필요 시 사용자 결정으로 닫기. 브랜치 `fix/t02-backend` 삭제는 사용자 승인 후.
2. Codex worktree `../ai-studio-t02-fix` 제거는 Codex 작업 종료 확인 + 사용자 승인 후(`git worktree remove`, 강제 삭제 금지).
3. Claude 검토용 임시 worktree(`../ai-studio-review-6a32c64`)는 이미 제거·prune 완료. 임시 MariaDB(scratch, 3307)는 종료.
4. PR #1의 main 병합은 T02 최종 승인 이후 별도 지시로만.

## 12. T02-UI-FIX — Codex UI 리뷰 결함·사용자 확인 항목 수정 (2026-11-25, Claude Code)
상세: [T02_UI_FIX_REPORT.md](T02_UI_FIX_REPORT.md).

### 12.1 이전 CI 재실행 기록
- `6f4589f`(문서 커밋) CI run **45558365457**: 1차 시도 Backend Linux가 Maven Central HTTP 429(외부 저장소 요청 제한)로 실패 → 실패 job만 재실행(2차 시도) **전 7개 job 성공**. 코드 결함이 아닌 외부 일시 오류.

### 12.2 사용자 실제 화면 검증(사용자 보고)
통합 UI 주요 화면 확인. 기존 관리자 계정·IFNOVA 채널·예산 정책 6개·테스트 환율·지출 차단 상태 보존, 정합성 검사 실행 결과 정상, 예약·원장 비어 있음(정상), 서버 상태 정상, 라이트·다크·반응형·로그아웃 등 사용자 확인 결과대로 기록. 실제 유료 예약·정산 미실행. 이 검증에서 발견한 표시 개선 사항이 UI-F01~F13이다.

### 12.3 결과 요약
- UI-RV-01~06 수정, UI-F01·F02·F03·F04·F05·F07·F11·F12·F13 표시 문구 반영(enum·API 불변), A02(정확히 100% = 한도 소진)·A03(HydrateFallback) 해결, A01은 응답 유실 확인 안내로 완화하고 근본 해결을 P-T02-05로 제안(실제 비용 발생 전 해결 또는 수용 필요).
- 검증: vitest 60/60, E2E 10/10(Mock API, 3회 연속), lint·typecheck·build, OpenAPI·생성 타입, 백엔드 단위 133/133·통합 97/97(격리 MariaDB 3307)·bootJar. 커밋 `646179a` CI run 45567226915 **7/7 success**.
- 보류: UI-F06·F08·F09·F10. API·DB·Flyway 변경 없음.


## 13. T02-UI-FIX-EXT — 세션 경계 및 정산 안전성 (2026-11-25, Codex)

- 표적 교차 리뷰에서 발견한 RVX-01~03을 사용자 승인 범위에서 수정했다. 제품 커밋은 `53b305b`이고 대상은 Draft PR #1이다.
- 이전 세션의 지연 403·401·200이 새 세션의 요청 재전송·만료·캐시 갱신을 유발하지 않도록 보호한다. 정산 보조 조회는 예약 상세에서 분리하고, 처리 미확정 요청은 열린 화면에서 동일한 키와 본문으로 수동 재시도한다.
- [수정 보고서](T02_UI_EXT_FIX_REPORT.md)에 재현, 자체 회귀 검증, CI를 기록했다. T02는 IMPLEMENTED이며 최신 HEAD 독립 검토, 사용자 재확인 및 종료 승인이 남아 있다.
- P-T02-05는 전체 새로고침·화면 이탈·다른 탭 및 기기의 수동 재입력 위험을 T02 종료 판정에 한해 조건부 수용한다. 서버 중심 근본 해결은 실제 비용 발생 및 유료 API 활성화의 필수 선행 조건이다. [T03 설계 제약](../plans/T03_PLAN.md)

## 14. T02-FINAL-PATCH — 보호 mutation 콜백 세대 경계 (2026-11-25)

- 최종 독립 검토에서 RVX-01·03과 RVX-02 지연 HTTP 응답 차단을 통과로 판정했다. 승인된 후속 범위는 RVX-02-CB이며 제품 커밋은 `55ea852`이다. [최종 리뷰](../reviews/T02_FINAL_REVIEW.md)
- 새 세대가 시작되면 이전 콜백에서 진행 중인 캐시 조회와 알림을 정리하고, 기다리던 콜백이 화면을 바꾸기 전에 세대를 다시 확인한다. [보강 및 검증 기록](T02_FINAL_PATCH_REPORT.md)
- 사용자 직접 검증 보고는 전체 화면 14/14, 최종 변경 화면 6/6 통과다. 정상 UI 흐름이 유지되어 기존 결과를 승계한다. T02는 IMPLEMENTED이며 변경 콜백 경계의 별도 확인 및 최종 종료 승인 대기다.

## 15. T02-FINAL-CLOSE — 종료 승인 및 main 반영 (2026-11-25)

T02는 2026-11-25 최종 승인으로 **DONE**이다. 제품 최종 커밋은 `55ea852`이고, [최종 독립 검토](../reviews/T02_FINAL_PATCH_REVIEW.md)는 RVX-02-CB PASS, 표적 회귀 3/3, 신규 종료 차단 결함 없음, 종료 권고 APPROVE를 기록했다. [PR #1](https://github.com/Hjinsu/ai-studio/pull/1)에서 최종 문서 커밋, 정확한 병합 커밋과 검사 이력을 확인할 수 있다.

| 검증 | 결과 및 근거 |
|---|---|
| 백엔드 단위 | 133건, 기존 T02 통합 검증 §11~12, `./gradlew :backend:test` |
| 백엔드 통합 | 97건, 기존 T02 통합 검증 §11~12, 격리 임시 MariaDB 3307에서 `./gradlew :backend:integrationTest`. CI는 MariaDB Testcontainers 사용 |
| 프론트엔드 단위 | 73/73, 최종 보강 및 별도 검토의 Vitest 결과. 기존 70건 및 신규 RVX-02-CB 3건 |
| 브라우저 E2E | Edge Mock API 10/10, 최종 보강 결과. 실제 백엔드 연결 브라우저 E2E 미실행 |
| 사용자 확인 | 전체 화면 14/14, 최종 변경 화면 재확인 6/6 |
| CI 기준 | 문서 정리 전 [45578405327](https://github.com/Hjinsu/ai-studio/actions/runs/45578405327), `49334bc`, 7/7. [최종 PR 검사](https://github.com/Hjinsu/ai-studio/pull/1/checks) 및 [main CI](https://github.com/Hjinsu/ai-studio/actions?query=branch%3Amain) |

종료 작업은 문서와 Git 처리로 한정됐다. 제품 코드와 사용자 개발 DB를 변경하지 않았으며 지출 차단을 유지한다. CR-01·CR-02, P-T02-03·P-T02-05, UI-F06·F08·F09·F10 보류 및 [T03 선행 설계 제약](../plans/T03_PLAN.md)을 승계한다. 서버 중심 영속 정산 멱등성과 결과 복구는 실제 비용 발생 및 유료 API 활성화 전에 해결해야 한다. T03 미착수.

# T02 구현 보고서 (설계 검토용)
작성 2026-11-25 · 작성 Claude Code · 브랜치 `wip/t02-implementation-checkpoint-2026-11-25` · 상태: **IMPLEMENTED — CI 전 job 통과(run 45526843601), 최종 설계 승인 대기(DONE 아님)** · T03 미착수

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

# T02 상세 구현 계획 (설계 기준선)
작성 2026-11-25(4차: T2-Q1~Q14, F-01~F-09 확정 반영) · 상태: **구현 승인(2026-11-25)** · DONE 조건: 구현+테스트+CI 전체 통과+설계 검토

## 0. 기준 문서
| 영역 | 문서 | ADR |
|---|---|---|
| 데이터 모델·권한 | [T02_DATA_MODEL.md](../design/T02_DATA_MODEL.md), DDL [T02_DDL_DRAFT.sql](../design/T02_DDL_DRAFT.sql) → V3~V5, [prod-app-grants.sql](../../infra/mariadb/prod-app-grants.sql) | 026, 029, 030, 034, 039, 053, 055, 058, 061 |
| 예약·정산·원장·시간대·환율 | [T02_BUDGET_LEDGER.md](../design/T02_BUDGET_LEDGER.md) | 027, 028, 038, 042, 043, 046, 048, 049, 054, 056, 057, 061 |
| 인증·CLI | [T02_AUTH.md](../design/T02_AUTH.md) | 031, 036, 037, 044, 045, 051, 058, 060 |
| API 계약 | [openapi.yaml](../api/openapi.yaml) | 032, 033, 040, 041, 052 |
| UI | 2절 7단계 | 035, 047 |
오류 코드 규칙: 구체적 비즈니스 오류는 전용 코드 — 예산 초과 BUDGET_EXCEEDED, 지출 차단 POLICY_VIOLATION, 정책·환율·채널 상태·정합성 미충족 PREREQUISITE_NOT_MET, 허용되지 않은 상태 INVALID_STATE_TRANSITION, 중복 DUPLICATE_RESOURCE, 멱등 키 내용 불일치·version/baseRevision 불일치 RESOURCE_CONFLICT, 인식 비용 음수 POLICY_VIOLATION.

## 1. 범위
포함: 관리자 CLI(생성·재설정), 세션 인증·CSRF·rate limit, 채널·설정 revision, 예산 제어(지출 차단·정합성 상태), 환율, SYSTEM/CHANNEL 정책·revision, CONTENT_TYPE 기본 한도 설정, bucket·기간 계산, 예약·정산 내부 서비스, 관리자 확정·조정, 사용량, 원장, 정합성 검사, 대시보드, 관리자 UI, OpenAPI 계약·생성 타입.
제외: 콘텐츠 단위 예약·FK(T03), 실제 provider 호출(T07 승인 전 금지), 운영 JAR 정적 패키징(배포 단계), 신뢰 프록시(배포 단계), 감사 로그 실제 삭제(별도 관리 정책).

## 2. 구현 순서
1. T01 DONE 기록, 결정 반영, 설계 기준선 커밋.
2. Migration V3~V5 + `MigrationConventionTest`(해시 등록, utf8mb4_bin 컬럼 예외 목록).
3. 공통: Clock, 트랜잭션 재시도(READ COMMITTED, 1213/1205 최대 3회, 백오프+jitter), 페이지 응답, 감사 서비스.
4. 인증: SecurityFilterChain, JSON 로그인(세션 전략·컨텍스트 저장·CSRF 재발급), 세션 쿠키, credential_version 검증 필터, rate limiter(Caffeine), 401/403 공통 형식, 관리자 CLI(create, reset-password).
5. 채널: CRUD·pause/resume/archive(control S → channel X), 설정 revision(channel X + baseRevisionNo).
6. 예산: budget_control(지출 차단, 정합성 상태), 환율, 정책·revision(시간대 효력 시각), 기간 계산, 예약(Fail Closed 검사, 잠금 순서), 정산(PROVISIONAL/CONFIRM/RELEASE/불명/ADJUSTMENT/REFUND), 사용량, 원장 조회, 정합성 검사, 대시보드.
7. 관리자 UI: 로그인/로그아웃, 대시보드, 채널 목록·등록·수정·상태 변경, 설정·이력, 예산 정책·이력, 지출 차단, 사용량, 원장, 예약(확정·조정), 환율. 생성 타입 + React Query, CSRF 래퍼, 금액 문자열 그대로 표시.
8. `frontend/tools/openapi-typescript`(TS 5.9.3, openapi-typescript 7.13.0, Redocly 2.83.0, 자체 lockfile) → `src/api/generated/schema.d.ts`. CI: 재생성 diff, Redocly lint.
9. 계약 테스트: 컨트롤러 테스트에 OpenAPI 요청·응답 검증(openapi-request-validator-mockmvc 3.0.0, 테스트 classpath).
10. 로컬·CI 테스트, T02_REPORT.md, STATE.md.

## 3. 의존성
| 용도 | 좌표/버전 | 범위 |
|---|---|---|
| 인증 | spring-boot-starter-security, -security-test (Boot 4.1.3 BOM, Security 7.1.3) | main / test |
| rate limit | com.github.ben-manes.caffeine:caffeine (BOM 3.2.4) | main |
| 계약 검증 | com.atlassian.oai:openapi-request-validator-mockmvc:3.0.0 | test 전용 |
| 타입 생성·lint | openapi-typescript 7.13.0, typescript 5.9.3, @redocly/cli 2.83.0 | `frontend/tools/openapi-typescript` 격리 |
| 라우팅(UI) | react-router (구현 시 최신 안정 버전 확인·고정) | frontend |

## 4. 테스트 (기존 계획 + 지시서 5절 전체)
| 검증 | 방법 |
|---|---|
| 동시 예약 한도 초과 방지, bucket 동시 생성, 정책 변경/신규 정책 생성/채널 일시정지·보관/지출 차단 vs 예약 동시 실행 | DB IT(다중 스레드) + 정합성 검사 0건 |
| 멱등 키 다른 내용 409, **멱등 키 대소문자 구분** | 단위 + DB IT |
| 예약 도중 실패 전체 rollback | DB IT(강제 예외) |
| **정책 누락·비활성·0원·정합성 ERROR 시 Fail Closed** | 단위 + DB IT |
| PROVISIONAL 없는 CONFIRM, **잠정 감소 후 예약 복원, 증가·감소 반복**, 다회 ADJUSTMENT·REFUND, **KRW 전용 조정 후 원본 금액 유지**, 예상 초과, 지연 정산, RELEASED→SETTLED | 단위(계산식, 문서 예시 값) + DB IT |
| 외부 과금 불명 자동 해제 없음 | 단위 |
| 환율 누락·만료(이전 환율 fallback 없음), effectiveFrom 범위 | 단위 + DB IT |
| 기간·시간대 전환, DST, **시간대 변경 전후 bucket 미생성 기간**, **정책 revision–bucket 연결 정합성** | 단위(PeriodCalculator) + DB IT |
| **SYSTEM/CHANNEL 비용 중복 합산 방지**(대시보드·사용량) | DB IT |
| 원장 UPDATE/DELETE 차단, **운영 앱 계정 원장 UPDATE/DELETE·DDL 차단, admin_bootstrap 최소 권한** | DB IT(트리거 + `prod-app-grants.sql` 적용 계정) |
| 최초 관리자 동시 생성, 비밀번호 규칙(12자, 72바이트), **재설정 후 기존 세션 무효화(credential_version)** | 단위 + DB IT |
| 로그인 rate limit, 계정 존재 비노출, 세션 만료·ID 교체, CSRF(로그인 포함, 로그인 후 재발급), 쿠키 profile별 설정 | Security 테스트(MockMvc + 실제 Tomcat) |
| **채널 설정 revision 동시 생성 충돌** | DB IT |
| **신규 DB 및 V2→최신 migration** | DB IT(Testcontainers/임시 인스턴스) |
| OpenAPI 계약·생성 타입 일치 | 계약 matcher, CI 재생성 diff |
| 운영 DB 앱·migration 계정 연결 보호 | 기존 DatabaseGuardConfigurationTest |

## 5. 확정된 결정 (T2-Q → ADR)
Q1→ADR-039, Q2→040, Q3→041, Q4→042, Q5→043, Q6→044, Q7→045, Q8→046, Q9→047, Q10→048, Q11→049, Q12→050, Q13→051, Q14→052. 추가 수정 F-01~F-09 → ADR-053~061. 미결 없음(구현 중 새 설계 변경은 영향 범위와 함께 보고).

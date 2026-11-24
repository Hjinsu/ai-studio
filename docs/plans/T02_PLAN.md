# T02 상세 구현 계획 (초안, 설계 검토용)
작성 2026-11-25 · 상태: **계획만, 미착수** · 선행 조건: T01 DONE(CI 전 job 통과)

## 1. 범위
STATE.md T02 "인증, 채널, 설정, 비용 원장" + ADR-017, 019, 021, 023 적용분. 콘텐츠/작업/게시 도메인은 포함하지 않는다(T03 이후).

| 영역 | 포함 | 제외(이후 단계) |
|---|---|---|
| 인증 | 관리자 세션 로그인/로그아웃, CSRF, 현재 사용자, 로그인 rate limit, 공통 오류 형식의 401/403 | 다중 사용자 권한 체계 |
| 채널 | 채널 CRUD, 채널 설정(버전 관리, optimistic lock), 예산 시간대 | OAuth 연동(T08) |
| 예산/원장 | 예산 정책 CRUD, bucket, 예약/해제/정산 서비스, 원장 조회, 사용량 조회, 환율 | 실제 provider 비용 연동(T04, T07) |
| 계약 | `docs/api/openapi.yaml`(3.1), 프론트 타입 생성, 계약 테스트 | — |

## 2. 작업 순서
1. **OpenAPI 계약 초안** `docs/api/openapi.yaml`: 공통 ErrorResponse(19개 코드 enum), 인증·채널·예산·원장 경로. 설계 검토 후 구현 착수.
2. **의존성 확인·고정**: spring-boot-starter-security(Boot 4.1.3 BOM), openapi-typescript(npm 고정), 계약 검증 라이브러리(후보 조사 후 결정, 4절 D-07).
3. **Migration V3~**: 아래 테이블. DDL 규칙(ADR-014: CHARSET/COLLATE 생략 또는 unicode_ci 명시), MigrationConventionTest에 해시 등록.
4. **보안 모듈** `com.aistudio.security`: SecurityFilterChain(세션, CSRF, `/actuator/health`만 permitAll), AuthenticationEntryPoint/AccessDeniedHandler → 공통 오류 형식(UNAUTHORIZED/FORBIDDEN), BCrypt, 세션 유휴 30분, 쿠키 HttpOnly·SameSite=Lax·Secure(local=false, prod=true), 로그인 rate limit(단일 인스턴스 메모리).
5. **채널 모듈** `com.aistudio.channel`: CRUD, 설정 버전(unique(channel_id,version)), optimistic lock(409 RESOURCE_CONFLICT).
6. **예산 모듈** `com.aistudio.budget`: 정책, bucket 계산(시간대별 기간 경계 → UTC 저장), 예약/정산 서비스, 원장 조회.
7. **프론트**: 생성 타입 연결, CSRF 처리 fetch 래퍼(범위는 D-10).
8. **테스트·문서·STATE 갱신**.

## 3. 테이블 초안 (V3 이후, 컬럼은 OpenAPI와 함께 확정)
| 테이블 | 핵심 컬럼 / 제약 |
|---|---|
| admin_user | id, login(unique), password_hash(BCrypt), role, enabled, created_at, updated_at |
| audit_event | id, actor_user_id, action, entity_type, entity_id, details_json(비핵심), created_at |
| channel | id, name, language, timezone, budget_timezone, status, version, created_at, updated_at |
| channel_config | id, channel_id, version, config_json; unique(channel_id, version) |
| budget_policy | id, scope(SYSTEM/CHANNEL/CONTENT), scope_id(nullable for SYSTEM), period_type, limit_amount DECIMAL(19,6), currency, timezone, effective_from_utc, enabled |
| budget_bucket | id, policy_id, period_start_utc, period_end_utc, timezone, reserved_krw, spent_krw, version; unique(policy_id, period_start_utc) |
| budget_reservation | id, request_key(unique), status(ACTIVE/RELEASED/SETTLED), estimate_krw, created_at, closed_at + 예약 대상 bucket 연결(budget_reservation_bucket) |
| cost_log | id, event_key(unique), request_key, entry_type, original_amount, original_currency, exchange_rate_id, amount_krw, price_version, provisional, occurred_at, created_at. UPDATE/DELETE 금지(append-only) |
| exchange_rate | id, base_currency, quote_currency(KRW), rate DECIMAL, effective_at_utc, source; unique(base, quote, effective_at_utc) |

## 4. 원자적 예산 예약 흐름 (ADR-017/019)
1. 요청(request_key, 원본 통화/예상 금액) → 적용 정책 조회(SYSTEM + CHANNEL + CONTENT) → 각 정책의 현재 기간 bucket 결정(정책 시간대 기준 경계, 없으면 생성).
2. 한 트랜잭션: bucket을 id 순으로 `SELECT ... FOR UPDATE` → 모든 bucket에서 `spent + reserved + estimate <= limit` 확인 → 실패 시 BUDGET_EXCEEDED(422), 아무것도 기록하지 않음 → 성공 시 reservation 생성, cost_log RESERVE(event_key) 추가, bucket.reserved 증가.
3. 정산: PROVISIONAL(잠정 비용 기록, reserved→spent 이동), CONFIRM(잠정-확정 차액), ADJUSTMENT/REFUND(spent 증감), RELEASE(미사용 예약 해제). 같은 event_key 재수신은 멱등 무시. 예상액 초과 정산은 기록하고 경고, 이후 예약 차단.
4. 동시성 테스트: 여러 스레드가 같은 bucket에 예약 → 한도 초과 0건(Testcontainers).

## 5. 추가 결정 필요 사항
| ID | 질문 | 제안 |
|---|---|---|
| D-01 | budget_reservation과 원장 RESERVE의 기준 데이터 | reservation=현재 상태, cost_log=불변 이력, bucket reserved/spent=잠금용 집계. 불일치 검증 쿼리 제공 |
| D-02 | 환율 출처·시점 | 외부 API 없이 관리자 수동 등록. 이벤트 발생 시점 직전 유효 환율 적용. 환율 없으면 BUDGET 예약 거부(PREREQUISITE_NOT_MET) |
| D-03 | PROVISIONAL 없이 CONFIRM이 오는 경우 | CONFIRM이 전체 확정 금액을 기록(차액 기준 0) |
| D-04 | CONTENT 예산의 기간 | period_type=LIFETIME(콘텐츠 전체 기간). content 테이블은 T03이므로 T02는 scope_id FK 없이 저장하고 T03에서 FK 추가 migration |
| D-05 | 금액 반올림 | DECIMAL(19,6) 저장, KRW 비교는 소수 6자리 그대로, 표시만 원 단위 반올림(HALF_UP) |
| D-06 | 초기 관리자 생성(하드코딩 금지) | 1회성 환경변수 `AI_STUDIO_ADMIN_BOOTSTRAP_LOGIN/PASSWORD`: admin_user가 0명일 때만 생성, 이후 무시·경고. 대안: CLI 명령 |
| D-07 | OpenAPI 계약 테스트 방식 | 계약 우선(yaml 수동 관리) + MockMvc 응답을 yaml로 검증하는 라이브러리(후보 조사 후 고정). springdoc 자동 생성은 비교용으로만 검토 |
| D-08 | 생성된 TS 타입 커밋 여부 | 커밋하고 CI에서 재생성 diff 검사 |
| D-09 | 채널 삭제 | 물리 삭제 금지, status=ARCHIVED |
| D-10 | T02 프론트 범위 | 로그인/로그아웃 화면과 CSRF 처리만. 채널·예산 화면은 T06 |
| D-11 | 로그인 rate limit 수치 | 동일 login+IP 5회/15분 실패 시 15분 차단, 429 RATE_LIMIT_EXCEEDED |
| D-12 | 역할 | 초기 ADMIN 단일 역할 |
| D-13 | 예산 시간대 변경 반영 | 정책/채널 시간대 변경 시 다음 기간 bucket부터 적용, 현재 bucket 경계 유지(ADR-017) — 변경 이력은 audit_event |

## 6. T02 테스트 계획
- 단위: 기간 경계 계산(Asia/Seoul, DST 있는 시간대 예: America/New_York 전환일), 정산 유형별 집계, event_key 멱등, 반올림, 오류 코드 매핑.
- 통합(Testcontainers): V1→V3+ 신규/업그레이드, 동시 예산 예약, 원장 append-only(UPDATE/DELETE 차단 방식은 D-01과 함께 결정), 세션 만료·CSRF·401/403 공통 형식.
- 계약: OpenAPI 대비 응답 검증, 프론트 타입 생성 diff.
- 보안: 비밀번호·세션 ID 로그 비노출, Secure 쿠키 profile별 확인.

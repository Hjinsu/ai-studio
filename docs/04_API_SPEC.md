# REST API v1
Base /api/v1, JSON UTF-8, 인증 세션/CSRF(ADR-023), UTC ISO8601, pagination page/size(max100). 계약은 OpenAPI 3.1 `docs/api/openapi.yaml`(T02 작성), 프론트 타입은 openapi-typescript 우선 후보로 생성(ADR-021).
오류 응답 `{code,message,traceId,timestamp,details}`: timestamp UTC ISO8601, details는 항상 배열 `[{field,reason}]`(없으면 `[]`), 내부 예외 메시지 비노출, 응답 헤더 `X-Trace-Id`와 body traceId 동일(ADR-011, ADR-012). MVC 밖(서블릿/Security 필터) 오류도 같은 형식(`/error`, ADR-024).

고정 오류 코드(ADR-011, ADR-012):
| 코드 | HTTP | 용도 |
|---|---|---|
| INVALID_REQUEST | 400 | 형식 오류, 파싱 불가, 타입 불일치 |
| VALIDATION_FAILED | 400 | 필드 검증 실패(details에 field) |
| UNAUTHORIZED | 401 | 인증 필요 |
| FORBIDDEN | 403 | 권한 없음, CSRF 실패 |
| RESOURCE_NOT_FOUND | 404 | 대상 없음 |
| METHOD_NOT_ALLOWED | 405 | 메서드 불허 |
| NOT_ACCEPTABLE | 406 | 응답 형식 협상 실패(본문은 JSON) |
| RESOURCE_CONFLICT | 409 | 일반 충돌, optimistic lock, Idempotency-Key payload 불일치 |
| INVALID_STATE_TRANSITION | 409 | 허용되지 않은 상태 전이 |
| PREREQUISITE_NOT_MET | 409 | 선행 조건 미충족(예: stale 승인) |
| DUPLICATE_RESOURCE | 409 | 유일성 위반 |
| PAYLOAD_TOO_LARGE | 413 | 요청 크기 초과 |
| UNSUPPORTED_MEDIA_TYPE | 415 | Content-Type 불가 |
| POLICY_VIOLATION | 422 | 정책 위반 |
| BUDGET_EXCEEDED | 422 | 예산 초과 차단 |
| RATE_LIMIT_EXCEEDED | 429 | 요청 제한 |
| CLIENT_ERROR | 실제 4xx | 위에 없는 4xx(예: 410, 418) — 실제 상태 유지 |
| INTERNAL_SERVER_ERROR | 500 또는 실제 5xx | 예기치 못한 오류, 미분류 5xx(예: 502, 504)는 실제 상태 유지 |
| SERVICE_UNAVAILABLE | 503 | 외부/내부 일시 장애 |
상태만 알 수 있는 오류(프레임워크·필터)는 상태별 기본 코드(409→RESOURCE_CONFLICT, 422→POLICY_VIOLATION)로 변환한다. 세분 코드(BUDGET_EXCEEDED 등)는 애플리케이션이 명시적으로 발생시킨다.
Health: `GET /actuator/health` 인증 없음, 응답은 status만(ADR-008). 다른 Actuator 엔드포인트 비노출. Actuator 표준 응답은 공통 오류 형식과 별도 계약(ADR-024).

T02 추가 API(ADR-021, 상세는 docs/plans/T02_PLAN.md, OpenAPI로 확정): 로그인, 로그아웃, CSRF 토큰, 현재 사용자 조회, 채널 CRUD, 채널 설정, 예산 정책 CRUD, 예산 사용량, 비용 원장 조회.
| Method | Path | 요청/결과 |
|---|---|---|
| POST | /channels | name,language,timezone → id |
| GET/PATCH | /channels/{id} | config, version (optimistic lock) |
| POST | /contents | channelId,topic,title,format → contentId |
| GET | /contents/{id} | status,revision,scenes,jobs,cost |
| POST | /contents/{id}/produce | revisionId → 202 jobId |
| POST | /contents/{id}/revisions | baseRevisionId,changes → new revision |
| POST | /jobs/{id}/retry | reason → 202 |
| POST | /contents/{id}/approve | revisionId,manifestHash,decision → approvalId |
| POST | /contents/{id}/publish | approvalId,scheduledAt → 202 |
| GET | /assets/{id}/preview | authorized range stream |
| GET | /channels/{id}/budget | reserved,spent,remaining,currency |
| GET | /channels/{id}/analytics | dates,metrics |
| GET | /youtube/oauth/start | channelId → auth URL |
| GET | /youtube/oauth/callback | state,code → bind channel |
POST job/publish에 Idempotency-Key; 동일 키 다른 payload 409. 승인 stale 409. 외부 실행은 async. API schema OpenAPI로 구현 시 생성 및 contract test.

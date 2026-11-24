# REST API v1
Base /api/v1, JSON UTF-8, 인증 세션/CSRF, UTC ISO8601, pagination page/size(max100). 오류 {code,message,traceId,details}. 400 입력,401 인증,403 권한,404 없음,409 충돌,422 정책/검증,429 제한,503 외부 장애.
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

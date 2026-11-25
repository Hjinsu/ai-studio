# OpenAPI 계약 초안
기존 /api/v1, ADMIN 세션+CSRF, 오류 {code,message,traceId,timestamp,details}, ID/금액/환율 문자열, UTC timestamp, 페이지 기본20 최대100, 평탄 스키마 및 additionalProperties=false, 생성 타입 직접 수정 금지. 기존 openapi.yaml과 path/operationId/status를 대조한 뒤 승인. 예시 필드명은 제안이며 실제 schema가 아님.

| Method/path | 입력 및 응답 |
|---|---|
| GET /contents | channelId,contentType,status,q,page,size; page of ContentSummary |
| POST /contents | requestKey,channelId,contentType,title,topic,channelConfigId,duration; 201 ContentDetail |
| GET /contents/{contentId} | currentRevision,activeRun,budgetSummary,version |
| POST /contents/{contentId}/archive | expectedVersion; 200 detail |
| GET /contents/{contentId}/revisions | paginated immutable revisions |
| POST /contents/{contentId}/revisions | requestKey,baseRevisionNo,expectedVersion,settings,changeReason; 201 revision |
| POST /contents/{contentId}/runs | requestKey,revisionId,pipelineVersion; 201 RunDetail |
| GET /contents/{contentId}/runs | paginated runs |
| GET /runs/{runId} | status,job counts,progress,revisionId |
| POST /runs/{runId}/cancel | requestKey,reason; 200/202 status |
| GET /jobs | runId,channelId,status,type,page,size; page |
| GET /jobs/{jobId} | status,attempts,lease expiry,error,operation summary |
| GET /jobs/{jobId}/attempts | page |
| GET /jobs/{jobId}/events | page |
| POST /jobs/{jobId}/retry | requestKey,reason; safe resume only |
| POST /jobs/{jobId}/cancel | requestKey,reason |
| GET /operations | status,jobId,page,size |
| GET /operations/{operationId} | safe result/recovery status |
| POST /operations/{operationId}/reconcile | requestKey,reason; no arbitrary forced status |
| POST /budget-reservations/{reservationId}/settlement-operations | creationRequestKey, existing settlement payload; 201 operation |
| GET /budget-reservations/{reservationId}/settlement-operations | page incl pending/unknown |
| GET /settlement-operations/{operationId} | state,result event reference |
| POST /settlement-operations/{operationId}/execute | operation id only, no replacement payload; 200/202 |

모든 경로에 /api/v1 접두사. id 양수 signed BIGINT 문자열, 날짜 UTC, enum 명시. 400 입력, 401 인증, 403 CSRF/권한, 404 미존재, 409 상태/버전/key 충돌/미충족 전제, 422 예산·정책, 429 제한, 503 일시적 서비스 오류. 기존 오류 코드를 우선 사용. 201 최초 생성과 동일 요청 replay 응답 규칙 명시(동일 리소스, 새 side effect 없음). 목록 정렬 서버 기준 id tie-break. 직접 status PATCH, 원장 수정/삭제, 임의 외부 요청 URL 금지. A에서 요청/응답 예시와 required/nullability/length 제한, 기존 resolve/adjustment 전환 방안 및 OpenAPI 테스트 확정.

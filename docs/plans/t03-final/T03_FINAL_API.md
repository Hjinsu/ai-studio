# T03 API 계약

제품 기준 docs/api/openapi.yaml의 /api/v1, ADMIN 세션·CSRF, 19개 공통 오류, 기존 GET·인증·예산 계약을 보존한다. ID는 양수 signed BIGINT 문자열. operation/dispatch UUID는 별도 스키마다. 금액/환율은 기존 문자열 패턴, 시각 UTC ISO 8601, page 기본값 0, size 기본 20/최대 100. 경로별 평탄 스키마와 오류는 [검토용 변경 초안](T03_OPENAPI_DRAFT.yaml)에 기록한다.

Topic/Content/Revision/Run/Job/Queue 관리는 초안의 목록·상세 및 작성 경로를 제공한다. Pipeline은 서버 선택, Job 단위 취소 미제공, /jobs/{jobId}/retry는 ACTION_REQUIRED의 명시 재개다. Run 취소는 202 또는 처리 완료 200. 활성 Run 보유 채널 archive는 409 INVALID_STATE_TRANSITION. JobDetail.operation, RunDetail.unresolvedOperations, ContentDetail.budgetSummary, dashboard production 필드를 추가한다. LIFETIME 날짜 없음, 정책별 예산만 표시하며 중복 합산 금지.

| 영속 정산 경로 | 계약 |
|---|---|
| POST /budget-reservations/{reservationId}/settlement-operations | Idempotency-Key, 기대 예약 version, 직전 receipt UUID 또는 null, 명시 확인, action·금액·사유·근거를 제출. 201 신규, 200 동일 키 동일 본문 재생. payload 변경 및 다른 미완결 의도 409. guard와 operation 원자 저장 |
| GET /budget-reservations/{reservationId}/settlement-operations | 열림 및 최근 확정 이력. 새로고침 뒤 우선 조회 |
| GET /settlement-operations/{operationId} | PENDING/COMMITTED/REJECTED/CANCELLED, 서버 영속 receipt. 사용자가 확인할 immutable payload 요약 |
| POST /settlement-operations/{operationId}/execute | ID로만 실행. 성공 200, 불명 커밋 시 조회 후 같은 ID로만 재시도. 확정 이후 영수증 재생 |
| POST /settlement-operations/{operationId}/cancel | 미실행 PENDING만 허용. 실행 경합은 reservation 및 guard 잠금으로 직렬화 |
| POST /operations/{operationId}/attestations | Idempotency-Key, 기대 버전, 증거·사유 및 독립 청구/산출물 판정. actor/시간은 서버 확정. 동일 요청 재생 |
| GET /operations/{operationId} | 복구/증거 요약. 원본 민감 payload 제외 |

새 요청의 명시 확인은 버전과 직전 receipt를 본문에 포함하며 일반 정산과 attestation 조직을 분리한다. 기존 resolve/adjustments는 전환 기간 호출 시 409 PREREQUISITE_NOT_MET과 신규 경로 안내, 쓰기 0. OpenAPI와 프론트 원자 전환을 계획한다. 인증 401, CSRF 403, 잘못된 입력 400, 소유 자원 미존재 404, 상태·멱등/전제 409, 정책·한도 422, 제한 429, 일시 장애 503. 비용 실패의 내부 이유는 외부 공통 오류 스키마를 바꾸지 않는다.

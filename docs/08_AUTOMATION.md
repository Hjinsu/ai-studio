# 작업 실행 계약
content: DRAFT,PRODUCING,REVIEW_REQUIRED,REVISION_REQUIRED,APPROVED,ARCHIVED,CANCELLED. job: PENDING,RUNNING,SUCCEEDED,RETRY_WAIT,FAILED,BLOCKED,CANCELLED. publish: NOT_SCHEDULED,SCHEDULED,UPLOADING,PROCESSING,UPLOADED,PUBLISHED,FAILED,CANCELLED. 외부 불명 상태는 job BLOCKED + reason EXTERNAL_OUTCOME_UNKNOWN.
claim: 짧은 트랜잭션에서 eligible row 잠금(SKIP LOCKED 지원 버전 검증), lease_token/lease_until 설정. heartbeat 및 token fencing으로 stale worker 완료 거절. 외부 호출 전 request log와 비용 예약. 성공 후 asset 기록, 비용 정산, 후속 job enqueue 원자적 처리. lease 만료 시 외부 상태 조회 가능 여부 우선 확인.
일시 장애는 exponential backoff+jitter, 기본 최대 3회(제안), 429 Retry-After 준수. 인증/입력 오류는 자동 재시도 금지. dependency 모두 성공 시 실행. dedupe_key=revision:stage:scene:inputHash. Quartz timezone 명시, 일정 중복 방지. 취소는 협력적이며 이미 발생한 비용은 보존.

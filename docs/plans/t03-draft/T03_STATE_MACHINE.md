# 상태 전이 (제안)
기존 docs/08의 PRODUCING/PENDING/BLOCKED(reason EXTERNAL_OUTCOME_UNKNOWN)와 새 이름은 충돌 가능. A에서 기존 계약과 매핑/변경 ADR 확정. 게시 상태는 독립이며 T08까지 비활성. 콘텐츠 상태는 현재 revision의 활성 run과 결과를 바탕으로 서버가 산출/갱신한다. 과거 run이 현재 revision 상태를 덮지 못한다.

## Job
| From | Event/guard | To |
|---|---|---|
| BLOCKED | 모든 필수 선행 성공 | READY |
| BLOCKED | 선행 최종 실패/취소/생략 | SKIPPED |
| READY | claim, slot/채널/run 조건 충족 | RUNNING |
| RUNNING | 토큰 유효 + 결과/정산 확정 | SUCCEEDED |
| RUNNING | 안전한 일시 오류, 횟수 남음 | RETRY_WAIT |
| RUNNING | 확정 실패/횟수 소진, 미확정 비용 없음 | FAILED |
| RUNNING | 정책/예산/인증 등 사용자 조치 | ACTION_REQUIRED |
| RUNNING | 결과/정산 불명 | RECOVERY_REQUIRED |
| RUNNING | 취소 요청 | CANCEL_REQUESTED |
| RETRY_WAIT | due, run 활성 | READY |
| ACTION_REQUIRED | 관리자 명시 재개 + 전제 재검증 | READY |
| RECOVERY_REQUIRED | 성공·비용 확인 | SUCCEEDED |
| RECOVERY_REQUIRED | 확정 미실행·비용 해소, 횟수 남음 | READY (취소 의도 있으면 CANCELLED) |
| RECOVERY_REQUIRED | 확정 실패 및 비용 해소 | FAILED |
| CANCEL_REQUESTED | 중단 확인 및 비용 해소 | CANCELLED |
| CANCEL_REQUESTED | 이미 성공/비용 확정 | SUCCEEDED (run 취소 의도 유지) |
| CANCEL_REQUESTED | 결과 불명 | RECOVERY_REQUIRED |
| BLOCKED/READY/RETRY_WAIT/ACTION_REQUIRED | 취소, 미확정 operation 없음 | CANCELLED |

그 외 전이 409. terminal SUCCEEDED/FAILED/CANCELLED/SKIPPED 재개 금지; 최종 실패 후 새 run은 명시 요청. 자동 재시도는 같은 job/operation이며 별개의 유료 실행을 허가하지 않는다. 만료 lease는 안전성 판정 후 위 오류 전이, stale worker 변경 0건. 상태 갱신+attempt+event는 한 TX. 취소 의도는 복구 중에도 영속 보존.

## Run
QUEUED→RUNNING; RUNNING↔ACTION_REQUIRED; QUEUED/RUNNING/ACTION_REQUIRED→CANCEL_REQUESTED; 모든 job 종료 후 SUCCEEDED(전부 성공), FAILED(실패/필수 생략), CANCELLED(취소 의도 및 미확정 비용 없음). 미확정 operation 또는 사용자 조치가 있으면 ACTION_REQUIRED, 취소 의도 있으면 CANCEL_REQUESTED 유지 가능. 성공과 취소 경합은 동일 잠금 안에서 결정. 실패 branch는 descendants SKIPPED, 독립 branch는 이미 시작된 작업 정리 후 종료(새 claim은 중지하는 기본 fail-fast 정책 제안). run의 terminal 변경과 활성 guard 해제는 같은 TX. 집계는 run 행 잠금으로 직렬화.

## Content
DRAFT→QUEUED→PROCESSING→ACTION_REQUIRED/FAILED/CANCELLED; 성공 시 T03 fake 실행은 실제 검수 가능 영상으로 표시하지 않음(실행 완료 표시 별도). T06 이후 REVIEW_REQUIRED/APPROVED, T08 독립 게시 상태. 새 revision은 새 초안으로 전환(활성 run 중 변경 금지 제안), 새 run은 명시 요청, ARCHIVED는 활성 run/미확정 작업 없을 때만. 보관 후 신규 실행/예약 금지, 기존 비용 정산 허용. 채널 PAUSED/ARCHIVED는 새 실행·claim 제한, 비용 정산 허용. 전이 전체는 실제 상태명과 함께 A에서 확정.

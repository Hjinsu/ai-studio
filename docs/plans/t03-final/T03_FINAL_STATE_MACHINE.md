# T03 상태와 P12

Content DRAFT → PRODUCING → DRAFT, DRAFT → ARCHIVED. current_revision_id는 같은 콘텐츠 revision, active_run_id는 같은 콘텐츠의 비종료 Run과 일치한다. 활성 포인터 변경과 Run 생성/종료는 같은 락 및 TX다. 채널 보관은 활성 Run이 있으면 409 INVALID_STATE_TRANSITION. Run 취소만 지원하며 cancel_requested_at과 승인된 actor/reason을 저장한다.

| 현재 | 조건 | 결과 |
|---|---|---|
| WAITING | 모든 선행 성공 | READY |
| WAITING/READY/RETRY_WAIT/ACTION_REQUIRED | 취소/실패 확산, 비용 해소 없음이 확인됨 | CANCELLED/SKIPPED. PRE 예약이 있으면 회수 요청, R10 대기 |
| READY/RETRY_WAIT | due, 채널 ACTIVE, 취소·fail-fast 없음, DB lease 여유 | RUNNING 및 attempt 생성 |
| RUNNING | 유효 fencing과 성공, 효과 작업은 P12 증거·정산까지 확인 | SUCCEEDED 및 후행 평가 |
| RUNNING | 허가 전 안전한 오류, 자동 잔여 | RETRY_WAIT |
| RUNNING | 전제·예산·정책 오류 또는 자동 소진 | ACTION_REQUIRED. 수동까지 소진 시 FAILED |
| RUNNING | 확정 실패 | FAILED, 미시작 후행 중단 |
| RUNNING | 허가 이후 결과·비용 불명, 또는 이 상태에서 lease/deadline 만료 | RECOVERY_REQUIRED |
| RUNNING/PRE | lease/deadline 만료 | 미허가 입증 후 같은 슬롯으로 안전 재시도 또는 취소/실패 |
| ACTION_REQUIRED | 명시 관리자 재개, 2회 상한 및 전제 확인 | READY. 수동 카운터만 증가 |
| RECOVERY_REQUIRED | P12 EXECUTED_RESULT_ADOPTED, artifact 검증과 정산 확정 | SUCCEEDED |
| RECOVERY_REQUIRED | P12 NOT_EXECUTED 이후 dispatch가 존재 | 취소 의도가 있으면 CANCELLED, 그 외 FAILED. 같은 operation 재발급 금지 |
| RECOVERY_REQUIRED | P12 EXECUTED_FAILED 및 비용 확정 | 취소 의도면 CANCELLED, 그 외 FAILED |
| RECOVERY_REQUIRED | 비용 또는 artifact 판단 불명/상충 | 유지, 자동 송신 없음 |

A3의 세부 전이·사유와 fan-in 규칙은 위 가드에 종속된다. J23/J24의 허가 후 재시도/재개 해석은 위 단일 발급 규칙으로 대체한다. 취소 신호는 진행 상태를 덮지 않는다. terminal Job 및 Run은 변경 금지. 취소와 성공 경합은 완료 사실 및 원래 취소 의도를 같은 Run 잠금 아래 반영한다. R10은 모든 Job terminal, 미해결 비용 및 Operation 0, 필수 P12 반영 완료를 한 TX에서 검사하고 Run 종료, active_run_id 해제와 콘텐츠 복귀를 함께 기록한다. 실패·취소·성공 우선순위는 취소 의도, 요구 Job 실패 여부, 전체 성공 여부다.

PRE는 dispatch가 없고 의도 또는 미허가 예약 처리가 남은 상태다. INFLIGHT는 dispatch 존재 및 증거/정산 미해결 상태다. NONE은 효과 없음 또는 의도·예약 해소와 결과 영속 receipt가 확인된 상태다. P12는 receipt당 한 번 적용하고 미적용 receipt를 reconciler가 다시 반영한다. 후속 일반 환불·조정은 terminal Run을 재개하지 않는다.

큐 DB UTC로 lease_expires_at/execution_deadline_at과 current_attempt_no/token을 함께 검사하며 만료 Worker의 상태 쓰기는 거절한다. 잠금 재시도는 자동 3회(최초 포함) 및 관리자 수동 2회와 별개다. 새 Run만 신규 효과를 생성한다.

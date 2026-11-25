# 신규 결정 제안 (미승인)
기존 ADR-001~066 유지. 실제 DECISIONS 최신 ID 확인 후 번호 배정; 임의로 ADR-067부터 덮어쓰지 않음.

| ID | 제안 | 대안/영향 |
|---|---|---|
| D01 | 콘텐츠당 활성 run 1개 | 병렬 run 대비 비용·복구 단순화; DB guard |
| D02 | 채널/유형 불변 | 변경 허용 시 예산 이력 복잡; 새 콘텐츠 |
| D03 | content/revision/run/job/attempt 분리 | 초기 automation_job 명칭과 계약 대조 |
| D04 | 고정 DAG | 순차-only 대비 확장, cycle 검증 |
| D05 | lease60s/heartbeat15s | 설정 가능, fencing 및 DB UTC |
| D06 | worker2, 단일 인스턴스 | 분산 cap 미보장, 운영 확장 별도 |
| D07 | 최초 포함 5회, base5s cap300s jitter | 초기 3회 제안 대체 여부 승인 필요 |
| D08 | ACTION_REQUIRED/RECOVERY_REQUIRED 분리 | 기존 BLOCKED reason 계약 매핑/변경 승인 |
| D09 | 유형별 필수 LIFETIME, content별 bucket | 적용 정책 선택 및 기간 sentinel 확정 필요 |
| D10 | 영속 operation+분리 TX+reconciler | 단일 TX는 RV-09 위반; outbox/상태기반 비교 |
| D11 | P-T02-05를 T03에서 구현, 별도 settlement_operation | T07까지 연기 가능하지만 비용 전 완료 필수; 기존 API 우회 방지 |
| D12 | T02 UI 재사용 및 제작/복구 화면 | 새 디자인 시스템 도입 금지 |

추가 A 확정 항목: 기존 상태명/automation_job 명칭, revision hash canonicalization, 콘텐츠/채널 상태 경합, run terminal 집계/취소 우선순위, DB guard 및 잠금 순서, LIFETIME 정책 전역/채널 매칭 및 과거 링크 기준, settlement operation 상태 및 기존 API 전환, 실제 호출 직전 차단 경계, Quartz JDBC schema, 테스트 성능 허용치. CR-01/02 및 T02 보류 항목 유지. 사용자 승인 전 제안을 기존 결정으로 기록하지 않는다.

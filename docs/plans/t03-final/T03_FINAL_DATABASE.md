# T03 데이터베이스

## 소유권 및 제약

| 범위 | 테이블 및 계약 |
|---|---|
| 기존 | channel, channel_config, admin_user, audit_event. channel_config는 (channel_id,id) UNIQUE 추가. V1~V5 불변 |
| 콘텐츠 | topic→channel, content→동일 channel의 topic. content에 (id,channel_id), (id,content_type), (id,channel_id,content_type) UNIQUE. channel_id/content_type 불변 |
| Revision·Run | content_revision→content와 같은 channel의 config. content_run→동일 content의 revision. current_revision_id와 active_run_id, revision.base_run_id는 같은 content를 복합 FK로 강제. 새 content는 NULL 포인터 허용 후 같은 TX에서 설정 |
| 큐 | automation_job→동일 content의 run 및 channel, job_dependency→같은 run의 양 job, job_attempt→job 및 queue_worker_instance, job_event→같은 job의 attempt. queue_control과 queue_command_log는 DB 상한 및 영속 요청 키 보존 |
| 비용 | budget_bucket/reservation은 콘텐츠 식별 3열이 전부 있거나 전부 없어야 한다. reservation v0의 channel_id는 기존 규칙, content_id/type은 NULL. v1은 3열 전부 필수. content_budget_policy는 정책 동결, budget_policy_state_event와 reservation_policy_selection은 세대별 후보 집합과 선택 증거, reservation_expected_bucket은 그 결정의 정책·revision·한도·bucket 증거 |
| 작업 효과 | job_operation→job 및 동일 job의 생성 attempt, reservation_intent→operation 및 예약 소유 키, operation_dispatch UNIQUE(operation_id), operation_evidence→dispatch, operation_event_intent의 (operation_id,event_seq)와 event_key UNIQUE, operation_receipt는 P12 terminal 결과. 큐 적용은 receipt 버전 및 완료 참조로 펜싱 |
| 관리자 비용 | settlement_operation→reservation, settlement_guard의 예약당 열린 행 한 개, operation_attestation→operation 및 감사 actor. settlement와 자동 intent의 확정 결과는 불변 |
| 검사 | budget_control의 검사 상태·세대, consistency_check_run/consistency_issue의 실행 결과 및 제한된 상세 |

기존 ID는 signed BIGINT, UUID는 CHAR(36) utf8mb4_bin 소문자 표준 형식, 비교 키와 SHA-256 텍스트는 utf8mb4_bin. 시각 DATETIME(6) UTC. 금액 DECIMAL(24,12) 원통화, DECIMAL(24,10) 환율, DECIMAL(24,6) KRW. FK RESTRICT, 역참조는 부모·자식 생성 후 추가한다. 범위별 상세 컬럼 초안은 SQL을 따른다.

LIFETIME는 content_id가 있는 별도 subject이며 정책당 콘텐츠별 단일 UNIQUE 적용. 2000-01-01 00:00:00.000000 이상, 9999-12-31 23:59:59.999999 미만, UTC. sentinel 고정은 CHECK, API에서는 기간 날짜를 노출하지 않는다. 정책/bucket/content 구분과 채널·유형 키를 참조한다. 기대 링크의 스냅샷은 현재 한도 개정으로 수정하지 않는다.

## 적용 순서 및 권한

V6 이후 도메인 대체 키·topic/content/revision/run과 역 FK → 큐 제어·worker·job/attempt 및 역 FK·dependency/event/log → 비용 FK와 콘텐츠 정책·선택 증거 → operation 및 의도·dispatch·증거·정산·검사 → 인덱스·불변 트리거·최소 권한 → API/mapper 활성화. 버전 번호는 Codex 통합 담당자가 배정한다. 미배포 신구 writer를 동시에 활성화하지 않는다. V5 예약·원장·FX·링크에 추정 데이터 backfill 금지.

운영 앱에는 DDL, 원장·감사·불변 스냅샷 UPDATE/DELETE, 트리거 변경을 허용하지 않는다. guard는 행 제거보다 nullable operation pointer와 명시 CHECK/버전 갱신을 사용한다. 완료 receipt·불변 속성·terminal 행 보호 트리거와 권한 표를 구현 시 추가하고 binlog ON에서 migration 권한을 실측한다. 구 V5 CHECK/DDL과 맞는지 격리 환경에서 검증한다.

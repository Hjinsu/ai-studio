# DB 설계 (제안, 실행 SQL 아님)
기존 signed BIGINT, UTC DATETIME(6), utf8mb4_unicode_ci, 키/hash utf8mb4_bin, 금액/환율 ADR-030 및 RV-04 준수. 기존 V1~V5 수정 금지. V6 이후 신규 migration 번호는 실제 main 확인. 아래 NOT NULL 기본, ?는 nullable. PK/FK 타입·이름은 실제 DDL 기준. 초기 논리 모델의 automation_job 이름을 우선 검토; 아래 job은 논리명이다.

| 테이블 | 컬럼 및 제약 초안 |
|---|---|
| content | id PK, channel_id FK, content_type SHORTS/LONGFORM, title VARCHAR(255), status VARCHAR(32), current_revision_id?, version BIGINT, created_by FK, created_at, updated_at, archived_at?; 채널/유형 불변 |
| content_revision | id PK, content_id FK, revision_no INT, channel_config_id FK(채널 소유권 검증), working_title VARCHAR(255), topic TEXT, description TEXT?, language VARCHAR(35), target_duration_seconds INT, settings_json JSON, revision_hash CHAR(64), change_reason VARCHAR(500)?, created_by FK, created_at; UNIQUE(content_id,revision_no), UNIQUE(content_id,id), append-only |
| content_run | id PK, content_id, content_revision_id, run_no INT, pipeline_version VARCHAR(50), status VARCHAR(32), request_key VARCHAR(128), request_fingerprint CHAR(64), requested_by, created_at, started_at?, finished_at?; UNIQUE(content_id,run_no), UNIQUE(request_key), composite revision ownership FK |
| job | id PK, run_id FK, job_key VARCHAR(128), job_type VARCHAR(50), status VARCHAR(32), priority INT, attempt_count INT, max_attempts INT, available_at, lease_owner VARCHAR(128)?, lease_token VARCHAR(64)?, lease_expires_at?, heartbeat_at?, cancel_requested_at?, input_json JSON, output_json JSON?, error_code VARCHAR(64)?, error_message VARCHAR(500)?, created_at, updated_at; UNIQUE(run_id,job_key), UNIQUE(run_id,id) |
| job_dependency | run_id, job_id, predecessor_job_id; PK(job_id,predecessor_job_id), 두 복합 FK로 동일 run 보장, self edge 금지, 순환은 생성 TX 검증 |
| job_attempt | id PK, job_id FK, attempt_no INT, lease_token, started_at, finished_at?, result?, error_code?, error_message?; UNIQUE(job_id,attempt_no) |
| job_event | id PK, job_id FK, attempt_id?, event_type, from_status?, to_status, actor_type, actor_id?, reason?, created_at; append-only |
| job_operation | id PK, job_id FK, operation_key UNIQUE, request_fingerprint, immutable request snapshot, reservation_request_key UNIQUE, reservation_id?, state, dispatch_intent_at?, provider_ref?, result_snapshot?, settlement snapshot/key?, version, created_at, updated_at |
| operation_event | id PK, operation_id FK, event_key UNIQUE, from_state?, to_state, safe details, created_at; append-only |
| settlement_operation | id PK, reservation_id FK, creation_request_key UNIQUE, payload_hash, immutable payload, event_key UNIQUE, state, actor_id, result reference?, version, created_at, updated_at; 관리자 정산 독립 식별 |

## 소유권과 무결성
content.current_revision_id는 nullable. FK(content.id,current_revision_id) -> revision(content_id,id); 최초 content INSERT, revision INSERT, 포인터 UPDATE 한 TX. revision과 channel_config가 같은 channel인지 복합 FK 또는 검증 가능한 제약으로 보호. revision 번호는 content 행 잠금 + baseRevision/version 검증 후 증가. hash canonicalization 규칙(UTF-8, 정렬된 필드, null/숫자 규칙, hash 버전) 고정 및 테스트. 제목의 제작 의미는 revision이 기준. 활성 run 1개는 content 행 잠금 + nullable generated active key unique 또는 별도 guard로 보장; RECOVERY_REQUIRED 포함 비종료 run은 활성. 불확실 run 남은 동안 새 run 차단. 물리 삭제 금지, FK cascade 삭제 금지.

## 인덱스
job(status,available_at,priority,id), job(status,lease_expires_at,id), job(run_id,status), dependency(predecessor_job_id,job_id), attempt(job_id,attempt_no), event(job_id,id), run(content_id,id), operation(state,updated_at,id), settlement(reservation_id,state,id). 실제 claim ORDER와 EXPLAIN/동시성 측정 후 조정. 상태/lease 필드 조합 CHECK 및 양수/범위 검증. attempt와 operation 연결의 소유권 보장.

## 기존 비용 테이블 변경
이전 T02_DDL_DRAFT에서 budget_policy/bucket/reservation에 content_id NULL 제약이 확인됨. 최신 V3~V5 실제 DDL 확인 후 bucket/reservation 제약을 새 migration으로 변경, FK(content_id), reservation 채널 일치 복합 FK 또는 강제 검증 추가. budget_policy는 유형별 기본 정책으로 유지(개별 CONTENT 정책 도입은 별도 승인). LIFETIME bucket은 정책+content에 고정된 시작/종료 규칙으로 단 하나; 기간 변경/정책 revision 변경으로 새 bucket 생성 금지. 기존 generated subject_key/unique(period_start) 활용 여부 검증, LIFETIME 전용 unique 보장 추가 검토. 실제 period sentinel 및 기존 기간 검사와의 호환은 A에서 확정. 기존 원장·예약 연결 변경/삭제 금지. Quartz JDBC JobStore 공식 고정 버전 스키마와 Flyway 관리, 자동 초기화 금지(ADR-022).

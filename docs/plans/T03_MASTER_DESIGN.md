# AI Studio T03 — 개발 인수 패키지
작성: 2026-11-25 KST / v0.1 / 설계 제안, 구현 승인 전

## 집에서 시작하기
1. 회사의 T02 종료 보고서로 PR #1 병합 및 최신 main CI를 확인한다. 병합 완료 시 `git fetch origin; git switch main; git pull --ff-only origin main`. 미병합이면 기존 작업 브랜치에서 종료 절차를 마친다.
2. 기존 개발 DB를 백업하고 회사 덤프를 복원한다. Flyway V1~V5 보존. Java21, Node24, MariaDB11.8, AI_STUDIO_DB_URL/USER/PASSWORD 확인. 비밀/덤프 Git 금지.
3. 백엔드 ` .\gradlew.bat :backend:bootRun`, 별도 터미널 `cd frontend; npm.cmd ci; npm.cmd run dev`. localhost:5173 및 localhost:8080/actuator/health 확인. 기존 IFNOVA, 정책, 관리자, 지출 차단 확인.
4. 이 패키지를 저장소의 임시 검토 경로(예: docs/plans/t03-draft/)에 복사한다. 기존 문서를 덮어쓰지 않는다.
5. T03_HANDOFF.md를 Codex 또는 Claude에게 전달한다. 처음은 T03-A 계약 대조 및 승인 요청이다. 사용자 승인 후 개발한다.

## 파일
T03_PLAN / DATABASE_DESIGN / STATE_MACHINE / JOB_ENGINE_DESIGN / COST_INTEGRATION / API_DESIGN / UI_DESIGN / TEST_PLAN / DECISIONS / HANDOFF.

## 출처와 한계
사용자 첨부 최신 T03_PLAN.md, DECISIONS(2).md, T02_DDL_DRAFT.sql 및 openapi.yaml(이전 첨부 초안), 초기 docs/03_DATABASE.md, docs/08_AUTOMATION.md 및 대화 설계안. 최신 main의 실제 Java 코드·DDL·API·CI는 직접 검증하지 못했다. 특히 초기 논리 모델의 automation_job 및 상태명은 최신 제안과 차이가 있어 명시적 ADR 승인 후만 변경한다. 신규 D01~D12는 아직 승인되지 않았다. 실제 마이그레이션 및 제품 코드는 이 패키지에 포함되지 않는다.


---

# T03 상세 계획 v0.1 (제안)
목표: 콘텐츠 버전과 실행 단위, DB 큐, lease, 재시도, 장애 복구, 콘텐츠 예산을 구현하여 T04~T10 실행 기반 확보. T02 DONE 승인됨; 실제 PR 병합 및 main HEAD 확인 필수.

## 범위
콘텐츠 등록/보관, 불변 revision, run, DAG, job/attempt/event, worker, fake executor, 격리 fake provider, LIFETIME budget, 영속 수동 정산 요청, 관리자 UI. 실제 조사·대본·스토리보드 T04, 미디어 T05, 검수 T06, 유료 T07, 게시 T08. 주장/근거 및 미디어 모델의 연결 계약은 ADR-015/016과 일치하도록 대조하되 선행 구현 강제하지 않는다. Redis/Kafka 미도입.

## 필수 선행 제약 (기존 T03_PLAN 10개)
1. CONTENT_TYPE LIFETIME 연결과 reservationBucketIssues 동시에 확장; 과거 T02 링크 호환.
2. LockRetryingTransactions.execute는 외부 활성 TX에서 금지.
3. 큐/비용은 영속 operation + 키 + 복구로 연결.
4. reserve: 및 reserve-v2: 예약 전용.
5. 예산 재계획 소진 503 재시도; 비즈니스 오류 자동 재시도 금지.
6. 전체 정합성 스캔/control X 잠금 측정.
7. 신뢰 프록시 명시적 목록, 전달 헤더 위조 방지; 운영 배포 전 해결.
8. 정합성 오류 진단/승인 기반 수동 복구; 실제 비용 전 검증.
9. DB UTC 시각, 단일 인스턴스 유지; 확장 전 분산 로그인 및 시계 재검토.
10. P-T02-05 서버 영속 수동 정산 식별/조회/복구; 실제 비용 전 완료, T03 구현 제안.

## 단계와 종료 게이트
| ID | 결과물 | 게이트 |
|---|---|---|
| A | 실제 DDL/코드/API와 대조, ADR/ERD/전이표/OpenAPI 확정 | 사용자 승인, 변경 범위 고정 |
| B | V6 이후 migration, 콘텐츠/revision/run | 깨끗한 DB + V5 업그레이드 테스트 |
| C | DAG/큐/lease/취소/재시도/fake executor | 동시성/장애 테스트 |
| D | 비용 LIFETIME/operation/수동 정산 복구 | 비용 원장·정합성·장애 테스트 |
| E | 관리자 화면 | 계약·접근성·세션 경계 테스트 |
| F | 통합/독립 리뷰/사용자 확인 | CI 전체, 보고서, 사용자 승인 후 DONE/merge |

A에서 계약을 먼저 고정하고 B/C/D 백엔드 담당 1인, E UI 담당 별도 브랜치/워크트리 가능. 공유 파일(OpenAPI, migration, 상태 문서)은 소유자를 한 명 지정한다. T02 보류 UI/API 항목 무단 확대 금지.

## 완료 조건
기존 회귀 + 신규 테스트 통과, 격리 DB에서 중복/lease 만료/재시작/원장 복구 검증, 지출 차단 유지, 실제 provider/게시 0회, 사용자 검증 및 독립 교차 리뷰. 미실행 검증은 명시. STATE/TASKS/DECISIONS/인덱스/보고서 갱신, 사용자 승인 후만 DONE. 기존 V1~V5 불변, T04 자동 착수 금지.


---

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


---

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


---

# DB 작업 실행 엔진 (제안)
Quartz는 탐색/복구 트리거만 담당; DB가 상태의 유일한 원천. Quartz JDBC JobStore는 Flyway 관리. 단일 인스턴스, worker 2개, poll 1초, lease 60초, heartbeat 15초, sweep 10초, 콘텐츠별 활성 run 1개/실행 job 2개. 전역 다중 인스턴스 실행 제한을 보장한다고 주장하지 않는다. 환경 설정 AI_STUDIO_ 접두사. 실제 provider 미등록/실행 금지.

## Claim / fencing
Dispatcher는 worker permit 확보 후 짧은 READ COMMITTED TX에서 eligible job을 선택한다. run/channel 활성 여부와 의존성/취소/시도 상한을 재확인. 동일 run 잠금과 job 잠금 순서는 A에서 단일화하고 모든 집계/취소/복구에 적용한다. budget lock과 섞지 않는다. 예시 후보 쿼리(그대로 실행할 migration 아님):
```sql
SELECT id FROM job WHERE status='READY' AND available_at<=UTC_TIMESTAMP(6)
ORDER BY priority DESC,available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED;
```
실제 claim은 run/job 순서 및 슬롯 제한 검증 후 token(UUID), owner, expiry, attempt_count 증가, attempt/event를 원자 저장. claim 실패 permit 반환. claim 후 TX 종료, 실행 시작. heartbeat/complete/update는 id+token+허용 상태+lease_expires_at>DB UTC 조건과 affected rows=1 검사. stale worker 결과 차단; 외부 부작용은 token만으로 차단 불가능. 만료 시 sweep이 동일 잠금 순서로 재확인하고 attempt 종료, retry/recovery 전이. heartbeat와 sweep 경합 테스트. heartbeat는 worker 실행과 독립 스케줄러. 재시작은 미확정 operation부터 진단, 만료되지 않은 lease 강제 탈취 금지.

## DAG
run 생성 TX에서 고정 pipeline version과 job set/edges 저장, 위상 정렬로 순환/누락/자기참조 거부, 최대 nodes/edges 제한을 설정하고 테스트. 동적 DAG 변경은 T03 제외. 모두 성공한 predecessor만 READY. 결과 전달은 동일 run 내 불변 결과 참조; 이전 run 결과 혼합 금지. dependency promotion/terminal 집계는 중복 실행 가능한 reconciler로 회복.

## Retry
최초 포함 최대 5회 제안(초기 문서 3회 제안과 충돌: 사용자 승인 필요), base 5초, cap 300초, exponential+jitter, Retry-After는 더 긴 경우 존중. UTC available_at에 실제 결정 지연 저장. 예산 재계획 소진 503, DB 일시 장애는 기존 key로 안전할 때만 retry; BUDGET_EXCEEDED/POLICY_VIOLATION/PREREQUISITE_NOT_MET는 ACTION_REQUIRED, 잘못된 입력 확정 실패. 외부 결과 불명은 무조건 RECOVERY_REQUIRED. 관리자가 재개해도 기존 attempt 기록/키 유지; 한도 초과 시 새 run 또는 승인된 복구만.

## 운영
graceful shutdown: claim 중단, 진행 작업 제한 시간 대기, 미완료는 lease/operation으로 복구; 종료 시 비용 예약 자동 해제 금지. fake executor는 성공/실패/지연/취소/응답유실 모드를 allowlist로 제공, 외부 네트워크 호출 금지. 운영 프로필에서 fake 실행 UI 활성화 여부 명시. 로그에는 job/run/trace ID, 비밀/요청 민감 본문 제외. 큐 metrics: ready age, claim rate, retry, lease expiry, recovery count; 기존 health 공개 범위 유지.


---

# 비용 통제 및 영속 복구 (제안)
ADR-017/019/026~030/038/042/043/048/054~057/061, RV-01~09 유지. 원장 append-only, 정산 기존 링크 및 환율 고정, 실제 비용 초과도 기록. 시스템 DAY/MONTH 필수, 기존 CHANNEL 적용 규칙 유지, 콘텐츠 작업은 CONTENT_TYPE LIFETIME 필수(제안), 정책 누락/비활성/0원 fail closed. 신규 지출 차단 유지, 기존 정산 허용. 정책별 한도는 중복 합산 금지.

## LIFETIME
적용 범위는 실제 기존 CONTENT_TYPE 정책의 전역/채널별 선택 계약 확인 후 확정. 제안: 해당 유형 전역 정책 및 채널별 적용 정책이 모두 존재하면 각각 적용, 비활성 정책을 우회하지 않음; 최소 한 개 활성 적용 유형 정책 필수. 각 policy/content 단위 고정 bucket, revision/run 변경으로 비용 초기화 금지. 정책 재생성으로 초기화 금지. 정책 변경 시 동일 bucket에서 한도 갱신, 기존 소비·예약·링크 보존. content 소유 채널/유형 검증. 기존 SYSTEM/CHANNEL DAY/MONTH 연결 검사는 유지. reservation.content_id NULL인 기존 T02 예약은 기존 계약으로 검사, content_id 있는 예약은 당시 적용 정책의 정확한 링크 집합 및 유형·기간·소유권 검증. 현재 정책 목록을 과거 예약에 소급하지 않는다. 검사 시각/정책 revision 판정은 실제 RV-07 구현과 대조. 기존 예약 링크 수정 금지. content 예산 집계는 하나의 bucket 기준으로 표시, 여러 정책이면 최소 잔여 한도.

## 트랜잭션 경계
1. TX1: job token 검증, 논리 operation/불변 요청/키 준비.
2. TX2: 기존 budget service 최상위 TX, request_key로 reserve. 외부 TX 없이 호출. 성공/불확실 응답은 동일 key 조회/재실행으로 확인.
3. TX3: 예약 ID 연결, 상태 RESERVED. 취소/lease 상실이면 외부 호출 금지, orphan recovery로 정리.
4. TX4: 유효 token과 실행 조건 검증, DISPATCHING intent를 커밋. 실제 호출은 TX 밖. 이후 crash는 요청 전송 여부를 증명할 수 없으면 불확실. 외부 호출 직전 지출 차단/채널 조건 재검증 정책 및 경쟁 경계 A에서 확정(이미 시작된 호출의 강제 취소는 보장 불가).
5. TX5: 결과와 immutable 정산 payload 기록; stale worker는 job 결과를 변경 못함. 불확실 응답은 복구 절차로만 처리.
6. TX6: 기존 budget settlement 최상위 TX, 안정 event_key. 미확정 시 원장 조회/동일 payload 재시도.
7. TX7: 원장 결과 확인 후 operation COMPLETED/job 완료. 성공 기록 전에 정산 성공 증거 필요.

상태 PREPARED→RESERVED→DISPATCHING→RESULT_RECORDED→SETTLEMENT_PENDING→COMPLETED. 미확정 상태 RECOVERY_REQUIRED, 미실행 및 예약 해소 확인 시 CANCELLED. 예약 자체 RECONCILIATION_REQUIRED는 기존 서비스 계약 사용. 큐 락을 보유한 채 비용 호출 금지; budget_control→channel→policy→bucket→reservation→event INSERT 유지. 예약 전후 crash, 링크 없는 예약, 완료 후 job 미반영을 양방향으로 탐지하는 reconciler; 동일 operation에 동시 처리 권한 guard 및 키 불일치 fail closed. 자동 복구는 증명 가능한 멱등 DB 연산만 허용, 외부 재호출/자동 해제 금지.

## 키
run:v1:<uuid>, op:v1:<uuid>, job-budget:v1:<uuid>, settle:v1:<uuid>:confirm:1 / provisional:1 / release:1, admin-settle:v1:<uuid>. reserve:/reserve-v2: 사용 금지. 길이/regex 실제 DDL 확인. 같은 key 같은 canonical payload 재사용; 다른 payload 409. 요청 단위 UUID는 재시도 전에 영속 저장. 복수 정산 이벤트는 각 logical event sequence를 영속 생성, 시도 횟수로 key 변경 금지.

## P-T02-05 관리자 정산
별도 settlement_operation 제안. 생성 API는 client creation key+fingerprint 멱등, 서버 ID/불변 payload 저장. 실행은 해당 ID만 사용, 원장 event_key 고정, 결과 조회 GET 및 미완료 목록 제공. 새로고침/다른 탭/기기에서 기존 pending/unknown operation을 우선 제시하고 별개 정산 생성은 명시적 확인. 생성 응답 유실도 목록/키로 찾는다. 동일 reservation에 불확실 operation이 있으면 새로운 조정 실행을 차단하고 기존 결과를 확인; 생성 경쟁은 DB guard로 보호. 정상적인 같은 금액의 별개 조정은 기존 완료 확인 후 새 operation으로 허용. 기존 직접 resolve/adjustment API로 우회되지 않도록 서버에서 영속 operation 계약을 강제(기존 호환 변경은 ADR/OpenAPI 승인 필요). 페이지 상태나 localStorage만으로 해결하지 않는다. 권한/CSRF 및 T02 세션 generation 경계 적용. 원장 커밋 후 operation 결과 저장 실패는 원장으로 복구; GET 미조회만으로 미실행 단정 금지.

## CR-01/02 및 성능
신뢰 프록시는 운영 전에 명시 allowlist, untrusted forwarded header 제거, 신뢰 hop 검증, rate limit 정책 재검증. 로컬은 remoteAddr 유지. 오류 복구: 지출 차단→격리 백업/진단(예약 ID, link set, policy revision, bucket 합계, ledger delta, operation)→사용자 승인→원장 불변을 지키는 보정 migration/관리 명령 별도 승인→정합성 재검사→명시적 지출 해제(실제 과금 승인 전 금지). 자동 데이터 수정 금지. 검사 규모 1k/10k/100k 예약의 scan 시간, control lock hold, reserve/settle p95 및 타임아웃을 격리 DB에서 측정; 허용치 A에서 합의하고 결과 보고, 안전성 완화 금지. 다중 인스턴스 도입 전 DB 시각 일관성·분산 로그인 재검토.


---

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


---

# 관리자 UI 설계 (제안)
ADR-063/066 및 T02 인증 세대 보호 재사용. Tailwind tokens, Lucide, Radix, Pretendard 로컬, 라이트/다크, 모바일 키보드 접근성. 새 의존성 무단 도입 금지. BIGINT/금액 문자열 유지, 서버 정렬 유지. 실제 비용/영상/게시로 오인되는 fake 결과 금지.

| 화면 | 기능 |
|---|---|
| 콘텐츠 목록 | 채널/유형/상태/검색, 서버 페이지, 현재 revision/실행 요약, 비용 정책 기준 잔여 한도 |
| 생성/편집 | ACTIVE 채널, 유형, 주제/제목, 설정 snapshot, baseRevision/version 확인; 변경불가 채널/유형 안내 |
| 상세 | 개요/설정 이력/실행/작업/비용 탭, 실행 생성 및 취소 확인, 이전 결과 분리 |
| 작업 목록/상세 | 상태 필터, 선행 작업, attempt/event, lease 만료 시각, 오류 요약, 안전한 재개/취소 |
| 복구 관리 | 결과 불확실·조치 필요 구분, 영속 operation ID/예약/원장 결과, 조회와 실행 분리, 강제 성공/해제 없음 |
| 예약·정산 기존 화면 | 미확정 요청 우선, 서버 operation 초안/확인/실행/결과 조회; 새로고침/다른 탭에서도 복구 |
| 대시보드 | 대기/진행/실패/복구 필요 개수, 지출 차단/정합성 기존 규칙 |

진행률 성공 job / 전체 job, terminal 수 별도 표시, 실패/취소/생략 성공으로 계산 금지. 미확정 결과를 실패로 단정하지 않음. 폴링(제안 3초 활성 상태, 숨김 탭 중지), 요청 경합 및 session generation 변경 시 이전 토스트/캐시/탐색 차단. 401 로그인 복귀, 403 기존 안전 재시도 규칙, 429 Retry-After 표시. 비용 정책 잔여 한도와 실제 예약 가능 여부 분리. 실행 중 취소는 요청 상태 표시. 상태 한글 label 및 접근성 테스트. 실제 화면 검증은 사용자 개발 DB를 손상시키지 않는 fake/격리 환경에서 실시.


---

# 검증 계획 (제안)
테스트 수는 사전 확정하지 않는다. 기존 T02 백엔드 133 단위/97 통합, 프론트 73 단위/10 mock E2E는 과거 검증 기준이며 신규 실제 결과와 구분한다. 운영 DB 3306 사용자 데이터 사용 금지; Testcontainers/격리 테스트 DB. CI 7 job 유지 및 신규 검증 추가. 미실행은 명시.

| ID | 필수 시나리오 |
|---|---|
| DB01 | 빈 DB V1~신규, V5→신규, 이전 migration checksum 불변, 기존 예약/원장 호환 |
| DB02 | cross-content revision/run 및 channel 소유권, 불변 revision, 동시 revision 생성 |
| DB03 | 동시 run 생성 1개, 동일 키 replay, 다른 payload 409, 미확정 run 재생성 차단 |
| Q01 | DAG 순환/self/cross-run, fan-out/fan-in, 선행 실패/취소 전파 |
| Q02 | 동시 claim 경쟁, slot 상한, ready 시간·우선순위, starvation 측정 |
| Q03 | heartbeat/expiry 경합, stale token complete 거부, DB UTC |
| Q04 | 모든 상태 전이와 금지 전이, 취소/완료 경합, terminal 집계 |
| Q05 | retry 상한·backoff·jitter·Retry-After, 503 vs 비즈니스 오류 |
| Q06 | 서버 강제 종료 및 재시작, 미확정 작업 재호출 금지 |
| C01 | LIFETIME 콘텐츠별 분리, revision/run/정책 변경 후 누적 유지, 채널/유형 불일치 |
| C02 | 기존 T02 예약 검사 호환, 신규 링크 누락/중복/잘못된 bucket 검출, ERROR 신규 지출 차단 |
| C03 | budget service 외부 TX 금지, lock order 및 경쟁, 금액/환율 기존 정밀도 |
| C04 | 각 TX 경계 crash 주입: PREPARED, reserve commit, link, dispatch intent, 결과, settlement commit, job complete |
| C05 | orphan reserve 탐지, 동일 키 재시도, 미확정 자동 해제 금지, 중복 정산 차단 |
| C06 | 수동 정산 생성/실행 응답 유실, 새로고침/탭/기기, 동시 요청, 같은 내용 별개 정상 조정, 기존 직접 API 우회 차단 |
| C07 | 지출 차단/채널 pause/보관 시 신규 예약 금지, 기존 정산 허용, fake 격리 |
| C08 | 정합성 검사 규모별 control lock/지연 측정, CR-02 진단 dry-run |
| API01 | 실제 MockMvc OpenAPI 요청/응답 및 추가 필드 거부, ID 경계, 인증/CSRF/오류 |
| UI01 | 목록/상세/이력/작업/복구, loading/empty/error, 한글/라이트·다크/모바일 |
| UI02 | 오래된 세션 콜백 및 요청 차단, 정산 조회 실패 후 operation 유지, 401/403/429 |
| E2E01 | mock 브라우저 검증과 별도 실제 백엔드+격리 DB 브라우저 검증(가능 여부 A에서 확정) |
| REG01 | 기존 T02 전체 회귀, OpenAPI 재생성 diff, lint/typecheck/build, gitleaks |

장애 테스트는 sleep 기반 경쟁 대신 barrier/latch/제어 시계/명시 fault injection 우선. DB UTC 조건은 실제 DB 통합으로 확인. 각 시나리오에서 job/attempt/operation/reservation/bucket/ledger의 최종 불변조건을 assert. 테스트 보고서에 명령, 환경, 커밋, 통과/실패/미실행 및 CI ID 기록. 독립 리뷰는 핵심 상태/락/원장/세션 경계에 집중. 사용자 검증 후에만 DONE 승인 요청.


---

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


---

# Codex / Claude 개발 착수 지시서
AI Studio T03 인수 작업. 첨부 설계 패키지는 v0.1 제안이며 사용자 승인 전 제품 변경 금지. 저장소의 AGENTS.md, CLAUDE.md, STATE.md, TASKS.md, DECISIONS.md, docs/00_INDEX.md, 협업/로컬 실행 가이드, 기존 docs/plans/T03_PLAN.md를 먼저 읽는다. T02 DONE 및 PR #1 main 병합/CI 실제 확인. 집 PC 경로는 현재 저장소 루트 사용, 회사 경로 하드코딩 금지.

## 지금 수행할 T03-A
- git status, 현재 branch/HEAD, remote, PR 상태, CI, 미커밋 변경 확인. T02 미종료면 우선 종료 절차만 보고.
- 실제 V1~V5 migration과 mapper/domain/service, BudgetMapper.reservationBucketIssues, LockRetryingTransactions, request/event key, 현재 OpenAPI/생성 타입, 초기 DB/automation docs, Quartz 구성 대조.
- 패키지의 10가지 제약/12개 D안과 기존 ADR의 충돌·누락·추가 결정 목록 작성. 실제 schema/API 이름과 상태명으로 상세 계약 수정 제안. 기존 파일 덮어쓰기 금지.
- DB 컬럼/제약/인덱스/DDL 초안, 상태 전이표, 잠금 순서, 장애 경계/복구 판정, OpenAPI 변경안, UI 및 테스트 계획을 저장소 기존 문서 구조에 맞게 정리. 작업 분담 및 공유 파일 소유자 지정. 문서 전용 변경 범위를 사용자에게 보고.
- `docs/reports/T03_DESIGN_REVIEW.md`에 기준 HEAD, 확인 근거 파일/행, 충돌, 선택지/추천, 미결 사항, 승인 필요 D안, 테스트 계획 기록. 문서 커밋/푸시는 사용자 및 저장소 규칙에 따른다.
- **사용자에게 설계 승인 요청 후 정지.** 채팅에서 문서화 요청은 D01~D12 일괄 승인 또는 제품 구현 승인이 아니다. 이미 별도 명시 승인된 결정은 그 근거를 기록하고 중복 질문하지 않는다.

## 승인 후 실행
T03-A 계약 승인 및 T02 main 병합 확인 후에만 새 T03 브랜치/워크트리 생성, B→C→D→E→F 진행. backend/shared contract 소유자 지정, 다른 에이전트는 승인된 API로 UI 구현. 단계별 보고/테스트. 기존 V1~V5 수정 금지, 사용자 개발 DB 테스트 금지, 비밀/덤프 Git 금지, 실제 유료 API/지출 허용/게시 금지. Fake 검증은 격리 DB에서만 비용 허용 설정. 최종 T03 DONE/main merge는 사용자 승인 후, T04 착수는 별도 승인.

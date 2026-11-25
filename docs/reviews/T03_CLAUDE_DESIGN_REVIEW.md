# T03-A1 — 도메인 및 작업 엔진 설계 검증 (Claude Code)

| 항목 | 내용 |
|---|---|
| 작업 ID | T03-A1 (사용자 지시 2026-11-25. `TASKS.md`에는 아직 T03-A1 행이 없다 — CDR-25) |
| 검토자 | Claude Code (독립 검토. Codex의 비용 안전성·동시성 검토 결과는 참조하지 않았다) |
| 기준 | `main` `47ef697` (작업 트리 clean). 검토 대상 초안은 미추적 파일 `docs/plans/t03-draft/*`(10개)와 `docs/plans/T03_MASTER_DESIGN.md`(t03-draft 10개 파일을 이어 붙인 동일 내용) |
| 대조 근거 | `AGENTS.md`, `CLAUDE.md`, `STATE.md`, `TASKS.md`, `DECISIONS.md`(ADR-001~066), `docs/00_INDEX.md`, `docs/03_DATABASE.md`, `docs/04_API_SPEC.md`, `docs/08_AUTOMATION.md`, `docs/plans/T03_PLAN.md`, 실제 migration V1~V5, `backend/src/main/java/com/aistudio/**`, `docs/api/openapi.yaml`(0.3.0), `infra/mariadb/prod-app-grants.sql` |
| 방법 | 문서·코드·DDL 정적 대조. **테스트·DB·빌드는 실행하지 않았다**(설계 검토 범위). 실행으로 확인해야 하는 사항은 "미확인"으로 표시했다 |
| 수정 범위 | 이 보고서 1개 파일만 추가. 제품 코드·DB·migration·기존 설계 문서·TASKS/STATE/DECISIONS는 수정하지 않았다 |

---

## 0. 요약

### 0.1 항목별 판정

| # | 검토 항목 | 판정 | 핵심 사유 |
|---|---|---|---|
| 1 | 콘텐츠·Revision·Run·Job·Attempt 분리 | **조건부 적합** | 분리 방향은 맞다. Revision과 Run 산출물의 관계(무엇을 승인·재생성하는가)가 정의되지 않았다(CDR-02) |
| 2 | 신규 테이블 컬럼·FK·인덱스·제약 | **조건부 적합** | 복합 FK 의도는 좋다. 실제 V4/V5에 필요한 UNIQUE가 없고, 비정규화·권한·불변 트리거 명세가 빠졌다(CDR-11, 21, 22, 23) |
| 3 | 상태 전이의 완전성 | **부적합(수정 필요)** | BLOCKED 의미가 기존 계약과 반대다. 취소 의도가 이중으로 표현되고, lease 만료 전이가 없다. Run/Content 상태가 서로 모순된다(CDR-01, 04, 10) |
| 4 | DAG·순환 방지 | **조건부 적합** | 생성 시 위상 정렬은 적절하다. 다만 고정 DAG는 장면 단위 fan-out(T05)과 양립하지 않고, fan-in 승격 경쟁이 미정이다(CDR-03, 08) |
| 5 | Quartz와 DB 큐 역할 분리 | **조건부 적합** | 원칙은 ADR-022와 일치한다. 1초 poll은 Quartz에 부적합하고, JDBC JobStore 도입 시점이 미정이다(CDR-12) |
| 6 | Lease·Heartbeat·Worker 권한 | **조건부 적합** | fencing 조건식은 적절하다. 멈춘 worker의 lease가 무한 연장되고, 단일 인스턴스 가정이 강제되지 않으며, 시각 원천이 섞여 있다(CDR-13, 14, 16) |
| 7 | 재시도·취소·장애 복구 | **조건부 적합** | 분류 원칙은 좋다. 횟수 충돌(3 vs 5), 수동 재개와 횟수의 관계, 사람의 판정 경로가 없다(CDR-05, 15, 20) |
| 8 | T04~T10 확장성 | **부적합(사전 결정 필요)** | 장면 확장, 산출물 귀속, ai_request_log 중복, topic·FR02가 T03 스키마 결정에 직접 묶여 있다(CDR-02, 03, 17, 18) |
| 9 | 관리자 UI·API | **조건부 적합** | 멱등 키 전달 방식이 기존 계약과 다르다. 기존 `/produce` `/retry` 기준선과 대응이 필요하다(CDR-07, 20, 24) |
| 10 | 기존 ADR과의 충돌 | **충돌 있음** | docs/08 상태명·재시도 횟수·`automation_job`, docs/03 T03 범위 테이블, ADR-022 JobStore, ADR-054 Fail Closed 범위 확장(§10 표) |

### 0.2 승인 전 반드시 결정할 사항 (HIGH)

1. **CDR-01** 작업 대기 상태 이름. 초안의 BLOCKED는 docs/08에서 "외부 결과 불명"을 뜻한다. 이름을 `WAITING`으로 바꾸고, docs/08의 BLOCKED+EXTERNAL_OUTCOME_UNKNOWN은 RECOVERY_REQUIRED로 대응시킨다.
2. **CDR-02** Revision은 불변 *입력 사양*이고, 산출물(대본·장면·asset)은 Run에 귀속한다. 장면 편집은 `base_run_id`를 가진 새 revision으로 처리한다. 승인(T06)은 revision과 run manifest를 함께 묶는다.
3. **CDR-03** 고정 DAG에 *제한된 확장 노드*를 허용한다. 확장 job이 같은 run 안에 자식 job을 추가하되, 시작 전 barrier 노드로만 연결해 순환을 막는다. 컬럼은 T03에서 예약하고 구현은 T04/T05에서 한다.
4. **CDR-04** 취소 의도는 `cancel_requested_at` 컬럼 하나로만 표현하고, CANCEL_REQUESTED 상태는 제거한다. LEASE_EXPIRED 전이를 명시한다.
5. **CDR-05** RECOVERY_REQUIRED를 끝낼 *감사되는 관리자 판정(attestation)* 경로를 만든다. 초안의 "강제 성공·해제 없음"과 docs/05의 "조회 불가 시 사람 확인"이 충돌한다.

---

## 1. 발견 사항 목록

심각도는 협업 가이드 §7(CRITICAL/HIGH/MEDIUM/LOW)을 따른다. 설계 결함과 개선 제안을 구분해 표시했다(결함=D, 제안=P).

| ID | 심각도 | 구분 | 항목 | 요약 |
|---|---|---|---|---|
| CDR-01 | HIGH | D | 3,10 | job BLOCKED의 의미가 docs/08(외부 불명)과 초안(선행 대기)에서 정반대다 |
| CDR-02 | HIGH | D | 1,8 | Revision과 Run 산출물의 귀속·승인 대상·장면 재생성 모델이 없다 |
| CDR-03 | HIGH | D | 4,8 | 고정 DAG로는 storyboard 이후에 정해지는 장면 수만큼 job을 만들 수 없다(docs/06 "장면별") |
| CDR-04 | HIGH | D | 3,7 | CANCEL_REQUESTED 상태와 cancel_requested_at 컬럼이 중복된다. lease 만료·취소 중 실패 전이가 없다 |
| CDR-05 | HIGH | D | 7,9 | 외부 결과를 조회할 수 없을 때 RECOVERY_REQUIRED를 끝낼 수단이 없다 |
| CDR-06 | MEDIUM | D | 2,8 | 현행 예산 코드는 CONTENT_TYPE 정책을 잠그지도 적용하지도 않는다. D09 구현 범위가 초안 서술보다 크다 |
| CDR-07 | MEDIUM | D | 9,10 | 멱등 키를 본문 `requestKey`로 받는 초안과 기존 `Idempotency-Key` 헤더 계약이 다르다 |
| CDR-08 | MEDIUM | D | 4,6 | fan-in 승격 경쟁(lost promotion)이 가능하다. claim의 job→run 잠금 순서가 역전될 위험이 있다 |
| CDR-09 | MEDIUM | P | 1,2 | 활성 run 1개 보장은 generated unique보다 `content.active_run_id` 포인터가 단순하다 |
| CDR-10 | MEDIUM | D | 3,10 | Run 목록에 RECOVERY_REQUIRED가 없다. Content 상태가 run 결과를 복제하고 docs/08과 충돌한다 |
| CDR-11 | MEDIUM | D | 2 | 소유권 복합 FK에 필요한 UNIQUE·컬럼이 실제 V4/V5에 없다 |
| CDR-12 | MEDIUM | P | 5,10 | 1초 poll·heartbeat를 Quartz로 처리하면 부적합하다. JDBC JobStore 도입 시점 결정이 필요하다 |
| CDR-13 | MEDIUM | D | 6 | 단일 인스턴스가 강제되지 않는다. 메모리 permit 기반 상한은 두 번째 프로세스에서 깨진다 |
| CDR-14 | MEDIUM | D | 6 | 독립 heartbeat는 멈춘 worker의 lease도 계속 연장한다 |
| CDR-15 | MEDIUM | D | 7,10 | 재시도 3회(docs/08)와 5회(D07)가 충돌한다. 수동 재개와 시도 횟수의 관계가 미정이다 |
| CDR-16 | MEDIUM | P | 6 | 예산은 앱 Clock, 큐는 DB UTC를 쓴다. 시각 원천 규칙이 없다 |
| CDR-17 | MEDIUM | D | 8,10 | docs/03이 T03으로 표시한 topic(FR02 중복 검사)·content_scene의 처리 결정이 없다 |
| CDR-18 | MEDIUM | P | 8 | job_operation과 docs/03 ai_request_log(T04)의 역할이 겹친다 |
| CDR-19 | LOW | P | 1 | content.title과 revision.working_title이 둘 다 원천이다 |
| CDR-20 | LOW | P | 7,9 | job 단위 취소의 의미가 불명하다. `/jobs/{id}/retry`가 terminal 재개 금지와 어긋난다 |
| CDR-21 | LOW | D | 2 | claim 인덱스 컬럼 순서와 ORDER BY가 맞지 않아 filesort가 생긴다 |
| CDR-22 | LOW | D | 2,10 | 운영 권한(prod-app-grants), ADR-053 bin 허용 목록, 불변 트리거 명세가 빠졌다 |
| CDR-23 | LOW | D | 2 | language 길이가 channel과 다르다. revision hash 정규화 수단(Fingerprints)이 package-private이다 |
| CDR-24 | LOW | P | 9 | 클라이언트가 pipelineVersion·내부 channelConfigId를 지정한다. 대시보드 API 확장과 결과 저장소 명세가 빠졌다 |
| CDR-25 | INFO | — | 10 | 배정·산출물 경로 정합: TASKS 행 없음, 초안 미추적, 산출물 경로가 초안 지시(`docs/reports/T03_DESIGN_REVIEW.md`)와 다르다 |

---

## 2. 항목별 상세

### 2.1 콘텐츠·Revision·Run·Job·Attempt 도메인 분리 — 조건부 적합

**적합한 점**
- 콘텐츠(정체성, 채널·유형 불변), revision(불변 사양), run(실행 단위), job(DAG 노드), attempt(lease 1회)를 분리했다. 이는 ADR-020(current_revision 소속 강제)과 ADR-022(상태 분리)에 부합한다.
- revision을 전부 append-only로 둔 것은 ADR-020의 "승인된 revision 수정 금지"보다 강한 조건으로, 문제가 없다.
- D02(채널·유형 불변)는 ADR-029의 LIFETIME 예산과 콘텐츠 단위 bucket이 콘텐츠의 채널·유형에 고정되는 구조와 일관된다.

**문제점**
- **CDR-02 (HIGH)** Revision이 *무엇의* 버전인지 정의되지 않았다.
  - 초안 revision 컬럼은 입력 사양뿐이다(topic, 목표 길이, settings_json).
  - docs/06은 "장면 수정 시 새 revision, 변경되지 않은 asset 재사용"이라고 한다. ADR-016은 `media_asset_usage(revision_id, scene_id)`, ADR-002는 "승인 revision/hash 일치 시에만 게시"라고 한다. 즉 revision이 *산출물을 포함한 버전*이라고 가정한다.
  - 같은 revision으로 run을 여러 번 하면 산출물이 run마다 다르다. 그런데 승인·게시 대상이 revision이면 어느 run의 산출물인지 특정할 수 없다.
  - "사용자가 장면만 다시 생성"(01_REQUIREMENTS)도 어느 run 결과를 기준으로 하는지 표현할 수단이 없다.
- **CDR-19 (LOW)** `content.title`과 `revision.working_title`이 둘 다 원천이다. 초안도 "제목의 제작 의미는 revision이 기준"이라고 하므로, 갱신 규칙이 없으면 값이 어긋난다.
- operation과 attempt의 소유 관계가 모호하다. 초안은 "attempt와 operation 연결의 소유권 보장"이라고 쓰지만, job_operation에 attempt 참조 컬럼이 없다. 재시도 시 같은 operation을 재사용하는지, 새로 만드는지도 정해지지 않았다.

**권장 해결책·설계 변경안**
1. 의미를 고정한다(ADR 신규).
   - **Revision = 사용자가 편집하는 불변 입력 사양.**
   - **Run = 특정 revision을 고정 pipeline으로 실행한 시도.**
   - **산출물(대본, 장면, asset usage)은 run에 귀속한다.**
2. 장면 편집·부분 재생성은 `content_revision.base_run_id`(nullable)를 가진 새 revision으로 표현한다. 새 run은 base run의 변경되지 않은 산출물을 `input_hash`로 재사용한다(CDR-03·T05). ADR-016의 `media_asset_usage`는 `(run_id, scene_no)` 기준으로 정의한다.
3. T06 승인 대상은 `(revision_id, run_id, manifest_hash)`로 정의한다. ADR-002의 "revision/hash"는 "revision + 해당 run의 manifest hash"로 해석을 명시한다.
4. `content.title`은 *현재 revision의 working_title 복제본*으로 둔다. current_revision_id를 갱신하는 같은 TX에서만 바꾸고, 별도 수정 API는 두지 않는다.
5. job_operation은 `(job_id, operation_seq)` UNIQUE와 `created_attempt_id`를 가진다.
   - "확정 미실행"으로 판정되어 재시도할 때만 같은 operation을 재사용한다(같은 provider 멱등 키).
   - "확정 실패 후 재시도"는 새 operation_seq를 만든다.

### 2.2 신규 테이블 컬럼·FK·인덱스·제약 — 조건부 적합

**적합한 점**: signed BIGINT, DATETIME(6) UTC, utf8mb4_unicode_ci, 키·hash utf8mb4_bin(ADR-053), `UNIQUE(content_id,id)`·`UNIQUE(run_id,id)` 복합 FK 패턴, 물리 삭제·cascade 금지.

**문제점과 실제 DDL 근거**
- **CDR-11 (MEDIUM)** 복합 FK에 필요한 대상 키가 없다.
  - revision↔channel_config 소유권 검증에 필요한 `channel_config UNIQUE(channel_id, id)`가 없다. 현재는 `uk_channel_config_revision (channel_id, revision_no)`만 있다(`V4__create_channel.sql:43`).
  - revision에 `channel_id` 컬럼이 없으므로 복합 FK를 걸 수 없다.
  - 예약의 콘텐츠·채널 일치: `budget_reservation.channel_id`가 nullable이라(`V5:140`) `(content_id, channel_id)` 복합 FK에는 CHECK가 함께 필요하다.
- job에 `content_id`, `channel_id`가 없다.
  - "콘텐츠별 실행 job 2개" 상한, 채널 PAUSED claim 제한, `GET /jobs?channelId=`가 모두 run→content→channel 조인을 요구한다. claim 경로의 조인은 SKIP LOCKED 후보 선택과 상한 계산을 복잡하게 만든다.
  - docs/03 `automation_job`의 `scene_id`, `stage`, `dedupe_key`(revision:stage:scene:inputHash)도 빠져 run 간 결과 재사용 근거가 사라졌다.
- **CDR-21 (LOW)** 인덱스 `job(status, available_at, priority, id)`와 예시 쿼리 `ORDER BY priority DESC, available_at, id`가 맞지 않는다. available_at 범위 조건 뒤에 priority 정렬이 오므로 filesort가 발생한다.
- **CDR-22 (LOW)**
  - 신규 테이블의 운영 권한이 정의되지 않았다. 현재 `infra/mariadb/prod-app-grants.sql`은 테이블별 최소 권한을 나열하고 `ProductionGrantsIT`가 이를 검증한다.
  - append-only 테이블(revision, dependency, event, operation_event)의 UPDATE/DELETE 금지 트리거 명세가 없다. V3~V5는 모두 트리거로 막는다.
  - ADR-053 `MigrationConventionTest.BINARY_COLLATION_COLUMNS`에 신규 bin 컬럼을 등록해야 한다는 점이 빠졌다.
- **CDR-23 (LOW)**
  - `content_revision.language VARCHAR(35)`는 `channel.language VARCHAR(16)` 및 정규식 CHECK(`V4:7`, `V4:17`)와 다르다.
  - revision_hash 정규화 규칙은 미정이다. 기존 `Fingerprints`(`budget/Fingerprints.java:16`)는 package-private이라 재사용하려면 `shared`로 옮겨야 한다.
- 예산 테이블 변경 범위
  - 초안은 "budget_policy는 유형별 기본 정책으로 유지"라고 한다. 이 경우 `ck_budget_policy_no_content`(`V5:78`)는 **유지**해야 하고, 해제 대상은 `ck_budget_bucket_no_content`(`V5:131`), `ck_budget_reservation_no_content`(`V5:163`) 두 개뿐이다. 초안 문장이 세 개 모두 바꾸는 것으로 읽히므로 명시가 필요하다.

**설계 변경안 (V6 이후, 실행 SQL 아님. 상세는 부록 A)**
- `V6`: `ALTER TABLE channel_config ADD CONSTRAINT uk_channel_config_owner UNIQUE (channel_id, id)` (DDL이라 기존 불변 트리거와 무관).
- `content`
  - `UNIQUE(id, channel_id)`, `UNIQUE(id, content_type)`
  - `active_run_id` nullable + `FK(id, active_run_id) → content_run(content_id, id)` (CDR-09)
  - `BEFORE UPDATE` 트리거로 `channel_id`, `content_type`, `created_by` 변경을 SIGNAL
  - 보관 CHECK `(status='ARCHIVED') = (archived_at IS NOT NULL)` — channel과 같은 형태
- `content_revision`
  - `channel_id` 복제 컬럼
  - `FK(content_id, channel_id) → content(id, channel_id)`
  - `FK(channel_id, channel_config_id) → channel_config(channel_id, id)`
  - `language VARCHAR(16)`, `revision_hash CHAR(64) COLLATE utf8mb4_bin`, `hash_version SMALLINT`, `base_run_id` nullable
  - UPDATE/DELETE 금지 트리거
- `automation_job` (§2.10 명칭)
  - `content_id`, `channel_id`(불변 복제, 복합 FK로 run과 일치 강제)
  - `job_type`, `job_key`, `input_hash CHAR(64) bin` (재사용 조회용, unique 아님)
  - `spawned_by_job_id` nullable (CDR-03)
  - `max_attempts`, `auto_retry_count`, `status_reason VARCHAR(64)`, `cancel_requested_at`
  - 인덱스 `(status, priority DESC, available_at, id)` — MariaDB 10.8+ 내림차순 인덱스. 로컬 11.8.2, CI 11.8.8 모두 해당. 실측은 미확인. T03에서 priority가 필요 없으면 컬럼을 빼고 `(status, available_at, id)`로 둔다.
  - `(content_id, status)` — 콘텐츠별 RUNNING 수 계산
  - CHECK `(status='RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)`
- `budget_reservation`: `CHECK (content_id IS NULL OR channel_id IS NOT NULL)` + `FK(content_id, channel_id) → content(id, channel_id)`. `budget_bucket`: `FK(content_id) → content(id)`.
- 신규 테이블 전부 `prod-app-grants.sql`에 추가한다(append-only는 SELECT/INSERT만). `ProductionGrantsIT` 기대값도 갱신한다.

### 2.3 상태 전이의 완전성 — 부적합(수정 필요)

**문제점**
- **CDR-01 (HIGH)** 의미가 반대로 충돌한다.
  - docs/08(`docs/08_AUTOMATION.md:2`): job 상태는 `PENDING,RUNNING,SUCCEEDED,RETRY_WAIT,FAILED,BLOCKED,CANCELLED`이고 "외부 불명 상태는 job **BLOCKED** + reason EXTERNAL_OUTCOME_UNKNOWN"이다.
  - 초안: BLOCKED는 "선행 대기", 외부 불명은 RECOVERY_REQUIRED다.
  - 같은 단어가 정반대 위험도를 가리키므로 UI·로그·운영 판단에서 오해가 생긴다(대기 중인 정상 job이 사람 개입이 필요한 것으로 보이거나, 그 반대).
- **CDR-04 (HIGH)**
  1. 취소 의도가 `CANCEL_REQUESTED` 상태와 `cancel_requested_at` 컬럼 두 곳에 있다. 초안은 "취소 의도는 복구 중에도 영속 보존"이라고 하는데, 상태가 RECOVERY_REQUIRED로 바뀌면 상태로서의 CANCEL_REQUESTED는 사라지므로 결국 컬럼에 의존한다. 두 표현은 어긋날 수 있다.
  2. 누락 전이:
     - **LEASE_EXPIRED**: worker 소멸. operation 상태에 따라 RETRY_WAIT 또는 RECOVERY_REQUIRED로 가야 하는데 행이 없다.
     - CANCEL_REQUESTED에서 확정 실패나 일시 오류가 발생하는 경우
     - RETRY_WAIT 중 run 취소
     - READY 중 채널 PAUSED(상태 유지인지 미정)
     - ACTION_REQUIRED 재개 시 attempt 한도 소진
  3. worker 완료 UPDATE의 "허용 상태" 조건에 CANCEL_REQUESTED가 들어가야 하는데 명시되지 않았다. 빠지면 취소 요청 후 정상 완료가 stale 처리되어 RECOVERY_REQUIRED로 잘못 빠진다.
- **CDR-10 (MEDIUM)**
  - Run 상태 목록(QUEUED/RUNNING/ACTION_REQUIRED/CANCEL_REQUESTED/terminal 3개)에 **RECOVERY_REQUIRED가 없다**. 그런데 DB 설계 절은 "RECOVERY_REQUIRED 포함 비종료 run은 활성"이라고 쓴다.
  - "미확정 operation 또는 사용자 조치가 있으면 ACTION_REQUIRED"는 D08이 분리하려던 두 상황을 run 수준에서 다시 합친다.
  - Content 상태 `DRAFT→QUEUED→PROCESSING→ACTION_REQUIRED/FAILED/CANCELLED`는 run 결과를 복제한다. 실패 run이 있어도 새 run을 만들 수 있으므로 "콘텐츠 FAILED"는 생명주기 상태가 아니다.
  - docs/08 content 상태(DRAFT, PRODUCING, REVIEW_REQUIRED, REVISION_REQUIRED, APPROVED, ARCHIVED, CANCELLED)와도 이름이 다르다. T03 fake run이 성공한 뒤의 콘텐츠 상태도 정의되지 않았다("실행 완료 표시 별도").

**권장 상태 기계 (상세 표는 부록 B)**
- **Job**: `WAITING`(선행 대기), `READY`, `RUNNING`, `RETRY_WAIT`, `ACTION_REQUIRED`, `RECOVERY_REQUIRED`, terminal `SUCCEEDED`, `FAILED`, `CANCELLED`, `SKIPPED`.
  - 취소 의도는 `cancel_requested_at`만 쓴다. RUNNING·RECOVERY_REQUIRED 상태는 그대로 두고, 해소 시점에 플래그를 보고 terminal을 고른다.
  - `status_reason`에 docs/08의 reason 체계를 보존한다(EXTERNAL_OUTCOME_UNKNOWN, BUDGET_EXCEEDED, LEASE_EXPIRED_BEFORE_DISPATCH, RUN_FAILED_FAST 등).
- **Run**: `QUEUED`, `RUNNING`, terminal `SUCCEEDED`, `FAILED`, `CANCELLED`, 그리고 `cancel_requested_at`.
  - ACTION_REQUIRED·RECOVERY_REQUIRED는 run 상태로 두지 않고, job 상태의 집계(`attention` 요약: 개수)로 API에서 제공한다.
  - run은 *모든 job이 terminal일 때만* terminal이 된다. 미확정 job이 남아 있으면 RUNNING을 유지하고, 활성 run guard도 유지한다(D01 의도 보존).
  - 이렇게 하면 집계 상태와 원천 상태가 어긋날 여지가 줄어든다.
- **Content**: T03은 docs/08의 부분집합 `DRAFT`(현재 revision에 활성 run 없음), `PRODUCING`(활성 run 있음), `ARCHIVED`만 사용한다.
  - 마지막 run 결과는 run 배지로 따로 보여준다.
  - `REVIEW_REQUIRED`, `REVISION_REQUIRED`, `APPROVED`는 T06에서 활성화한다.
  - docs/08의 content `CANCELLED`는 run 결과와 겹치므로 폐기를 제안한다(ADR 필요).
- **채널 상태와의 관계**
  - 채널 PAUSED: 새 claim을 하지 않는다. RUNNING은 끝까지 진행한다. READY·RETRY_WAIT는 상태를 유지하고 claim 대상에서만 빠진다.
  - 채널 ARCHIVED: 새 run을 금지한다. 활성 run에는 취소 의도를 기록한다(자동 기록할지는 사용자 결정, 권장: 보관 요청 시 활성 run이 있으면 409로 거부).

### 2.4 DAG 의존성 및 순환 방지 — 조건부 적합

**적합한 점**
- run 생성 TX에서 코드 정의 pipeline으로 노드와 간선을 한 번에 만들고 위상 정렬로 순환, 자기참조, 누락을 거부한다.
- `(run_id, job_id)`·`(run_id, predecessor_job_id)` 복합 FK로 cross-run 간선을 차단한다.
- 동적 DAG 변경은 T03에서 제외했다.

**문제점**
- **CDR-03 (HIGH)** docs/06 파이프라인은 STORYBOARD 이후 **장면별** GENERATE_MEDIA를 요구하고, 장면 수는 STORYBOARD 결과로만 정해진다(롱폼 8~12분이면 수십 장면). "run 생성 시 고정된 job set"으로는 T05를 구현할 수 없다.
  - T04/T05에서 이 제약을 처음 깨면 job_dependency 불변성, 순환 검증 위치, 노드 상한, 진행률 계산(분모 변경)을 모두 다시 설계해야 한다.
- **CDR-08 (MEDIUM)** fan-in 승격 경쟁이 미정이다. 선행 A와 B가 동시에 완료되면 각 TX가 READ COMMITTED에서 상대의 미커밋 상태를 보고 "아직 미완료"로 판단해, 후행 C가 영원히 WAITING에 남을 수 있다. 초안은 reconciler 회복만 언급하고 1차 방지책을 정의하지 않았다.
- 순환 검사는 앱 TX 안에서만 한다. 간선이 생성 TX에서만 INSERT되고 이후 불변이라는 전제가 DB에 강제되어 있지 않다(트리거 없음).

**권장 해결책·설계 변경안**
1. **제한된 확장 노드**(T03 스키마 예약, 구현 T04/T05)
   - pipeline 정의에 `EXPANDER`(예: STORYBOARD)와 `BARRIER`(예: SUBTITLE 직전 MEDIA_BARRIER)를 둔다.
   - EXPANDER의 성공 TX만 같은 run 안에 자식 job을 추가할 수 있다. 자식의 선행은 `{EXPANDER}`, 후행은 `{BARRIER}`로만 연결한다.
   - BARRIER가 WAITING일 때만 간선 추가를 허용한다. 새 노드는 기존 노드의 조상이 될 수 없으므로 **구성상 순환이 불가능**하다.
   - `spawned_by_job_id`, run별 `max_jobs`(예: 500), `max_edges`(예: 2000)를 둔다. 수치는 T05에서 측정 후 확정한다.
   - 진행률 분모는 확장 후 확정된다고 UI에 표시한다.
2. 승격은 **후행 job 행을 잠근 뒤** 선행 상태를 다시 읽는다. 다만 아래 3의 run 잠금으로 모든 상태 전이를 run별로 직렬화하면 이 경쟁은 자동으로 사라진다. 권장은 3이다.
3. 큐 전역 잠금 순서(ADR-055와 겹치지 않는 별도 계층): **content → content_run → automation_job(id 오름차순) → attempt/event INSERT**.
   - heartbeat는 job 단일 행 조건부 UPDATE만 하고 run을 잠그지 않는다(상태 불변).
   - claim은 §2.6의 3단계 프로토콜로 순서 역전을 피한다.
   - 큐 TX 안에서는 예산 서비스를 호출하지 않는다(`LockRetryingTransactions.java:36-38`이 이미 강제한다).
4. `job_dependency` UPDATE/DELETE 금지 트리거를 둔다. 애플리케이션 시작 시 모든 pipeline 정의의 위상 정렬을 검증해 fail-fast한다(단위 테스트 포함).
5. 선행 결과 전달은 가변 `output_json`이 아니라 불변 결과 행으로 한다(예: `job_result(job_id PK, result_hash, summary_json, created_at)`, UPDATE 금지). 이전 run 결과와 섞이는 것은 복합 FK `(run_id, job_id)`로 차단한다.

### 2.5 Quartz와 DB 작업 큐 역할 분리 — 조건부 적합

**적합한 점**: "DB가 상태의 유일한 원천, Quartz는 트리거만"은 ADR-001·ADR-022·docs/08과 일치한다. 자동 스키마 초기화 금지, Flyway 관리도 일치한다.

**문제점 (CDR-12, MEDIUM)**
- poll 1초, heartbeat 15초, sweep 10초를 모두 Quartz 트리거로 처리하면 맞지 않는다.
  - JDBC JobStore는 fire마다 QRTZ_* 행을 갱신하므로 1초 주기는 쓸모없는 DB 쓰기다.
  - misfire 정책에 따라 재기동 직후 몰아서 실행될 수 있다.
  - 실제 정렬 기준은 DB 큐이므로 Quartz가 주는 가치가 없다.
- T03에는 *지속되어야 하는 일정*이 없다. 사람이 run을 명시적으로 만들고, 예약 제작은 P1(T09)이다. JDBC JobStore를 지금 도입하면 QRTZ_* migration, MigrationConventionTest collation 규칙 적용, 운영 권한(Quartz는 DELETE 필요), 비클러스터 설정 검증 비용만 생긴다.
- 현재 의존성에 Quartz가 없다(`backend/build.gradle.kts`, `gradle/libs.versions.toml`에서 미발견). Boot 4.1.3 BOM의 quartz 2.5.2는 확인했다(T01 조사 결과).

**권장 해결책 (사용자 선택 필요)**
- **권장 A (ADR-022 시점 조정 필요)**:
  - dispatcher poll과 heartbeat는 전용 스레드(`ScheduledExecutorService`, graceful shutdown 연동)로 처리한다.
  - lease sweep과 reconciler는 Spring 스케줄러(단일 인스턴스, 동시 실행 금지)로 처리한다.
  - Quartz JDBC JobStore는 최초의 지속 일정 기능(T09 예약 제작 또는 T08 예약 게시)과 함께 도입한다.
  - 이때 sweep은 docs/08의 "lease 회수는 Quartz" 문장을 바꿔야 하므로 ADR 갱신이 필요하다.
- **대안 B (ADR 그대로)**:
  - T03에 Quartz JDBC JobStore(`isClustered=false`, `initialize-schema=never`, `org.quartz.jobStore.misfireThreshold` 명시, 스케줄러 시간대 UTC)를 도입한다.
  - sweep과 reconciler만 `@DisallowConcurrentExecution` + misfire `DO_NOTHING` Quartz job으로 처리한다. poll과 heartbeat는 A와 같이 전용 스레드로 처리한다.
  - QRTZ_* DDL은 Quartz 2.5.2 배포본 `tables_mysql_innodb.sql`을 출처·체크섬과 함께 기록하고 ADR-014 collation 규칙에 맞게 옮긴다.
- 어느 쪽이든 Quartz job은 **DB 큐 상태를 직접 판정하지 않고** 큐 서비스의 같은 전이 함수를 호출한다(전이 로직 단일화).

### 2.6 Lease·Heartbeat·Worker 실행 권한 — 조건부 적합

**적합한 점**
- 모든 갱신이 `id + lease_token + 허용 상태 + lease_expires_at > DB UTC` 조건식과 `affected rows = 1` 검사를 쓴다.
- 만료되지 않은 lease는 강제로 탈취하지 않는다.
- "외부 부작용은 token만으로 막을 수 없다"를 명시하고, DISPATCHING 이후에는 재호출 없이 RECOVERY_REQUIRED로 보낸다.
- lease 60초/heartbeat 15초는 heartbeat 4회 누락 여유가 있어 합리적이다.

**문제점**
- **CDR-14 (MEDIUM)** heartbeat가 "worker 실행과 독립 스케줄러"이므로, worker 스레드가 데드락이나 무한 대기에 빠져도 heartbeat는 계속 lease를 연장한다. job은 영원히 RUNNING이 되고 sweep도 회수하지 못한다.
- **CDR-13 (MEDIUM)** D06은 "단일 인스턴스"를 가정만 할 뿐 강제하지 않는다. 같은 DB에 두 프로세스가 붙는 경우는 현실적이다(로컬 `bootRun` 두 개, Compose app + 로컬 bootRun).
  - 그러면 worker 2개 상한과, 메모리 permit으로 구현할 경우의 "콘텐츠별 RUNNING 2개" 상한이 깨진다.
  - fencing 덕분에 상태 오염은 막히지만, 외부 호출 동시성 상한(T07 비용·quota)이 깨진다.
- **CDR-16 (MEDIUM)** T02 예산은 앱 `Clock`(`BudgetReservationService.java:167,173`)을 쓰고, RV-07 링크 검사도 같은 시계로 저장한 `created_at`을 비교한다. 초안 큐는 DB `UTC_TIMESTAMP(6)`을 쓴다. 두 원천을 섞어 비교하는 쿼리가 생기면 시계 차이만큼 판정이 틀어진다(예: lease 만료 판단에 앱이 넣은 `available_at`을 섞는 경우).
- claim과 run 잠금 순서(CDR-08). 예시 쿼리처럼 job을 `FOR UPDATE SKIP LOCKED`로 먼저 잠그고 run을 잠그면 job→run 순서가 된다. 완료나 취소 경로가 run→job 순서이면 데드락이 생길 수 있다.

**권장 해결책·설계 변경안**
1. attempt에 `execution_deadline_at`을 둔다(job_type별 최대 실행 시간, pipeline 정의에 고정).
   - heartbeat 조건식에 `UTC_TIMESTAMP(6) < execution_deadline_at`을 추가해, 기한을 넘기면 연장하지 않는다.
   - heartbeat 스레드는 worker의 진행 신호(마지막 progress 시각)가 일정 시간 없으면 연장을 멈춘다.
2. 상한은 **DB에서 강제**한다.
   - claim TX가 run 행을 잠근 상태에서 `content_id`별 RUNNING 수를 세어 상한을 검사한다(job의 `content_id` 비정규화, `(content_id, status)` 인덱스).
   - 전역 worker 상한은 프로세스별이라고 명시한다.
   - 추가로 `worker_instance(instance_id, started_at, heartbeat_at)` 등록 행으로 두 번째 활성 인스턴스를 *탐지·경고*한다. 차단까지 할지는 사용자 결정 사항이다.
3. **claim 3단계 프로토콜**
   1. 잠금 없는 후보 조회(`status='READY' AND available_at <= UTC_TIMESTAMP(6)`, 소량 batch)
   2. 후보의 run 행을 `FOR UPDATE SKIP LOCKED`로 잠근다(바쁜 run은 건너뜀).
   3. job 행 `FOR UPDATE` 후 상태, 의존성, 취소, 상한, 채널 상태를 다시 확인하고 claim을 기록한다.
   - 채널 상태 확인은 *잠금 없는 읽기*로 충분하다. 실제 지출 차단은 예산 예약 TX가 `budget_control S → channel S` 잠금 안에서 다시 검사하기 때문이다(`BudgetReservationService.java:140-164`, ADR-042).
   - 이렇게 하면 큐 TX가 channel이나 budget_control을 잠그지 않아 ADR-055 잠금 계층과 섞이지 않는다.
4. 시각 규칙(ADR 신규)
   - **큐의 lease·available_at·heartbeat 비교는 DB 시각만 쓴다**(SQL 안에서 `UTC_TIMESTAMP(6) + INTERVAL`).
   - 예산은 기존 앱 Clock을 유지한다.
   - 두 시각 축을 같은 비교식에서 섞지 않는다.
   - 테스트는 DB 시각을 제어할 수 없으므로 lease 길이를 짧게 설정하는 방식과 barrier·latch를 쓴다.
5. fencing 토큰은 UUID `lease_token`에 더해 단조 증가하는 `attempt_no`를 함께 조건에 넣는다. 로그와 이벤트로 "몇 번째 lease"인지 추적할 수 있다.

### 2.7 작업 재시도·취소·장애 복구 — 조건부 적합

**적합한 점**
- 일시 오류(DB 잠금, 예산 판정 재계획 소진 503 — `BudgetReservationService.java:113-117`)와 비즈니스 오류(BUDGET_EXCEEDED, POLICY_VIOLATION, PREREQUISITE_NOT_MET)를 분리했다. 비즈니스 오류는 자동 재시도하지 않는다(T03_PLAN 제약 5).
- 외부 결과 불명은 무조건 RECOVERY_REQUIRED로 보낸다. 종료 시 예약을 자동 해제하지 않는다.
- fake executor에 응답 유실 모드가 있다.

**문제점**
- **CDR-15 (MEDIUM)**
  - docs/08은 "기본 최대 3회(제안)", D07은 "최초 포함 5회"를 쓴다.
  - "관리자가 재개해도 기존 attempt 기록 유지; 한도 초과 시 새 run" 규칙대로라면, BUDGET_EXCEEDED로 ACTION_REQUIRED가 된 job은 attempt를 이미 소모했으므로 한도를 올린 뒤 재개하면 즉시 한도 초과가 될 수 있다.
  - 정책이나 설정 변경이 진행 중인 job의 max에 적용되는지도 미정이다.
- **CDR-05 (HIGH)** provider가 결과 조회를 지원하지 않거나 조회해도 불명이면 RECOVERY_REQUIRED를 끝낼 방법이 없다.
  - 초안 UI는 "강제 성공·해제 없음", API는 "no arbitrary forced status"다.
  - docs/05는 "조회 불가 시 사람 확인", 기존 T02는 `POST /budget-reservations/{id}/resolve`(CHARGED/NOT_CHARGED + note)로 사람의 판정을 이미 허용한다(`BudgetControllers.java:274-292`).
  - 결과적으로 활성 run guard(D01) 때문에 **콘텐츠가 영구적으로 잠길 수 있다**.
- **CDR-20 (LOW)**
  - `POST /jobs/{id}/cancel`(job 단위 취소)이 DAG에서 무엇을 뜻하는지 불명하다. 후행 SKIPPED → run FAILED인가, 해당 job만 생략하고 계속인가.
  - `POST /jobs/{id}/retry`는 이름상 FAILED 재시도로 읽히지만 상태표는 terminal 재개를 금지한다. docs/04 기준선에도 `/jobs/{id}/retry`가 있다.

**권장 해결책·설계 변경안**
1. 시도 횟수
   - job 행에 `max_attempts`를 pipeline 정의에서 복사해 **고정 저장**한다(실행 중 설정 변경 영향 차단).
   - 기본값은 docs/08에 맞춰 **최초 포함 3회**를 권장한다. 5회는 fake/internal job에만 둔다. T07 유료 job은 별도 값(예: 2)으로 정하고 T07에서 승인한다.
   - `attempt_count`(모든 lease)와 `auto_retry_count`(자동 재시도)를 분리한다. 한도는 자동 재시도에만 적용한다.
   - 관리자 재개(ACTION_REQUIRED → READY)는 감사 기록과 함께 1회의 새 시도를 허용한다. 횟수 한도를 우회하지는 않고, 재개 횟수도 별도로 상한(예: 3)을 둔다.
   - 재개 전 전제(예산 여유, 지출 허용, 채널 ACTIVE)를 서버가 미리 검사하되, 실제 판정은 예약 TX에서 한다.
2. **사람 판정 경로**(CDR-05)
   - `POST /operations/{id}/attestations`를 둔다. 입력은 `outcome = NOT_EXECUTED | EXECUTED_CHARGED | EXECUTED_NOT_CHARGED`, `evidence(externalRef)`, `note` 필수, `Idempotency-Key`다.
   - 처리: ① 영속 operation에 판정을 append한다 ② 예산 정산을 기존 서비스(confirm/release)로 실행한다(event_key는 operation 기준 고정) ③ job 전이(성공 증거 없이 SUCCEEDED 금지 → EXECUTED_*는 산출물이 없으면 FAILED, NOT_EXECUTED는 재시도 가능 시 READY)를 순서대로 한다.
   - "강제 성공"은 금지하되 "증거와 함께 사람이 판정"하는 것은 허용한다고 문구를 바꾼다. 이 판정은 자동 검수를 대체하지 않는다(AGENTS).
3. 취소
   - T03 API는 **run 단위 취소만** 제공한다. job 단위 취소는 제외한다.
   - 실행 중이 아닌 job은 CANCELLED, RUNNING과 RECOVERY_REQUIRED는 플래그만 설정한다. 미확정이 모두 해소되면 run CANCELLED로 간다.
   - 이미 발생한 비용은 보존한다(docs/08).
4. `/jobs/{id}/retry`는 docs/04 기준선 경로를 유지하되 의미를 **resume**(ACTION_REQUIRED 재개 전용)으로 한정한다. RECOVERY_REQUIRED 해소는 attestation 경로로만 한다. terminal job은 409 INVALID_STATE_TRANSITION으로 처리한다.
5. 재시작 복구 순서
   1. 기동 시 claim을 멈춘다.
   2. 미확정 operation을 진단한다.
   3. 만료된 lease를 sweep한다.
   4. run 집계 reconciler를 실행한다.
   5. claim을 재개한다.
   - 이 순서를 부트 시퀀스로 고정하고 테스트한다(Q06).

### 2.8 T04~T10 확장 가능성 — 부적합(사전 결정 필요)

| 단계 | T03 결정에 묶인 요구 | 초안 상태 | 권장 |
|---|---|---|---|
| T04 조사·대본·스토리보드 | 산출물 저장 위치, 주장·근거(ADR-015 content_claim 등, docs/03 "T03~T04") | 미정 | 산출물 run 귀속(CDR-02). claim 테이블은 T04 범위라는 deferral을 ADR로 명시 |
| T04 FR02 소재 등록·중복 검사 | docs/03 `topic(channel_id, normalized_hash) UNIQUE`, 구현 "T03" | 초안에서 제거됨(revision.topic TEXT) | **CDR-17**: topic 테이블을 T03에 포함하거나 T04로 연기하는 결정 필요. 권장은 T03에 최소 `topic` 테이블 + `content.topic_id` nullable(중복 검사의 DB 근거 확보) |
| T05 장면별 미디어 | 장면 수만큼 job 확장, asset 재사용(`input_hash`), media_asset_usage(ADR-016) | 고정 DAG, dedupe_key 제거 | CDR-03 확장 노드 + `input_hash` |
| T06 검수·승인 | 승인 대상 revision+manifest, 수정 시 승인 무효화 | 미정 | `(revision_id, run_id, manifest_hash)` 승인. 새 revision이 생기면 콘텐츠 상태를 DRAFT로 되돌리고 기존 승인은 그 revision에만 남음 |
| T07 실제 provider | 외부 요청 이력 | job_operation과 docs/03 `ai_request_log(job_id, request_key UNIQUE, external_id, usage_json)` 중복 | **CDR-18**: job_operation을 "외부 부작용 의도와 결과의 원천"으로, ai_request_log는 `operation_id` FK를 가진 provider별 상세로 정의 |
| T08 게시 | publish 상태 독립(ADR-018) | 독립 유지 | 적합. 게시 작업은 별도 publish_job이며 automation_job으로 실행하지 않는다고 명시 권장 |
| T09 예약 제작 | 지속 일정 | Quartz 범위 미정 | CDR-12. 일정 발화 ID에서 파생한 멱등 키로 run 생성 API 재사용 |
| T10 멀티채널 병렬 | 채널별 동시성 상한 | job에 channel_id 없음 | job `channel_id` 비정규화 + 채널별 상한을 claim TX에서 검사할 수 있는 인덱스 |

### 2.9 관리자 UI 및 API 설계 — 조건부 적합

**적합한 점**
- `/api/v1`, ADMIN 세션 + CSRF, 19개 오류 코드, ID·금액 문자열, 평탄 스키마(ADR-062), 서버 정렬 + id tie-break, 직접 status PATCH 금지
- 진행률 계산 규칙(실패·취소·생략을 성공으로 세지 않음), "미확정을 실패로 단정하지 않음"
- 세션 세대(generation) 보호 재사용(ADR-066), 디자인 시스템(ADR-063) 준수

**문제점**
- **CDR-07 (MEDIUM)** 초안은 `requestKey`를 요청 본문에 둔다. 기존 계약은 다르다.
  - `docs/api/openapi.yaml:18`: "관리자 정산 조치는 `Idempotency-Key` 헤더"
  - `components.parameters.IdempotencyKey`(`openapi.yaml:862`)
  - `docs/04_API_SPEC.md`: "POST job/publish에 Idempotency-Key"
  - 실제 컨트롤러도 헤더로 받는다(`BudgetControllers.java:274-306`).
  - 두 방식이 섞이면 프론트 공통 클라이언트, 생성 타입, 계약 테스트가 둘로 갈라진다.
- 버전 필드 이름: 초안은 `expectedVersion`이다. 기존 `VersionRequest`는 `version`(integer, `openapi.yaml:1003` 부근)이고, 채널 설정은 `baseRevisionNo`를 쓴다.
- **CDR-24 (LOW)**
  - `POST /contents`의 `channelConfigId`(내부 PK)는 기존 API가 노출하는 `revisionNo` 체계와 다르다.
  - `POST /contents/{id}/runs`의 `pipelineVersion`을 클라이언트가 고르게 하면, 서버가 승인하지 않은 pipeline으로 실행할 수 있다.
  - 대시보드 확장(대기·진행·실패·복구 필요 개수)에 해당하는 API가 표에 없다. 기존 `GET /dashboard/summary` 스키마를 확장해야 한다.
- docs/04 기준선 `POST /contents/{id}/produce`가 `POST /contents/{id}/runs`로 대체되는데, 그 사실이 명시되지 않았다.
- 정산 operation과 기존 직접 API의 관계: "기존 resolve/adjustment 우회 차단"이 *엔드포인트 제거*를 뜻하면 T02 UI가 깨진다.

**권장 해결책·설계 변경안**
1. T03의 모든 비멱등 POST(콘텐츠·revision·run 생성, run 취소, resume, attestation, settlement operation 생성)는 **`Idempotency-Key` 헤더**를 쓴다(기존 파라미터 `$ref` 재사용). 키는 서버에서 `request_key`(bin)와 fingerprint로 저장한다.
   - 최초 생성은 201, 같은 키·같은 내용 replay는 같은 리소스를 200으로 돌려준다. 다른 내용은 409 RESOURCE_CONFLICT. 기존 예약 replay 규칙과 같다.
2. optimistic lock은 기존 `VersionRequest.version`을, revision 생성은 `baseRevisionNo`를 쓴다(채널 설정과 같은 패턴).
3. `POST /contents`는 `channelConfigRevisionNo`(선택)를 받는다. 생략하면 서버가 현재 채널 설정 revision에 고정하고, 지정했는데 최신이 아니면 409를 준다.
4. `pipelineVersion`은 요청에서 제거하고 서버가 content_type별 현재 pipeline으로 정한다. fake 실행 모드는 API로 노출하지 않고 `AI_STUDIO_FAKE_EXECUTOR_*` 설정(ADR-013 접두사)과 test·local profile 한정으로 둔다.
5. 기존 `/resolve`·`/adjustments`는 경로와 스키마를 유지하고, 내부에서 settlement_operation을 만들고 실행하는 얇은 래퍼로 바꾼다(헤더 `Idempotency-Key` = creation key). 우회 경로는 사라지고 T02 UI는 호환된다. 이 방향은 ADR과 OpenAPI 승인 대상이다.
6. 추가 API(OpenAPI 0.4.0 후보)
   - `GET /dashboard/summary`에 production 개수 필드를 추가(기존 필드 불변)
   - `GET /runs/{id}`에 `attention{actionRequired, recoveryRequired}`
   - `POST /operations/{id}/attestations`(CDR-05)
7. UI
   - run 생성은 비용을 예약할 수 있는 위험 작업이므로 확인 대화상자(AGENTS UI 규칙)를 쓴다.
   - fake run 결과에는 "모의 실행" 배지를 붙인다(실제 영상·비용으로 오인 방지).
   - RECOVERY_REQUIRED 화면은 조회(operation, 예약, 원장)와 판정 실행을 분리하고, 판정은 증거 입력을 필수로 한다.
   - 확장 노드 이후 진행률 분모가 바뀔 수 있다고 표시한다.

### 2.10 기존 ADR과 신규 설계 결정의 충돌 여부 — 충돌 있음

| 기존 결정·문서 | 초안 | 충돌·영향 | 권장 |
|---|---|---|---|
| docs/08 job 상태 `PENDING…BLOCKED(EXTERNAL_OUTCOME_UNKNOWN)` | WAITING을 BLOCKED로, 외부 불명을 RECOVERY_REQUIRED로 | **의미 반전**(CDR-01) | ADR 신규로 docs/08 상태 재정의, 이름 `WAITING`·`RECOVERY_REQUIRED`·`status_reason` |
| docs/08 content 상태 7종 | QUEUED/PROCESSING/FAILED/… | 이름·의미 불일치(CDR-10) | T03은 DRAFT/PRODUCING/ARCHIVED 부분집합, CANCELLED 폐기 |
| docs/03·docs/08 `automation_job`, `dedupe_key`, `next_run_at`, `stage`, `scene_id` | `job`, `job_key`, `available_at` | 명칭 변경(초안도 "우선 검토" 언급) | 테이블명은 기준선 `automation_job` 유지. 컬럼은 `available_at`(의미 명확)을 쓰고 docs/03 갱신을 ADR로. `dedupe_key`는 `UNIQUE(run_id, job_key)` + `input_hash`로 대체 |
| docs/08 재시도 "기본 최대 3회(제안)" | D07 5회 | 수치 충돌(CDR-15) | job별 고정 저장, 기본 3 |
| docs/04 `POST /contents/{id}/produce`, `POST /jobs/{id}/retry`, `Idempotency-Key` | `/runs`, `/retry`, 본문 requestKey | 경로 대체 미명시, 키 전달 불일치(CDR-07) | `/runs`로 대체를 docs/04에 기록, 헤더 유지 |
| docs/03 `topic`(T03), `content_scene`(T03~T04), ADR-015(T03~T04), ADR-016(T03~T05) | 미포함 | 범위 결정 누락(CDR-17) | ADR로 T03 포함·연기 범위 명시 |
| ADR-022 Quartz JDBC JobStore는 Flyway 관리(구현 T03) | 그대로 | 충돌 아님, 도입 비용·시점 문제(CDR-12) | 대안 A(시점 조정, ADR 갱신) 또는 B(ADR 그대로) 사용자 선택 |
| ADR-054 Fail Closed는 SYSTEM DAY·MONTH만 필수 | D09: CONTENT_TYPE LIFETIME도 필수 | **기존 결정의 범위 확장**. T02에서 만들어 둔 CONTENT_TYPE 정책이 처음으로 실제 적용됨(CDR-06) | 신규 ADR로 명시. 적용 시 기존 정책의 전역·채널별 행이 모두 적용됨을 사용자에게 고지 |
| ADR-055 잠금 순서(예산) | 큐 잠금 순서 "A에서 단일화" | 미정 | 큐 계층 `content → content_run → automation_job(id)`, 예산 계층과 TX 분리(§2.4) |
| ADR-053 bin 컬럼 허용 목록 | 신규 키·hash bin | 테스트 목록 갱신 필요(CDR-22) | 신규 컬럼 명시 |
| ADR-026·058 운영 앱 계정 최소 권한 | 언급 없음 | 신규 테이블 권한 누락(CDR-22) | prod-app-grants.sql·ProductionGrantsIT 갱신 |
| ADR-002 승인 revision/hash 일치 시 게시 | revision이 입력 사양 | 해석 필요(CDR-02) | revision + run manifest hash로 명시 |
| ADR-020 current_revision 소속 FK | 동일 | 충돌 없음 | — |
| ADR-029 콘텐츠 FK·콘텐츠 단위 예약 T03 | 동일 | 충돌 없음 | — |
| ADR-013 `AI_STUDIO_` 접두사 | 동일 | 충돌 없음 | — |
| ADR-024 격리 테스트 DB | 동일 | 충돌 없음 | — |
| ADR-062 평탄 스키마 | 동일 | 충돌 없음 | — |

**CDR-06 상세 (MEDIUM, 비용 영역은 Codex 검토 대상이지만 도메인 범위 산정에 필요해 사실만 기록)**
- `BudgetMapper.lockActivePolicies`(`BudgetMapper.java:143-153`)는 `scope = 'SYSTEM'`과 해당 채널 `CHANNEL`만 잠근다. **CONTENT_TYPE 정책은 예약에 전혀 반영되지 않는다.**
- `bucketByStart`·`bucketCovering`(`BudgetMapper.java:236-244`)은 `subject_key = 0`이 고정이다.
- `PeriodCalculator.periodAt`은 LIFETIME이면 예외를 던진다(`PeriodCalculator.java:47-48`).
- `ReserveCommand`에 contentId가 없고 fingerprint의 `contentId`도 null 고정이다(`BudgetReservationService.java:57-59, 99`).
- 따라서 D09는 "검사 SQL 확장"만이 아니다. 정책 선택, bucket 조회·생성, 기간 계산, fingerprint, `reservationBucketIssues`(`BudgetMapper.java:502-542`, `valid_link` 조건)를 함께 바꾸는 작업이다.
- LIFETIME bucket 기간은 **고정 sentinel**(`period_start = '1970-01-01 00:00:00'`, `period_end = '9999-12-31 00:00:00'`, `timezone='UTC'`)을 권장한다. `uk_budget_bucket_period (policy_id, subject_key, period_start)`(`V5:127`)만으로 "정책+콘텐츠당 정확히 1개"가 보장되고, 링크 검사 `b.period_start <= r.created_at < b.period_end`도 그대로 성립한다.
- 정책 revision 변경 시 열린 bucket 한도를 갱신하는 기존 동작(`BudgetPolicyService.java` `lockOpenBuckets`, `period_end > now`)과도 맞는다. 이 판단은 실행으로 확인하지 않았다.

---

## 3. 초안 결정(D01~D12) 판정

| ID | 판정 | 조건·수정 |
|---|---|---|
| D01 콘텐츠당 활성 run 1개 | 승인 권장 | `content.active_run_id` 포인터로 구현(CDR-09). 미확정 run이 남으면 유지. CDR-05 판정 경로가 함께 있어야 영구 잠김을 피함 |
| D02 채널·유형 불변 | 승인 권장 | BEFORE UPDATE 트리거로 DB 강제 |
| D03 5계층 분리 | 조건부 | CDR-02 산출물 귀속 결정과 함께 승인. 테이블명 `automation_job` |
| D04 고정 DAG | **수정 후 승인** | CDR-03 제한된 확장 노드 규칙을 T03 스키마에 예약 |
| D05 lease 60s / heartbeat 15s | 승인 권장 | `execution_deadline_at` 추가(CDR-14), DB 시각 규칙(CDR-16) |
| D06 worker 2, 단일 인스턴스 | 조건부 | 상한은 DB 강제, 인스턴스 등록·탐지(CDR-13) |
| D07 5회 | **수정 권장** | 기본 3회, job별 고정, 자동·수동 횟수 분리(CDR-15) |
| D08 ACTION/RECOVERY 분리 | 승인 권장 | job 수준만. run은 attention 집계(CDR-10). 이름 충돌은 CDR-01로 정리 |
| D09 유형별 필수 LIFETIME | 조건부(Codex 비용 검토와 합산 판단) | ADR-054 확장 명시, 구현 범위 재산정(CDR-06), sentinel 기간 |
| D10 영속 operation + 분리 TX + reconciler | 방향 적합(세부는 Codex 검토 대상) | operation_seq·attempt 소유(§2.1), attestation(CDR-05), ai_request_log 관계(CDR-18) |
| D11 P-T02-05를 T03에서 구현 | 승인 권장 | 기존 `/resolve`·`/adjustments`를 래퍼로 유지해 우회 차단과 호환을 동시에 충족(§2.9-5) |
| D12 T02 UI 재사용 | 승인 권장 | 위험 작업 확인 대화상자, 모의 실행 표시 |

## 4. 신규 결정 후보 (ADR 번호는 DECISIONS 최신 ADR-066 이후 사용자 승인 시 배정)

| 후보 | 내용 | 관련 |
|---|---|---|
| N-01 | Revision=불변 입력 사양, 산출물은 run 귀속, 승인은 revision+run manifest | CDR-02, ADR-002·016 |
| N-02 | job 상태 집합 재정의(WAITING, RECOVERY_REQUIRED, status_reason), 취소는 플래그 | CDR-01·04, docs/08 |
| N-03 | Run 상태 최소화 + attention 집계, Content T03 상태 부분집합 | CDR-10 |
| N-04 | 제한된 확장 노드(EXPANDER→children→BARRIER) | CDR-03 |
| N-05 | 큐 잠금 계층과 claim 3단계 | CDR-08 |
| N-06 | 큐 시각은 DB 시각, 예산은 앱 Clock(비교식 혼용 금지) | CDR-16 |
| N-07 | Quartz 도입 시점(대안 A/B) | CDR-12, ADR-022 |
| N-08 | 시도 횟수 정책(job별 고정, 기본 3, 자동·수동 분리) | CDR-15 |
| N-09 | 사람 판정(attestation) 경로 | CDR-05 |
| N-10 | T03 비멱등 POST는 Idempotency-Key 헤더 | CDR-07 |
| N-11 | topic·content_scene·claim·media의 T03 포함·연기 범위 | CDR-17 |
| N-12 | job_operation ↔ ai_request_log 관계 | CDR-18 |

## 5. 남은 위험 및 미확인 사항

- **미확인**: 내림차순 인덱스와 `SKIP LOCKED` 조합의 실제 실행 계획. 근거는 MariaDB 버전 지원일 뿐이며, 실측(EXPLAIN, 경쟁 claim)은 T03-B·C의 격리 DB에서 해야 한다.
- **미확인**: LIFETIME sentinel 기간이 `consistencyIssues`의 `BUCKET_REVISION_MISMATCH`(`period_end > now`)와 `lockOpenBuckets`에서 의도대로 동작하는지. 코드 대조로만 판단했다.
- **미확인**: Quartz 2.5.2 `tables_mysql_innodb.sql`의 실제 DDL과 MigrationConventionTest 규칙의 호환성(대안 B를 택할 때).
- Codex 비용·동시성 검토와 결론이 다를 수 있는 영역: D09·D10 세부(트랜잭션 경계 TX1~TX7, reconciler 판정), 잠금 순서. 이 보고서는 도메인 경계 관점의 권장만 담았으며, 두 검토의 합산 판단은 사용자·설계 담당에게 맡긴다.
- 초안은 "최신 main의 실제 코드를 검증하지 못했다"고 스스로 밝혔다. 이 보고서는 `47ef697` 기준으로 대조했으므로, 이후 main이 바뀌면 근거 행 번호를 다시 확인해야 한다.

## 6. 작업 관리 메모 (CDR-25, INFO)

- `TASKS.md`에 T03-A1(Claude 설계 검증)과 Codex 비용·동시성 검토 행이 없다. T03은 "미배정·BLOCKED"다. 통합 담당자가 배정을 기록해야 한다(이 보고서는 공통 파일을 수정하지 않음).
- `docs/plans/t03-draft/`와 `docs/plans/T03_MASTER_DESIGN.md`는 미추적 상태다. 두 문서는 내용이 같으므로, 커밋 시 하나만 기준으로 정하는 것을 권장한다.
- 초안 HANDOFF는 산출물을 `docs/reports/T03_DESIGN_REVIEW.md`로 지시하지만, 이번 사용자 지시는 `docs/reviews/T03_CLAUDE_DESIGN_REVIEW.md`다. 사용자 지시를 따랐다.
- 최종 설계 승인 전 구현 착수, 커밋, 푸시를 하지 않았다.

---

## 부록 A. DDL 초안 (설계 설명용, 실행 SQL 아님 · V6 이후 번호는 실제 main 확인 후 배정)

```sql
-- 소유권 대상 키 (기존 테이블, 신규 migration으로 추가)
ALTER TABLE channel_config ADD CONSTRAINT uk_channel_config_owner UNIQUE (channel_id, id);

CREATE TABLE content (
  id BIGINT AUTO_INCREMENT, channel_id BIGINT NOT NULL, content_type VARCHAR(16) NOT NULL,
  title VARCHAR(255) NOT NULL,              -- 현재 revision working_title 복제(같은 TX에서만 갱신)
  topic_id BIGINT NULL,                     -- N-11 결정에 따름
  status VARCHAR(16) NOT NULL,              -- T03: DRAFT | PRODUCING | ARCHIVED
  current_revision_id BIGINT NULL, active_run_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0, created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, archived_at DATETIME(6) NULL,
  PRIMARY KEY (id), UNIQUE (id, channel_id), UNIQUE (id, content_type),
  FOREIGN KEY (channel_id) REFERENCES channel (id), FOREIGN KEY (created_by) REFERENCES admin_user (id),
  FOREIGN KEY (id, current_revision_id) REFERENCES content_revision (content_id, id),  -- 순환 FK: revision 생성 후 ALTER로 추가
  FOREIGN KEY (id, active_run_id) REFERENCES content_run (content_id, id),
  CHECK (content_type IN ('SHORTS','LONGFORM')), CHECK (status IN ('DRAFT','PRODUCING','ARCHIVED')),
  CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
  CHECK ((status = 'PRODUCING') = (active_run_id IS NOT NULL)),
  INDEX (channel_id, status, id)
);
-- 트리거: channel_id / content_type / created_by 변경 SIGNAL, DELETE SIGNAL

CREATE TABLE content_revision (
  id BIGINT AUTO_INCREMENT, content_id BIGINT NOT NULL, channel_id BIGINT NOT NULL, revision_no INT NOT NULL,
  channel_config_id BIGINT NOT NULL, base_run_id BIGINT NULL,
  working_title VARCHAR(255) NOT NULL, topic TEXT NOT NULL, description TEXT NULL,
  language VARCHAR(16) NOT NULL, target_duration_seconds INT NOT NULL,
  settings_json JSON NULL,                  -- 비핵심 옵션만(ADR-015)
  revision_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL, hash_version SMALLINT NOT NULL,
  change_reason VARCHAR(500) NULL, created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE (content_id, revision_no), UNIQUE (content_id, id),
  FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
  FOREIGN KEY (channel_id, channel_config_id) REFERENCES channel_config (channel_id, id),
  FOREIGN KEY (content_id, base_run_id) REFERENCES content_run (content_id, id),
  CHECK (revision_no >= 1), CHECK (target_duration_seconds > 0)
);
-- 트리거: UPDATE / DELETE SIGNAL

CREATE TABLE content_run (
  id BIGINT AUTO_INCREMENT, content_id BIGINT NOT NULL, content_revision_id BIGINT NOT NULL, run_no INT NOT NULL,
  pipeline_version VARCHAR(50) NOT NULL, status VARCHAR(16) NOT NULL,   -- QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED
  cancel_requested_at DATETIME(6) NULL, cancel_reason VARCHAR(500) NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, request_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  requested_by BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
  PRIMARY KEY (id), UNIQUE (content_id, run_no), UNIQUE (content_id, id), UNIQUE (request_key),
  FOREIGN KEY (content_id, content_revision_id) REFERENCES content_revision (content_id, id),
  CHECK (request_key REGEXP '^[A-Za-z0-9:._-]{8,128}$'),
  CHECK ((status IN ('SUCCEEDED','FAILED','CANCELLED')) = (finished_at IS NOT NULL))
);

CREATE TABLE automation_job (
  id BIGINT AUTO_INCREMENT, run_id BIGINT NOT NULL, content_id BIGINT NOT NULL, channel_id BIGINT NOT NULL,
  job_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, job_type VARCHAR(50) NOT NULL,
  spawned_by_job_id BIGINT NULL,            -- 확장 노드(N-04), T03은 항상 NULL
  status VARCHAR(24) NOT NULL, status_reason VARCHAR(64) NULL, priority SMALLINT NOT NULL DEFAULT 0,
  attempt_count INT NOT NULL DEFAULT 0, auto_retry_count INT NOT NULL DEFAULT 0, max_attempts SMALLINT NOT NULL,
  available_at DATETIME(6) NOT NULL,        -- DB 시각 기준(N-06)
  lease_owner VARCHAR(128) NULL, lease_token CHAR(36) COLLATE utf8mb4_bin NULL,
  lease_expires_at DATETIME(6) NULL, heartbeat_at DATETIME(6) NULL, cancel_requested_at DATETIME(6) NULL,
  input_hash CHAR(64) COLLATE utf8mb4_bin NULL,
  error_code VARCHAR(64) NULL, error_message VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE (run_id, job_key), UNIQUE (run_id, id),
  FOREIGN KEY (content_id, run_id) REFERENCES content_run (content_id, id),
  FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
  FOREIGN KEY (run_id, spawned_by_job_id) REFERENCES automation_job (run_id, id),
  CHECK (status IN ('WAITING','READY','RUNNING','RETRY_WAIT','ACTION_REQUIRED','RECOVERY_REQUIRED',
                    'SUCCEEDED','FAILED','CANCELLED','SKIPPED')),
  CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
  CHECK (attempt_count >= 0 AND auto_retry_count >= 0 AND max_attempts >= 1),
  INDEX ix_job_claim (status, priority DESC, available_at, id),
  INDEX ix_job_lease (status, lease_expires_at, id),
  INDEX ix_job_content (content_id, status), INDEX ix_job_run (run_id, status),
  INDEX ix_job_reuse (job_type, input_hash)
);

CREATE TABLE job_dependency (
  run_id BIGINT NOT NULL, job_id BIGINT NOT NULL, predecessor_job_id BIGINT NOT NULL,
  PRIMARY KEY (job_id, predecessor_job_id),
  FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
  FOREIGN KEY (run_id, predecessor_job_id) REFERENCES automation_job (run_id, id),
  CHECK (job_id <> predecessor_job_id), INDEX (predecessor_job_id, job_id)
);
-- 트리거: UPDATE / DELETE SIGNAL (INSERT는 run 생성 TX와 확장 job 성공 TX에서만)

CREATE TABLE job_attempt (
  id BIGINT AUTO_INCREMENT, job_id BIGINT NOT NULL, attempt_no INT NOT NULL,
  lease_token CHAR(36) COLLATE utf8mb4_bin NOT NULL, lease_owner VARCHAR(128) NOT NULL,
  started_at DATETIME(6) NOT NULL, execution_deadline_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL, outcome VARCHAR(32) NULL, error_code VARCHAR(64) NULL,
  PRIMARY KEY (id), UNIQUE (job_id, attempt_no), UNIQUE (job_id, id),
  FOREIGN KEY (job_id) REFERENCES automation_job (id), CHECK (attempt_no >= 1)
);
-- 트리거: finished_at이 NULL이 아니면 UPDATE 금지, DELETE 금지

CREATE TABLE job_event (                    -- run 수준 이벤트도 수용(job_id NULL 허용)
  id BIGINT AUTO_INCREMENT, run_id BIGINT NOT NULL, job_id BIGINT NULL, attempt_id BIGINT NULL,
  event_type VARCHAR(32) NOT NULL, from_status VARCHAR(24) NULL, to_status VARCHAR(24) NULL,
  reason VARCHAR(64) NULL, actor_type VARCHAR(16) NOT NULL, actor_user_id BIGINT NULL, trace_id VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
  FOREIGN KEY (job_id, attempt_id) REFERENCES job_attempt (job_id, id),
  INDEX (run_id, id), INDEX (job_id, id)
);
-- 트리거: UPDATE / DELETE SIGNAL

-- 예산 기존 테이블 (ck_budget_policy_no_content는 유지)
ALTER TABLE budget_bucket DROP CONSTRAINT ck_budget_bucket_no_content,
  ADD CONSTRAINT fk_budget_bucket_content FOREIGN KEY (content_id) REFERENCES content (id);
ALTER TABLE budget_reservation DROP CONSTRAINT ck_budget_reservation_no_content,
  ADD CONSTRAINT ck_budget_reservation_content_channel CHECK (content_id IS NULL OR channel_id IS NOT NULL),
  ADD CONSTRAINT fk_budget_reservation_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id);
```

`job_operation`, `operation_event`, `settlement_operation`은 비용 경계 설계(Codex 검토 대상)와 함께 확정한다. 도메인 관점의 요구만 정리하면 다음과 같다.
- `(job_id, operation_seq)` UNIQUE
- `created_attempt_id` (`FK (job_id, created_attempt_id) → job_attempt (job_id, id)`)
- attestation append 테이블
- ai_request_log 연결점

## 부록 B. Job 상태 전이표 수정안

`cancel` = `cancel_requested_at IS NOT NULL`. 모든 전이는 run 행 잠금 하에서 상태 갱신, attempt, event를 한 TX로 처리한다(heartbeat 제외). 표에 없는 전이는 409 INVALID_STATE_TRANSITION이다.

| From | 이벤트 / 조건 | To | 비고 |
|---|---|---|---|
| WAITING | 모든 선행 SUCCEEDED | READY | 후행 승격은 선행 완료 TX 안에서 처리 |
| WAITING | 선행 중 FAILED·SKIPPED·CANCELLED 발생, 또는 run fail-fast | SKIPPED | status_reason=PREDECESSOR_* / RUN_FAILED_FAST |
| WAITING, READY, RETRY_WAIT, ACTION_REQUIRED | run 취소 | CANCELLED | 미확정 operation 없음 전제 |
| READY | claim(run 잠금 획득, 상한·채널·취소 확인) | RUNNING | attempt 생성, attempt_count+1 |
| READY, RETRY_WAIT | 채널 PAUSED | (유지) | claim 대상에서만 제외 |
| RUNNING | 완료 보고(token·epoch 유효), 결과·정산 확정 | SUCCEEDED | cancel이 있어도 성공은 성공 |
| RUNNING | 일시 오류, 자동 재시도 여유, cancel 없음 | RETRY_WAIT | available_at = DB 시각 + backoff |
| RUNNING | 일시 오류, cancel 있음 | CANCELLED | 미확정 비용 없을 때 |
| RUNNING | 확정 실패 또는 자동 재시도 소진, 미확정 비용 없음 | FAILED | 후행은 SKIPPED |
| RUNNING | BUDGET_EXCEEDED·POLICY_VIOLATION·PREREQUISITE_NOT_MET | ACTION_REQUIRED (cancel이면 CANCELLED) | 자동 재시도 금지 |
| RUNNING | 결과·정산 불명 | RECOVERY_REQUIRED | status_reason=EXTERNAL_OUTCOME_UNKNOWN |
| RUNNING | **LEASE_EXPIRED**, operation 없음 또는 DISPATCHING 전 | RETRY_WAIT / FAILED(소진) / CANCELLED(cancel) | status_reason=LEASE_EXPIRED_BEFORE_DISPATCH |
| RUNNING | **LEASE_EXPIRED**, DISPATCHING 이후 | RECOVERY_REQUIRED | 재호출 금지 |
| RETRY_WAIT | available_at 도래 | READY | |
| ACTION_REQUIRED | 관리자 resume(감사 기록, 재개 상한 이내) | READY | 시도 1회 부여, 전제 사전 검사 |
| RECOVERY_REQUIRED | 조회 또는 판정: 실행·성공 증거 | SUCCEEDED | |
| RECOVERY_REQUIRED | 조회 또는 판정: 미실행 확정, 예약 해소 | READY(여유, cancel 없음) / CANCELLED(cancel) / FAILED(소진) | 같은 operation 재사용 가능 |
| RECOVERY_REQUIRED | 조회 또는 판정: 실행·실패 확정, 비용 정산 완료 | FAILED (cancel이면 CANCELLED) | |

Run은 QUEUED에서 첫 claim으로 RUNNING이 된다. 모든 job이 terminal이 되면 ① 모두 SUCCEEDED면 SUCCEEDED ② cancel이 있으면 CANCELLED ③ 그 외에는 FAILED가 된다. terminal 전이와 `content.active_run_id = NULL`, `content.status = DRAFT`는 같은 TX에서 처리한다.

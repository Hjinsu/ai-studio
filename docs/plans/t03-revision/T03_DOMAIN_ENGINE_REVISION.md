# T03-A3 — 도메인 및 작업 엔진 상세 설계 수정안

| 항목 | 내용 |
|---|---|
| 작업 ID | T03-A3 (사용자 지시 2026-11-25) |
| 작성 | Claude Code |
| 상태 | **설계 수정안 — 미승인.** 제품 코드·DB·migration·기존 승인 ADR·공통 문서(STATE/TASKS/DECISIONS/OpenAPI)는 수정하지 않았다. 이 문서 한 개만 추가했다 |
| 기준 | `main` `47ef697`, 승인 ADR-001~066, 실제 migration V1~V5, `docs/api/openapi.yaml` 0.3.0 |
| 입력 | [T03-A1 Claude 리뷰](../../reviews/T03_CLAUDE_DESIGN_REVIEW.md)(CDR-01~25), [T03-A2 Codex 리뷰](../../reviews/T03_CODEX_DESIGN_REVIEW.md)(A2-01~08), `docs/plans/t03-draft/*`, 사용자 확정 방향 1~24 |
| 소유 경계 | 이 문서는 **도메인·작업 엔진**을 다룬다. 비용 영역(LIFETIME 정책 적용, `job_operation` 내부 상태, 정산 operation, 예산 링크 검사)은 Codex가 병렬로 설계한다. 이 문서는 그 영역과 맞물리는 **인터페이스만** 정의한다(§13). 둘이 겹치는 곳은 "통합 검증 대상(INT-nn)"으로 표시했다 |
| 표기 | DDL은 설계 명세이며 실행 migration이 아니다. migration 번호는 V6 이후로 하되 실제 main과 Codex 비용 migration의 순서를 통합 시 확정한다 |

---

## 0. 확정 방향 반영 위치

| # | 확정 방향 | 반영 절 |
|---|---|---|
| 1 | Revision은 불변 입력 사양, 산출물은 Run에 귀속 | §2.1, §3.4 |
| 2 | `base_run_id`, 승인 대상 = revision_id + run_id + manifest_hash | §2.2, §3.4~3.5, §10 T3-ADR-01 |
| 3 | content.title은 현재 revision 제목 복제본 | §2.3, §3.3, §5.4 P2 |
| 4 | `content.active_run_id` + 복합 FK로 활성 run 1개 | §3.3, §4.3 |
| 5 | `automation_job` 명칭 유지 | §3.6 |
| 6~7 | Job 상태 10종, CANCEL_REQUESTED 제거·`cancel_requested_at` | §4.1 |
| 8 | Run 5종 + attention 집계 | §4.2, §4.4 |
| 9 | Content DRAFT/PRODUCING/ARCHIVED | §4.3 |
| 10 | EXPANDER → CHILDREN → BARRIER 계약 | §7.3 |
| 11~12 | `queue_control` 직렬화, 잠금 순서 | §5 |
| 13 | 후보 조회 → 잠금 → 재확인 | §5.4 P4, §6.1 |
| 14 | lease 60s, heartbeat 15s, execution_deadline_at, DB UTC, attempt fencing | §6.2 |
| 15 | DB 기준 유효 lease 최대 2, 프로세스 상한·외부 실행 상한 구분 | §6.6 |
| 16 | 자동 실행 3회(최초 포함), 수동 재개 별도 상한, job별 고정 | §6.5 |
| 17 | 전용 스케줄러·Spring Scheduler, Quartz JDBC 연기 ADR | §6.8, §10 T3-ADR-07 |
| 18 | topic 최소 테이블 T03, scene·claim T04, media T05 | §2.4, §3.2, §10 T3-ADR-09 |
| 19 | Idempotency-Key 헤더·version·baseRevisionNo 유지 | §8.1 |
| 20 | pipelineVersion 서버 선택, Fake 명확 구분 | §7.1, §3.5, §8 |
| 21 | Run 단위 취소만 | §6.4, §8 |
| 22 | 재시도 API는 ACTION_REQUIRED 재개 한정, RECOVERY_REQUIRED는 별도 판정 | §6.5, §13 INT-06 |
| 23 | 전이표·복합 FK·CHECK·UNIQUE·인덱스·불변 트리거·최소 권한 | §3, §4, §3.12 |
| 24 | CDR-01~25 대응 | §11 |

---

## 1. 범위

**T03 포함**
- topic 최소 등록·중복 검사
- content 등록·보관
- 불변 revision
- run 생성·취소
- 고정 DAG와 확장 계약(스키마·검증만)
- automation_job 큐: claim, lease, heartbeat, sweep, 재시도, 재개
- attempt·event 이력
- queue_control
- Fake 실행기
- 관리자 API와 UI
- 비용 영역 인터페이스

**T03 제외**
- 실제 provider 호출(T07)
- 장면 확장 실행(T04~T05)
- content_scene·claim(T04)
- media 상세(T05)
- 검수·승인 구현(T06)
- 게시(T08)
- 예약 제작과 Quartz JDBC JobStore(지속 일정 도입 단계)
- job 단위 취소

---

## 2. 도메인 의미 정의

### 2.1 계층과 의미

| 개체 | 의미 | 변경 가능성 |
|---|---|---|
| topic | 채널 안의 제작 소재. 정규화 제목으로 중복을 판정한다 | 불변(수정 = 새 topic) |
| content | 채널·유형·topic에 고정된 제작 단위. 현재 revision과 활성 run을 가리킨다 | 포인터·상태·제목 복제본·version만 변경 |
| content_revision | 사용자가 편집한 **불변 입력 사양**(작업 제목, 지시문, 언어, 목표 길이, 채널 설정 revision, 비핵심 옵션) | 불변 |
| content_run | 특정 revision을 서버가 고른 고정 pipeline 버전으로 **한 번 실행한 시도**. 산출물은 run에 귀속된다 | 상태·타임스탬프·취소 의도만 변경 |
| automation_job | run 안의 DAG 노드 | 상태·lease·카운터·결과 요약만 변경 |
| job_attempt | job의 lease 1회(실행 1회) | 종료 시 한 번만 갱신 |
| job_event | 상태 전이·명령 이력 | append-only |

### 2.2 산출물 귀속과 승인 대상

- T04 이후의 산출물(대본, 장면, asset usage, manifest)은 `run_id`를 소유자로 갖는다. revision은 산출물을 직접 소유하지 않는다.
- 장면 편집과 부분 재생성은 `base_run_id`를 가진 새 revision으로 표현한다. 새 run은 base run의 변경되지 않은 산출물을 `input_hash`로 재사용할 수 있다(재사용 로직은 T05).
- 승인 대상(T06)은 **`(revision_id, run_id, manifest_hash)`** 이다.
  - run은 해당 revision의 run이어야 하고, 상태는 SUCCEEDED, 실행 모드는 LIVE여야 한다.
  - ADR-002의 "승인 revision/hash"는 이 세 값의 일치로 해석한다(T3-ADR-01).
  - 새 revision이 생기면 콘텐츠는 DRAFT로 돌아가고, 기존 승인은 이전 revision·run 쌍에만 남는다.
- `content_run.output_manifest_hash`는 T03에서 항상 NULL이다. Fake run은 manifest를 만들지 않으므로 승인 대상이 될 수 없다.

### 2.3 content.title

- `content.title`은 `current_revision_id`가 가리키는 revision의 `working_title` 복제본이다.
- 콘텐츠 생성 TX와 revision 생성 TX에서만, `current_revision_id`와 함께 갱신한다. 별도 제목 수정 API는 없다.
- 목록 검색과 정렬은 이 컬럼을 쓴다.
- 정합성 검사 Q-INV-03(§12)이 두 값의 일치를 확인한다.

### 2.4 범위 연기 (T3-ADR-09)

| 개체 | 처리 |
|---|---|
| topic | T03 최소 테이블(§3.2). FR02 중복 검사의 DB 근거 |
| content_scene, content_claim, claim_evidence, claim_verification | T04. run 귀속(`run_id`)을 전제로 설계 |
| media_asset, media_asset_usage | T05. usage는 `(run_id, scene_no)`로 정의(ADR-016 해석) |
| ai_request_log | T07. `job_operation`을 참조하는 provider 상세 행(INT-10) |

---

## 3. 테이블 명세

공통 규칙
- signed BIGINT PK, `DATETIME(6)` UTC, CHARSET/COLLATE 생략(DB 기본 `utf8mb4_unicode_ci` 상속, ADR-014).
- 정확 비교 컬럼만 `utf8mb4_bin`(ADR-053).
- 물리 삭제와 FK cascade는 금지한다.
- **큐 테이블의 시각은 모두 DB 시각(`UTC_TIMESTAMP(6)`)으로 SQL 안에서 기록한다**(§6.7).
- topic·content·revision의 `created_at`/`updated_at`도 같은 규칙을 따른다. 예산 테이블은 기존 앱 Clock을 유지한다.

### 3.1 기존 테이블 변경 (도메인 측)

```sql
-- revision → channel_config 소유권 복합 FK 대상 (V4는 (channel_id, revision_no)만 UNIQUE)
ALTER TABLE channel_config ADD CONSTRAINT uk_channel_config_owner UNIQUE (channel_id, id);
```

- `budget_bucket`·`budget_reservation`의 content FK와 `ck_*_no_content` 해제는 **Codex 비용 migration** 소관이다(INT-12).
- 도메인 측은 그 FK가 참조할 `content UNIQUE(id, channel_id)`, `UNIQUE(id, content_type)`을 제공한다.

### 3.2 topic

```sql
CREATE TABLE topic (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id            BIGINT       NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    normalized_title      VARCHAR(255) NOT NULL COMMENT 'NFKC → 소문자(Locale.ROOT) → 공백 1칸 압축 → trim',
    normalized_hash       CHAR(64)     COLLATE utf8mb4_bin NOT NULL COMMENT 'SHA-256(normalization_version || normalized_title)',
    normalization_version SMALLINT     NOT NULL,
    note                  VARCHAR(500) NULL,
    created_by            BIGINT       NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_topic PRIMARY KEY (id),
    CONSTRAINT uk_topic_normalized UNIQUE (channel_id, normalized_hash),
    CONSTRAINT uk_topic_owner UNIQUE (id, channel_id),
    CONSTRAINT fk_topic_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_topic_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_topic_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_topic_hash CHECK (normalized_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_topic_norm_version CHECK (normalization_version >= 1),
    INDEX ix_topic_channel (channel_id, id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'topic is immutable'
```

- 같은 채널에서 정규화 제목이 같으면 409 DUPLICATE_RESOURCE를 반환한다.
- 한 topic으로 여러 content(쇼츠·롱폼 등)를 만들 수 있다. 중복 판정은 topic 단위다.

### 3.3 content

```sql
CREATE TABLE content (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id          BIGINT       NOT NULL,
    topic_id            BIGINT       NOT NULL,
    content_type        VARCHAR(16)  NOT NULL,
    title               VARCHAR(255) NOT NULL COMMENT '현재 revision working_title 복제본(§2.3)',
    status              VARCHAR(16)  NOT NULL,
    current_revision_id BIGINT       NULL,
    active_run_id       BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          BIGINT       NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    archived_at         DATETIME(6)  NULL,
    CONSTRAINT pk_content PRIMARY KEY (id),
    CONSTRAINT uk_content_channel UNIQUE (id, channel_id),
    CONSTRAINT uk_content_type UNIQUE (id, content_type),
    CONSTRAINT fk_content_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_content_topic FOREIGN KEY (topic_id, channel_id) REFERENCES topic (id, channel_id),
    CONSTRAINT fk_content_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_content_type CHECK (content_type IN ('SHORTS', 'LONGFORM')),
    CONSTRAINT ck_content_status CHECK (status IN ('DRAFT', 'PRODUCING', 'ARCHIVED')),
    CONSTRAINT ck_content_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT ck_content_active_run CHECK ((status = 'PRODUCING') = (active_run_id IS NOT NULL)),
    CONSTRAINT ck_content_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    INDEX ix_content_list (channel_id, status, id),
    INDEX ix_content_topic (topic_id, id),
    INDEX ix_content_status (status, id)
);
-- content_revision·content_run 생성 후 추가(순환 FK):
-- ALTER TABLE content ADD CONSTRAINT fk_content_current_revision
--     FOREIGN KEY (id, current_revision_id) REFERENCES content_revision (content_id, id);
-- ALTER TABLE content ADD CONSTRAINT fk_content_active_run
--     FOREIGN KEY (id, active_run_id) REFERENCES content_run (content_id, id);
-- 트리거:
--   BEFORE UPDATE: channel_id, topic_id, content_type, created_by, created_at 변경 시 SIGNAL;
--                  OLD.status = 'ARCHIVED'이면 SIGNAL (보관은 종료 상태)
--   BEFORE DELETE: SIGNAL
```

활성 run 1개 보장
- `active_run_id`는 단일 컬럼이므로 콘텐츠당 최대 1개다.
- 복합 FK가 그 run이 같은 콘텐츠의 것임을 강제한다.
- CHECK가 PRODUCING 상태와 동치임을 강제한다.
- run 생성은 content 행 X 잠금 아래에서 `active_run_id IS NULL`을 확인한다.

### 3.4 content_revision

```sql
CREATE TABLE content_revision (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    content_id              BIGINT       NOT NULL,
    channel_id              BIGINT       NOT NULL COMMENT 'content.channel_id 복제(복합 FK용, 불변)',
    revision_no             INT          NOT NULL,
    channel_config_id       BIGINT       NOT NULL COMMENT '이 revision이 고정한 채널 설정 revision',
    base_run_id             BIGINT       NULL COMMENT '편집 기준 run(같은 콘텐츠, SUCCEEDED). 재사용은 T05',
    working_title           VARCHAR(255) NOT NULL,
    brief                   TEXT         NOT NULL COMMENT '제작 지시문(초안의 topic TEXT)',
    language                VARCHAR(16)  NOT NULL,
    target_duration_seconds INT          NOT NULL,
    settings_json           JSON         NULL COMMENT '비핵심 옵션만(ADR-015). 앱에서 허용 키·16KB 제한',
    revision_hash           CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    hash_version            SMALLINT     NOT NULL,
    change_reason           VARCHAR(500) NULL,
    created_by              BIGINT       NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    CONSTRAINT pk_content_revision PRIMARY KEY (id),
    CONSTRAINT uk_content_revision_no UNIQUE (content_id, revision_no),
    CONSTRAINT uk_content_revision_owner UNIQUE (content_id, id),
    CONSTRAINT fk_revision_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
    CONSTRAINT fk_revision_channel_config FOREIGN KEY (channel_id, channel_config_id)
        REFERENCES channel_config (channel_id, id),
    CONSTRAINT fk_revision_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_revision_no CHECK (revision_no >= 1),
    CONSTRAINT ck_revision_language CHECK (language REGEXP '(?-i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_revision_duration CHECK (target_duration_seconds BETWEEN 1 AND 14400),
    CONSTRAINT ck_revision_title CHECK (CHAR_LENGTH(TRIM(working_title)) > 0),
    CONSTRAINT ck_revision_brief CHECK (CHAR_LENGTH(TRIM(brief)) > 0),
    CONSTRAINT ck_revision_hash CHECK (revision_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_revision_hash_version CHECK (hash_version >= 1)
);
-- content_run 생성 후 추가:
-- ALTER TABLE content_revision ADD CONSTRAINT fk_revision_base_run
--     FOREIGN KEY (content_id, base_run_id) REFERENCES content_run (content_id, id);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'content_revision is immutable'
```

앱 검증
- `target_duration_seconds`는 고정한 channel_config의 유형별 범위 안이어야 한다(SHORTS: shorts_min~max, LONGFORM: longform_min~max).
- `base_run_id`는 같은 콘텐츠의 SUCCEEDED run이어야 한다.

**revision_hash (hash_version = 1)**
- 계산식: `SHA-256(UTF-8(canonical JSON))`
- 대상 필드(키 이름 사전순): `baseRunId`, `brief`, `channelConfigId`, `contentId`, `hashVersion`, `language`, `settings`, `targetDurationSeconds`, `workingTitle`
- 정규화 규칙
  - 공백 없는 JSON. `settings`의 중첩 객체는 키를 코드포인트 순으로 정렬한다.
  - null도 명시적으로 출력한다. 정수만 허용한다.
  - 문자열은 저장값 그대로 쓴다(정규화하지 않음). 입력 단계에서 줄바꿈을 `\n`으로 통일하고 이를 저장값으로 쓴다.
- 기존 `budget/Fingerprints`(package-private)를 `shared` 공통 정규화 유틸로 옮겨 재사용한다(구현 단계 변경). 예산 fingerprint 출력은 불변이어야 한다(회귀 테스트로 확인).
- 새 revision의 hash가 현재 revision과 같으면 409 DUPLICATE_RESOURCE(details reason `NO_CHANGES`)를 반환한다.

### 3.5 content_run

```sql
CREATE TABLE content_run (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    content_id            BIGINT       NOT NULL,
    content_revision_id   BIGINT       NOT NULL,
    run_no                INT          NOT NULL,
    pipeline_version      VARCHAR(64)  NOT NULL COMMENT '서버 선택(§7.1)',
    execution_mode        VARCHAR(8)   NOT NULL,
    fake_scenario         VARCHAR(48)  NULL,
    status                VARCHAR(16)  NOT NULL,
    cancel_requested_at   DATETIME(6)  NULL,
    cancel_requested_by   BIGINT       NULL,
    cancel_reason         VARCHAR(500) NULL,
    fail_fast_at          DATETIME(6)  NULL COMMENT '첫 FAILED job 시각. 이후 새 claim·승격 중지',
    output_manifest_hash  CHAR(64)     COLLATE utf8mb4_bin NULL COMMENT 'T05 이후 LIVE 성공 run만. T03은 항상 NULL',
    requested_by          BIGINT       NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL,
    started_at            DATETIME(6)  NULL,
    finished_at           DATETIME(6)  NULL,
    CONSTRAINT pk_content_run PRIMARY KEY (id),
    CONSTRAINT uk_content_run_no UNIQUE (content_id, run_no),
    CONSTRAINT uk_content_run_owner UNIQUE (content_id, id),
    CONSTRAINT fk_run_revision FOREIGN KEY (content_id, content_revision_id)
        REFERENCES content_revision (content_id, id),
    CONSTRAINT fk_run_requested_by FOREIGN KEY (requested_by) REFERENCES admin_user (id),
    CONSTRAINT fk_run_cancel_by FOREIGN KEY (cancel_requested_by) REFERENCES admin_user (id),
    CONSTRAINT ck_run_no CHECK (run_no >= 1),
    CONSTRAINT ck_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- T07에서 신규 migration으로 LIVE 허용
    CONSTRAINT ck_run_mode CHECK (execution_mode IN ('FAKE')),
    CONSTRAINT ck_run_fake_scenario CHECK (fake_scenario IS NULL OR execution_mode = 'FAKE'),
    CONSTRAINT ck_run_finished CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_run_started CHECK (status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') OR started_at IS NOT NULL),
    CONSTRAINT ck_run_queued CHECK (status <> 'QUEUED' OR started_at IS NULL),
    CONSTRAINT ck_run_cancel CHECK ((cancel_requested_at IS NULL) = (cancel_requested_by IS NULL)
        AND (cancel_reason IS NULL OR cancel_requested_at IS NOT NULL)),
    CONSTRAINT ck_run_manifest CHECK (output_manifest_hash IS NULL OR (status = 'SUCCEEDED'
        AND output_manifest_hash REGEXP '(?-i)^[0-9a-f]{64}$')),
    INDEX ix_run_content (content_id, id),
    INDEX ix_run_status (status, id)
);
-- 트리거:
--   BEFORE UPDATE: content_id, content_revision_id, run_no, pipeline_version, execution_mode, fake_scenario,
--                  requested_by, created_at 변경 시 SIGNAL;
--                  OLD.status ∈ {SUCCEEDED, FAILED, CANCELLED}이면 SIGNAL(종료 불변)
--   BEFORE DELETE: SIGNAL
```

- `CHECK execution_mode IN ('FAKE')`는 T03 동안 LIVE 실행을 **DB 수준에서 불가능하게** 한다. T07에서 별도 migration과 승인으로 푼다.
- run 생성 멱등 키는 `queue_command_log`(§3.11)에 둔다.

### 3.6 automation_job

```sql
CREATE TABLE automation_job (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    run_id                    BIGINT       NOT NULL,
    content_id                BIGINT       NOT NULL COMMENT '불변 복제(상한·필터·비용 인터페이스)',
    channel_id                BIGINT       NOT NULL COMMENT '불변 복제',
    job_key                   VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    job_type                  VARCHAR(48)  NOT NULL,
    node_kind                 VARCHAR(16)  NOT NULL,
    spawned_by_job_id         BIGINT       NULL COMMENT '확장 자식이면 EXPANDER job(§7.3). T03은 항상 NULL',
    requires_operation        BOOLEAN      NOT NULL COMMENT '외부 부작용(비용) 있는 job. job_operation 필수(§13)',
    status                    VARCHAR(24)  NOT NULL,
    status_reason             VARCHAR(64)  NULL,
    available_at              DATETIME(6)  NOT NULL COMMENT 'READY/RETRY_WAIT claim 가능 시각(DB 시각)',
    attempt_count             INT          NOT NULL DEFAULT 0 COMMENT '모든 claim 수 = 마지막 attempt_no',
    auto_attempts_used        SMALLINT     NOT NULL DEFAULT 0,
    max_auto_attempts         SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 3(최초 포함)',
    manual_resumes_used       SMALLINT     NOT NULL DEFAULT 0,
    max_manual_resumes        SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 2',
    pending_manual_resume     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '다음 claim을 수동 시도로 계산',
    execution_timeout_seconds INT          NOT NULL COMMENT 'attempt.execution_deadline_at 계산, 불변',
    current_attempt_no        INT          NULL,
    lease_owner               VARCHAR(100) NULL COMMENT '<instance_id>:<worker_slot>',
    lease_token               CHAR(36)     COLLATE utf8mb4_bin NULL,
    lease_expires_at          DATETIME(6)  NULL,
    heartbeat_at              DATETIME(6)  NULL,
    cancel_requested_at       DATETIME(6)  NULL,
    input_hash                CHAR(64)     COLLATE utf8mb4_bin NULL COMMENT '재사용 조회용(T05). unique 아님',
    result_summary_json       JSON         NULL COMMENT '안전한 요약만. 한 번 기록 후 불변',
    error_code                VARCHAR(64)  NULL,
    error_message             VARCHAR(500) NULL COMMENT '비밀·원문 응답 제외한 안전 메시지',
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    finished_at               DATETIME(6)  NULL,
    CONSTRAINT pk_automation_job PRIMARY KEY (id),
    CONSTRAINT uk_job_key UNIQUE (run_id, job_key),
    CONSTRAINT uk_job_owner UNIQUE (run_id, id),
    CONSTRAINT fk_job_run FOREIGN KEY (content_id, run_id) REFERENCES content_run (content_id, id),
    CONSTRAINT fk_job_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
    CONSTRAINT fk_job_spawned_by FOREIGN KEY (run_id, spawned_by_job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT ck_job_status CHECK (status IN ('WAITING', 'READY', 'RUNNING', 'RETRY_WAIT', 'ACTION_REQUIRED',
        'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_job_key CHECK (job_key REGEXP '^[a-z0-9][a-z0-9:/._-]{0,127}$'),
    CONSTRAINT ck_job_type CHECK (job_type REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,47}$'),
    CONSTRAINT ck_job_node CHECK (node_kind IN ('TASK', 'EXPANDER', 'BARRIER')),
    CONSTRAINT ck_job_child CHECK (spawned_by_job_id IS NULL OR node_kind = 'TASK'),
    CONSTRAINT ck_job_reason CHECK (status_reason IS NULL OR status_reason REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_job_lease CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL)
        AND (lease_token IS NULL) = (lease_owner IS NULL)
        AND (lease_token IS NULL) = (lease_expires_at IS NULL)
        AND (lease_token IS NULL OR current_attempt_no IS NOT NULL)),
    CONSTRAINT ck_job_finished CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'))
        = (finished_at IS NOT NULL)),
    CONSTRAINT ck_job_counts CHECK (max_auto_attempts BETWEEN 1 AND 10 AND max_manual_resumes BETWEEN 0 AND 10
        AND auto_attempts_used BETWEEN 0 AND max_auto_attempts
        AND manual_resumes_used BETWEEN 0 AND max_manual_resumes
        AND attempt_count >= auto_attempts_used
        AND (current_attempt_no IS NULL OR current_attempt_no = attempt_count)),
    CONSTRAINT ck_job_pending_resume CHECK (pending_manual_resume = FALSE OR status = 'READY'),
    CONSTRAINT ck_job_cancel CHECK (cancel_requested_at IS NULL
        OR status IN ('RUNNING', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_job_timeout CHECK (execution_timeout_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_job_hash CHECK (input_hash IS NULL OR input_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    INDEX ix_job_claim (status, available_at, id),
    INDEX ix_job_lease (status, lease_expires_at, id),
    INDEX ix_job_run (run_id, status, id),
    INDEX ix_job_channel (channel_id, status, id),
    INDEX ix_job_reuse (job_type, input_hash)
);
-- job_attempt 생성 후 추가:
-- ALTER TABLE automation_job ADD CONSTRAINT fk_job_current_attempt
--     FOREIGN KEY (id, current_attempt_no) REFERENCES job_attempt (job_id, attempt_no);
-- 트리거:
--   BEFORE UPDATE: run_id, content_id, channel_id, job_key, job_type, node_kind, spawned_by_job_id,
--                  requires_operation, max_auto_attempts, max_manual_resumes, execution_timeout_seconds,
--                  input_hash, created_at 변경 시 SIGNAL;
--                  OLD.status가 종료 상태이면 SIGNAL;
--                  OLD.result_summary_json IS NOT NULL AND NEW.result_summary_json <=> OLD가 아니면 SIGNAL
--   BEFORE DELETE: SIGNAL
```

- 우선순위 컬럼은 두지 않는다(CDR-21). claim 순서는 `available_at, id`(FIFO)다. 우선순위가 필요해지면 별도 ADR과 migration으로 추가한다.
- `job_key` 예: `research`, `script`, `storyboard`, `visual`, `narration`, `assemble`, `finalize`. 확장 자식은 `storyboard/scene:001/visual`처럼 쓴다(§7.3).

### 3.7 job_dependency

```sql
CREATE TABLE job_dependency (
    run_id             BIGINT NOT NULL,
    job_id             BIGINT NOT NULL COMMENT '후행',
    predecessor_job_id BIGINT NOT NULL COMMENT '선행',
    CONSTRAINT pk_job_dependency PRIMARY KEY (job_id, predecessor_job_id),
    CONSTRAINT fk_dep_job FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT fk_dep_predecessor FOREIGN KEY (run_id, predecessor_job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT ck_dep_no_self CHECK (job_id <> predecessor_job_id),
    INDEX ix_dep_predecessor (predecessor_job_id, job_id),
    INDEX ix_dep_run (run_id)
);
-- 트리거:
--   BEFORE INSERT: (SELECT status FROM automation_job WHERE id = NEW.job_id) <> 'WAITING'이면 SIGNAL
--                  ← 이미 승격·실행된 노드로 들어가는 간선 추가 금지. 확장 계약의 DB 방어선(§7.3)
--   BEFORE UPDATE / BEFORE DELETE: SIGNAL
```

### 3.8 job_attempt

```sql
CREATE TABLE job_attempt (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    job_id                BIGINT       NOT NULL,
    attempt_no            INT          NOT NULL,
    trigger_kind          VARCHAR(8)   NOT NULL COMMENT 'AUTO | MANUAL',
    worker_instance_id    CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    lease_owner           VARCHAR(100) NOT NULL,
    lease_token           CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    started_at            DATETIME(6)  NOT NULL,
    execution_deadline_at DATETIME(6)  NOT NULL,
    finished_at           DATETIME(6)  NULL,
    outcome               VARCHAR(24)  NULL,
    error_code            VARCHAR(64)  NULL,
    CONSTRAINT pk_job_attempt PRIMARY KEY (id),
    CONSTRAINT uk_attempt_no UNIQUE (job_id, attempt_no),
    CONSTRAINT uk_attempt_owner UNIQUE (job_id, id),
    CONSTRAINT uk_attempt_token UNIQUE (lease_token),
    CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES automation_job (id),
    CONSTRAINT fk_attempt_instance FOREIGN KEY (worker_instance_id) REFERENCES queue_worker_instance (instance_id),
    CONSTRAINT ck_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_attempt_trigger CHECK (trigger_kind IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_attempt_deadline CHECK (execution_deadline_at > started_at),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'RETRYABLE_ERROR',
        'ACTION_REQUIRED', 'FATAL_ERROR', 'OUTCOME_UNKNOWN', 'LEASE_EXPIRED', 'DEADLINE_EXCEEDED',
        'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT ck_attempt_finished CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
-- 트리거:
--   BEFORE UPDATE: OLD.finished_at IS NOT NULL이면 SIGNAL;
--                  job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
--                  started_at, execution_deadline_at 변경 시 SIGNAL
--   BEFORE DELETE: SIGNAL
```

### 3.9 job_event

```sql
CREATE TABLE job_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    run_id        BIGINT       NOT NULL,
    job_id        BIGINT       NULL COMMENT 'NULL = run 수준 이벤트',
    attempt_id    BIGINT       NULL,
    event_type    VARCHAR(40)  NOT NULL,
    from_status   VARCHAR(24)  NULL,
    to_status     VARCHAR(24)  NULL,
    reason        VARCHAR(64)  NULL,
    actor_type    VARCHAR(16)  NOT NULL,
    actor_user_id BIGINT       NULL,
    request_key   VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '관리자 명령의 Idempotency-Key',
    trace_id      VARCHAR(64)  NULL,
    details_json  JSON         NULL COMMENT '비밀·원문 금지',
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_job_event PRIMARY KEY (id),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES content_run (id),
    CONSTRAINT fk_event_job FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT fk_event_attempt FOREIGN KEY (job_id, attempt_id) REFERENCES job_attempt (job_id, id),
    CONSTRAINT fk_event_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_event_type CHECK (event_type REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_event_actor CHECK (actor_type IN ('ADMIN', 'SYSTEM', 'WORKER')),
    CONSTRAINT ck_event_attempt CHECK (attempt_id IS NULL OR job_id IS NOT NULL),
    INDEX ix_event_run (run_id, id),
    INDEX ix_event_job (job_id, id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'job_event is append-only'
```

### 3.10 queue_control, queue_worker_instance

```sql
CREATE TABLE queue_control (
    id                  TINYINT      NOT NULL,
    dispatch_enabled    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '새 claim 허용. 지출 허용(budget_control)과 독립',
    max_running_jobs    SMALLINT     NOT NULL DEFAULT 2 COMMENT 'DB 전역 RUNNING 상한(§6.6)',
    dispatch_changed_at DATETIME(6)  NULL,
    dispatch_changed_by BIGINT       NULL,
    dispatch_reason     VARCHAR(500) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_queue_control PRIMARY KEY (id),
    CONSTRAINT ck_queue_control_singleton CHECK (id = 1),
    -- 2 초과는 ADR 필요
    CONSTRAINT ck_queue_control_max CHECK (max_running_jobs BETWEEN 1 AND 2),
    CONSTRAINT fk_queue_control_by FOREIGN KEY (dispatch_changed_by) REFERENCES admin_user (id)
);
INSERT INTO queue_control (id, dispatch_enabled, max_running_jobs, version, updated_at)
VALUES (1, TRUE, 2, 0, UTC_TIMESTAMP(6));
-- 트리거: BEFORE DELETE → SIGNAL

CREATE TABLE queue_worker_instance (
    instance_id    CHAR(36)     COLLATE utf8mb4_bin NOT NULL COMMENT '프로세스 기동마다 UUID',
    host_label     VARCHAR(100) NOT NULL COMMENT '호스트명 등 진단용(비밀 아님)',
    worker_threads SMALLINT     NOT NULL,
    started_at     DATETIME(6)  NOT NULL,
    last_seen_at   DATETIME(6)  NOT NULL,
    stopped_at     DATETIME(6)  NULL,
    CONSTRAINT pk_queue_worker_instance PRIMARY KEY (instance_id),
    CONSTRAINT ck_worker_threads CHECK (worker_threads BETWEEN 0 AND 8),
    INDEX ix_worker_seen (stopped_at, last_seen_at)
);
-- 트리거: BEFORE DELETE → SIGNAL (보존 정책은 별도)
```

`dispatch_enabled` 기본값을 TRUE로 둔 근거
- T03 큐는 Fake만 실행하고, 비용은 `budget_control.spending_enabled`(기본 false, ADR-061)가 따로 막는다.
- 두 스위치는 독립이다. 큐 정지는 "새 claim 중지"이고, 진행 중인 RUNNING은 계속 heartbeat·완료한다.

### 3.11 queue_command_log

```sql
CREATE TABLE queue_command_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    request_key         VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Idempotency-Key 헤더 값',
    request_fingerprint CHAR(64)     COLLATE utf8mb4_bin NOT NULL COMMENT 'command_type + 경로 ID + 정규화 본문',
    command_type        VARCHAR(32)  NOT NULL,
    target_type         VARCHAR(32)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    actor_user_id       BIGINT       NOT NULL,
    trace_id            VARCHAR(64)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_queue_command_log PRIMARY KEY (id),
    CONSTRAINT uk_queue_command_key UNIQUE (request_key),
    CONSTRAINT fk_queue_command_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_queue_command_key CHECK (request_key REGEXP '^[A-Za-z0-9:._-]{8,128}$'),
    CONSTRAINT ck_queue_command_fp CHECK (request_fingerprint REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_queue_command_type CHECK (command_type IN ('CREATE_TOPIC', 'CREATE_CONTENT', 'CREATE_REVISION',
        'CREATE_RUN', 'CANCEL_RUN', 'RESUME_JOB')),
    INDEX ix_queue_command_target (target_type, target_id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL
```

- 명령 효과와 같은 TX에서 INSERT한다.
- 동시 요청으로 UNIQUE 위반이 나면 재조회해 fingerprint를 비교한다. 같으면 replay, 다르면 409 RESOURCE_CONFLICT.
- 키 공간은 전역이다. 다른 command_type에 같은 키를 쓰면 fingerprint가 달라 409가 된다.
- replay는 **현재 리소스 표현**을 200으로 돌려준다(기존 예약 replay와 같은 의미). 최초 응답 스냅샷 재생은 보장하지 않는다. 비용 정산의 결과 재생 요구(A2-05)는 Codex의 settlement operation이 담당한다.

### 3.12 운영 최소 권한 (`infra/mariadb/prod-app-grants.sql` 추가안)

| 테이블 | 앱 계정 권한 | 근거 |
|---|---|---|
| topic, content_revision, job_dependency, job_event, queue_command_log | SELECT, INSERT | 불변·append-only(트리거와 이중 방어) |
| content, content_run, automation_job, job_attempt, queue_worker_instance | SELECT, INSERT, UPDATE | 상태 행 |
| queue_control | SELECT, UPDATE | 단일 행, migration이 생성 |
| channel_config(기존) | 변경 없음(SELECT, INSERT) | UNIQUE 추가는 migration 계정 |
| job_operation 등 비용 영역 | Codex 설계 | INT-01 |

DELETE는 모든 신규 테이블에 부여하지 않는다. `ProductionGrantsIT` 기대값과 ADR-053 `BINARY_COLLATION_COLUMNS` 허용 목록을 함께 갱신한다. 허용 목록에 추가할 컬럼:
- `topic.normalized_hash`
- `content_revision.revision_hash`
- `content_run.output_manifest_hash`
- `automation_job.job_key`, `automation_job.lease_token`, `automation_job.input_hash`
- `job_attempt.worker_instance_id`, `job_attempt.lease_token`
- `job_event.request_key`
- `queue_worker_instance.instance_id`
- `queue_command_log.request_key`, `queue_command_log.request_fingerprint`

ADR-050에 따라 운영 binlog 환경의 트리거 생성 검증이 배포 전 필요하다.

### 3.13 테이블 생성 순서 (순환 FK 처리)

1. `channel_config` UNIQUE 추가
2. `topic`
3. `content`(revision·run FK 제외)
4. `content_revision`(base_run FK 제외)
5. `content_run`
6. `ALTER content` FK 2개
7. `ALTER content_revision` base_run FK
8. `queue_worker_instance`
9. `automation_job`(current_attempt FK 제외)
10. `job_attempt`
11. `ALTER automation_job` current_attempt FK
12. `job_dependency`
13. `job_event`
14. `queue_control` + 초기 행
15. `queue_command_log`
16. 트리거

---

## 4. 상태 전이표

공통 규칙
- 모든 상태 변경은 §5 프로토콜의 단일 TX에서 일어난다. 상태 UPDATE, attempt 종료, job_event INSERT, (해당 시) run·content 갱신을 함께 처리한다.
- 표에 없는 전이는 409 INVALID_STATE_TRANSITION(API) 또는 내부 오류(엔진)다.
- `cancel` = `content_run.cancel_requested_at IS NOT NULL`
- `ff` = `content_run.fail_fast_at IS NOT NULL`
- `op` = 해당 job의 미해결 operation 분류(§13 INT-04): `NONE`(없음 또는 종료), `PRE`(발신 전, 미발신 증명 가능), `INFLIGHT`(DISPATCHING 이후 결과 미확정)

### 4.1 Job

| # | From | 이벤트 / 조건 | To | status_reason | 부수 효과 |
|---|---|---|---|---|---|
| J01 | (신규) | RUN_CREATED | WAITING | — | 같은 TX에서 선행 없는 노드는 J02 |
| J02 | WAITING | 모든 선행 SUCCEEDED, ¬cancel, ¬ff | READY | — | available_at = DB now |
| J03 | WAITING | 선행 중 하나가 FAILED/SKIPPED | SKIPPED | PREDECESSOR_FAILED | 후행에 연쇄 |
| J04 | WAITING | 선행 중 하나가 CANCELLED | CANCELLED | RUN_CANCELLED | 후행에 연쇄 |
| J05 | WAITING, READY, RETRY_WAIT, ACTION_REQUIRED | RUN_CANCEL | CANCELLED | RUN_CANCELLED | op=PRE면 operation 취소 요청(INT-05) |
| J06 | WAITING, READY, RETRY_WAIT, ACTION_REQUIRED | RUN_FAIL_FAST | SKIPPED | RUN_FAILED_FAST | op=PRE면 operation 취소 요청 |
| J07 | RUNNING, RECOVERY_REQUIRED | RUN_CANCEL | (유지) | — | job.cancel_requested_at = now |
| J08 | READY, RETRY_WAIT | CLAIM(available_at ≤ now, 전역 상한, dispatch_enabled, 채널 ACTIVE, ¬cancel, ¬ff, 선행 전부 SUCCEEDED) | RUNNING | — | attempt 생성, attempt_count+1. pending_manual_resume면 MANUAL로 계산하고 플래그 해제, 아니면 auto_attempts_used+1. run QUEUED→RUNNING |
| J09 | READY, RETRY_WAIT | 채널 PAUSED/ARCHIVED, dispatch_enabled=false | (유지) | — | claim 대상에서만 제외 |
| J10 | RUNNING | COMPLETE(SUCCEEDED), fencing 유효 | SUCCEEDED | — | result_summary 기록. 후행 J02/J03/J04 평가. EXPANDER면 §7.3 |
| J11 | RUNNING | COMPLETE(RETRYABLE), ¬cancel, ¬ff, auto 남음 | RETRY_WAIT | 오류 코드 | available_at = now + backoff(§6.5) |
| J12 | RUNNING | COMPLETE(RETRYABLE), ¬cancel, ¬ff, auto 소진, manual 남음 | ACTION_REQUIRED | AUTO_ATTEMPTS_EXHAUSTED | — |
| J13 | RUNNING | COMPLETE(RETRYABLE), ¬cancel, ¬ff, auto·manual 모두 소진 | FAILED | ATTEMPTS_EXHAUSTED | run fail-fast |
| J14 | RUNNING | COMPLETE(ACTION_REQUIRED), ¬cancel, ¬ff | ACTION_REQUIRED | BUDGET_EXCEEDED / POLICY_VIOLATION / PREREQUISITE_NOT_MET / RETRY_AFTER_TOO_LONG 등 | — |
| J15 | RUNNING | COMPLETE(FATAL) | FAILED | 오류 코드 | run fail-fast |
| J16 | RUNNING | COMPLETE(OUTCOME_UNKNOWN) (requires_operation만 허용) | RECOVERY_REQUIRED | EXTERNAL_OUTCOME_UNKNOWN | cancel 플래그 보존 |
| J17 | RUNNING | COMPLETE(RETRYABLE / ACTION_REQUIRED), cancel | CANCELLED | RUN_CANCELLED | op=PRE면 operation 취소 요청 |
| J18 | RUNNING | COMPLETE(RETRYABLE / ACTION_REQUIRED), ¬cancel, ff | FAILED | RUN_FAILED_FAST_NO_RETRY | — |
| J19 | RUNNING | LEASE_EXPIRED 또는 DEADLINE_EXCEEDED(sweep), op ∈ {NONE, PRE} | J11~J13 / J17 / J18 중 해당 결과 | LEASE_EXPIRED / DEADLINE_EXCEEDED | attempt outcome 기록. 늦은 결과는 증거로만(§6.3) |
| J20 | RUNNING | LEASE_EXPIRED 또는 DEADLINE_EXCEEDED, op = INFLIGHT | RECOVERY_REQUIRED | EXTERNAL_OUTCOME_UNKNOWN | 재호출 금지 |
| J21 | ACTION_REQUIRED | RESUME(관리자, manual 남음, ¬cancel, ¬ff, 채널 ACTIVE, 콘텐츠 비보관) | READY | — | manual_resumes_used+1, pending_manual_resume=TRUE, available_at=now |
| J22 | RECOVERY_REQUIRED | OPERATION_RESOLVED: EXECUTED + 결과 증거 채택 | SUCCEEDED | — | J10과 같은 후행 평가 |
| J23 | RECOVERY_REQUIRED | OPERATION_RESOLVED: NOT_EXECUTED, ¬cancel, ¬ff, auto 남음 | RETRY_WAIT | NOT_EXECUTED_CONFIRMED | 같은 operation 재사용 허용 여부는 INT-07 |
| J24 | RECOVERY_REQUIRED | OPERATION_RESOLVED: NOT_EXECUTED, ¬cancel, ¬ff, auto 소진 | ACTION_REQUIRED 또는 FAILED(manual도 소진) | AUTO_ATTEMPTS_EXHAUSTED / ATTEMPTS_EXHAUSTED | — |
| J25 | RECOVERY_REQUIRED | OPERATION_RESOLVED: NOT_EXECUTED, cancel | CANCELLED | RUN_CANCELLED | — |
| J26 | RECOVERY_REQUIRED | OPERATION_RESOLVED: NOT_EXECUTED, ¬cancel, ff | FAILED | RUN_FAILED_FAST_NO_RETRY | — |
| J27 | RECOVERY_REQUIRED | OPERATION_RESOLVED: EXECUTED_FAILED 또는 결과 채택 불가 | FAILED (cancel이면 CANCELLED) | EXTERNAL_EXECUTION_FAILED | run fail-fast(cancel이 아니면) |
| J28 | RUNNING | HEARTBEAT | (유지) | — | lease 연장(§6.2). 상태 불변 |

불변조건 (Q-INV, §12)
- RECOVERY_REQUIRED ⇒ `requires_operation = TRUE`이고 해당 job에 미해결 operation이 존재
- RUNNING ⇒ 열린 attempt가 정확히 1개(`finished_at IS NULL`이고 attempt_no = current_attempt_no)
- 종료 job은 열린 attempt가 0개
- 비-RUNNING ⇒ lease 컬럼 NULL(CHECK)
- `requires_operation = FALSE`인 executor는 OUTCOME_UNKNOWN을 반환할 수 없다(엔진이 RETRYABLE로 취급하지 않고 계약 위반 FATAL로 기록)

### 4.2 Run

| # | From | 이벤트 / 조건 | To | 부수 효과 |
|---|---|---|---|---|
| R01 | (신규) | RUN_CREATE | QUEUED | content PRODUCING, active_run_id 설정 |
| R02 | QUEUED | 첫 CLAIM | RUNNING | started_at = now |
| R03 | QUEUED, RUNNING | RUN_CANCEL(최초) | (유지) | cancel_requested_at/by/reason 기록, 각 job J05/J07, 이후 R10 평가 |
| R04 | QUEUED, RUNNING | 어떤 job이 FAILED(J13/J15/J27) | (유지) | fail_fast_at = now(최초 1회), 각 job J06, 이후 R10 평가 |
| R10 | QUEUED, RUNNING | EVALUATE: 모든 job 종료 ∧ 해당 run의 모든 operation 종료(INT-05) | SUCCEEDED / CANCELLED / FAILED | 아래 판정. finished_at, content DRAFT, active_run_id = NULL |

R10 판정 순서
1. 모든 job이 SUCCEEDED면 **SUCCEEDED**
2. 그 외, cancel이면 **CANCELLED**
3. 그 외 **FAILED**

- R10 평가는 job 상태를 바꾼 모든 TX의 끝(같은 run 잠금 아래)과 reconciler에서 실행한다.
- 종료 run은 불변이다(트리거).
- 미확정 operation이 남아 있으면 모든 job이 종료여도 run은 비종료(활성 run guard 유지, Codex D01 조건)다. 이 상태는 attention으로 드러난다.

### 4.3 Content

| # | From | 이벤트 / 조건 | To |
|---|---|---|---|
| C01 | (신규) | CREATE_CONTENT(채널 ≠ ARCHIVED, topic이 같은 채널) | DRAFT (revision 1, current_revision_id 설정) |
| C02 | DRAFT | CREATE_REVISION(baseRevisionNo = 현재, hash 변경, 활성 run 없음) | DRAFT (current_revision_id, title, version+1) |
| C03 | DRAFT | CREATE_RUN(revisionNo = 현재, 채널 ACTIVE, 활성 run 없음, Fake 실행기 활성) | PRODUCING (active_run_id) |
| C04 | PRODUCING | 활성 run 종료(R10) | DRAFT (active_run_id = NULL) |
| C05 | DRAFT | ARCHIVE(version 일치) | ARCHIVED (archived_at) |
| — | PRODUCING | CREATE_REVISION / ARCHIVE | 409 INVALID_STATE_TRANSITION |
| — | ARCHIVED | 모든 변경 | 409 INVALID_STATE_TRANSITION(트리거가 최종 방어) |

- 보관 후에도 기존 예약의 정산·조회는 허용한다(ADR-042와 같은 원칙, Codex 영역).
- T06은 REVIEW_REQUIRED, REVISION_REQUIRED, APPROVED를 신규 migration으로 추가한다. docs/08의 content CANCELLED는 폐기한다(T3-ADR-03).

### 4.4 Attention (파생, 저장하지 않음)

| 수준 | 필드 | 계산 |
|---|---|---|
| run | `attention.actionRequired`, `attention.recoveryRequired` | `COUNT(job WHERE run_id = ? AND status = …)` |
| run | `attention.unresolvedOperations` | Codex 제공 집계(INT-05) |
| run | `progress` | total, waiting, ready, running, retryWait, actionRequired, recoveryRequired, succeeded, failed, cancelled, skipped(상태별 개수). `expansionPending` = 종료되지 않은 EXPANDER 존재 |
| content | `activeRun.attention` | 활성 run의 위 값 |
| 전역 | 대시보드·큐 상태 | §8.6 |

### 4.5 기존 docs/08 상태와의 대응 (T3-ADR-02)

| docs/08 | 신규 |
|---|---|
| job PENDING | WAITING(선행 대기) 또는 READY(실행 대기) |
| job BLOCKED + EXTERNAL_OUTCOME_UNKNOWN | RECOVERY_REQUIRED + status_reason EXTERNAL_OUTCOME_UNKNOWN |
| job RUNNING / SUCCEEDED / RETRY_WAIT / FAILED / CANCELLED | 동일 |
| (없음) | ACTION_REQUIRED, SKIPPED |
| content PRODUCING / DRAFT / ARCHIVED | 동일 |
| content CANCELLED | 폐기(run CANCELLED로 표현) |
| content REVIEW_REQUIRED / REVISION_REQUIRED / APPROVED | T06에서 활성화 |

---

## 5. 큐 잠금 및 트랜잭션 프로토콜

### 5.1 트랜잭션 실행기

- 큐 TX는 모두 **최상위 짧은 TX**다. READ COMMITTED이며, 데드락(1213)·잠금 대기 초과(1205)·스냅샷 충돌(1020)은 전체 롤백 후 최초 외 최대 3회 재시도한다.
- 기존 `LockRetryingTransactions`와 같은 규칙이고, 클래스를 재사용하거나 범용 이름으로 옮긴다(구현 결정).
- TX 재시도는 job 시도 횟수를 소모하지 않는다.
- 활성 TX 안에서 큐 TX를 열 수 없다(기존 검사 유지).
- **큐 TX 안에서는 예산 서비스·외부 호출·파일 I/O를 하지 않는다.**
- `innodb_lock_wait_timeout`은 큐 TX에서 세션 단위 5초를 권장한다(측정 후 확정).

### 5.2 잠금 계층

**큐 계층 (확정 방향 12)**
```
queue_control → content → content_run → automation_job (id 오름차순) → job_operation → job_attempt / job_event INSERT
```

규칙
1. 상위 자원을 잡은 뒤에만 하위 자원을 잡는다. 필요 없는 단계는 건너뛸 수 있지만 역순은 금지한다.
2. job 여러 개를 잡을 때는 `SELECT … WHERE id IN (…) ORDER BY id FOR UPDATE` 한 문장으로 잡는다.
3. `queue_control`은 **X 잠금으로 큐 상태 변경을 전역 직렬화**한다(확정 방향 11). 예외는 heartbeat(§6.2)로, job 한 행의 조건부 UPDATE만 하고 상태를 바꾸지 않는다.
4. 조회 API는 잠금을 걸지 않는다.

**예산 계층(기존 ADR-055)과의 교차 — INT-02, Codex 통합 검증 대상**
```
budget_control → channel → budget_policy → budget_bucket → budget_reservation → cost_log INSERT
```

- 제안 규칙: **한 TX가 두 계층을 모두 잡아야 하면 예산 계층(budget_control, channel)을 먼저 잡고 큐 계층을 나중에 잡는다.**
- 큐 계층 자원을 쥔 TX는 이후 channel·budget 자원을 잡지 않는다. 큐 TX 안에서 채널 상태가 필요하면 **잠금 없는 읽기**를 쓴다(§6.1).
- 이 규칙으로 정해지는 교차 경로:

| 경로 | 잠금 순서 | 비고 |
|---|---|---|
| 콘텐츠·revision 생성 | channel S → content X | 큐 상태 변경 아님(queue_control 없음) |
| run 생성 | channel S → queue_control X → content X → … | 채널 ACTIVE 판정과 채널 보관의 경쟁 차단 |
| 채널 pause/archive(기존) | budget_control S → channel X | archive는 활성 run 존재 시 409를 제안(T3-ADR-11). 활성 run 확인은 잠금 없는 읽기로 충분하다. run 생성이 channel S를 잡으므로 직렬화된다 |
| dispatch intent(Codex TX4) | budget_control S → channel S → content_run S → automation_job X(펜싱) → job_operation X | Codex 설계와 통합 검증. queue_control 생략은 하위 집합 순서라 허용 |
| 정산(Codex TX6) | 예산 계층만 | 큐 잠금 없음 |

### 5.3 DB 시각

- 큐의 모든 비교와 기록은 SQL 안의 `UTC_TIMESTAMP(6)`을 쓴다. 애플리케이션이 시각을 계산해 넘기지 않는다. 지연은 `+ INTERVAL ? MICROSECOND`처럼 길이만 넘긴다.
- 예산은 기존 앱 Clock을 유지한다. **두 시각 축을 같은 비교식에 섞지 않는다**(T3-ADR-06).

### 5.4 경로별 프로토콜

각 경로는 "잠금 → 재확인 → 변경 → 이벤트 → (R10 평가)" 순서다.

**P1 topic 생성**
1. channel S. ARCHIVED면 409 PREREQUISITE_NOT_MET.
2. 정규화·hash.
3. INSERT topic + command_log. UNIQUE 위반이 command_log 키 때문이면 replay, 정규화 hash 때문이면 409 DUPLICATE_RESOURCE.

**P2 콘텐츠 생성**
1. channel S. ARCHIVED면 409.
2. topic 소유 확인(복합 FK가 최종 방어).
3. 현재 channel_config 고정(요청 `channelConfigRevisionNo`가 있으면 최신과 같아야 하고, 다르면 409).
4. INSERT content(DRAFT, title) → INSERT revision 1 → UPDATE content.current_revision_id → command_log.

**P2' revision 생성**
1. channel S → content X.
2. 다음 조건을 확인한다.
   - status = DRAFT(PRODUCING·ARCHIVED는 409)
   - `baseRevisionNo` = 현재 revision_no(다르면 409 RESOURCE_CONFLICT)
   - hash 변경(같으면 409 DUPLICATE_RESOURCE)
   - base_run 검증
3. INSERT revision(revision_no = 현재+1) → UPDATE content(current_revision_id, title, version+1, updated_at) → command_log.

**P3 run 생성**
1. channel S. ACTIVE가 아니면 409 PREREQUISITE_NOT_MET.
2. queue_control X → content X.
3. 다음 조건을 확인한다.
   - DRAFT, active_run_id IS NULL
   - 요청 revisionNo = 현재
   - Fake 실행기 활성(설정)
   - pipeline 선택(§7.1)
4. INSERT content_run(QUEUED, run_no = MAX+1).
5. jobs를 모두 WAITING으로 INSERT → edges INSERT(트리거가 WAITING 확인).
6. 선행 없는 노드를 READY로 승격.
7. UPDATE content(status = PRODUCING, active_run_id, version+1).
8. event(RUN_CREATED) + command_log.

**P4 claim (확정 방향 13)**
1. 프로세스 worker permit을 확보한다. 실패하면 대기한다.
2. TX 시작 → queue_control X. dispatch_enabled = FALSE면 종료.
3. `SELECT COUNT(*) FROM automation_job WHERE status = 'RUNNING'` ≥ max_running_jobs면 종료(§6.6).
4. 후보 조회(잠금 없음):
   `SELECT id, run_id FROM automation_job WHERE status IN ('READY','RETRY_WAIT') AND available_at <= UTC_TIMESTAMP(6) ORDER BY available_at, id LIMIT 20`
5. 후보마다:
   1. 채널 상태를 잠금 없이 읽는다. ACTIVE가 아니면 건너뛴다.
   2. content_run X를 잡고 비종료, ¬cancel, ¬ff를 확인한다.
   3. automation_job X를 잡고 status ∈ {READY, RETRY_WAIT}, available_at ≤ now, 선행 전부 SUCCEEDED(재조회)를 확인한다.
   4. 첫 적격 후보 1개만 claim한다.
6. INSERT job_attempt(lease_token = UUID, attempt_no = attempt_count+1, deadline = now + execution_timeout).
7. UPDATE job: RUNNING, lease 컬럼, lease_expires_at = LEAST(now + lease, deadline), current_attempt_no, 카운터.
8. run이 QUEUED면 RUNNING으로 바꾼다. event(CLAIMED).
9. 커밋 후 실행한다. 적격 후보가 없으면 permit을 반납한다.

전역 X 잠금 아래에서는 SKIP LOCKED가 필요 없다. 후보 조회 → 잠금 → 재확인은 READ COMMITTED의 최신 커밋 값을 보도록 하기 위한 것이다.

**P5 heartbeat** — §6.2

**P6 완료 보고**
1. queue_control X → content_run X → automation_job X.
2. fencing 재확인:
   `id = ? AND status = 'RUNNING' AND lease_token = ? AND current_attempt_no = ? AND lease_expires_at > UTC_TIMESTAMP(6)`
   - 불일치면 변경 0건. "늦은 결과"로 반환한다(§6.3).
3. requires_operation이면 job_operation 분류를 읽는다(INT-04).
4. J10~J18 중 해당 전이를 적용하고 attempt를 종료한다.
5. 후행 평가: 후행 job 집합을 id 순으로 X 잠근 뒤 J02/J03/J04를 적용한다.
6. ff·cancel 연쇄(J06/J05), R10 평가 → event.

**P7 sweep (10초)**
1. queue_control X.
2. `SELECT id FROM automation_job WHERE status = 'RUNNING' AND lease_expires_at <= UTC_TIMESTAMP(6) ORDER BY id LIMIT 50`
3. 각 job에 대해:
   1. content_run X → job X.
   2. 재확인: 여전히 RUNNING이고 lease_expires_at ≤ now(heartbeat가 이겼다면 건너뜀).
   3. operation 분류 → J19/J20, attempt outcome(LEASE_EXPIRED 또는 deadline 경과면 DEADLINE_EXCEEDED).
   4. R10 평가.
4. job마다 별도 TX로 처리해 잠금 보유 시간을 짧게 한다.

**P8 run 취소**
1. queue_control X → content X → content_run X → 해당 run의 비종료 job 전부 X(id 순) → job_operation(분류 읽기).
2. 최초 취소면 cancel 컬럼을 기록한다. 이미 취소됐으면 replay.
3. J05/J07 → R10 평가 → event + command_log.
4. 응답은 run이 종료되면 200, 아니면 202.

**P9 재개(resume)**
1. channel S(ACTIVE 확인).
2. queue_control X → content_run X → automation_job X.
3. J21 조건을 확인 → 전이 → event + command_log.

**P10 콘텐츠 보관**
1. content X.
2. status = DRAFT(active_run_id IS NULL), version 일치를 확인 → ARCHIVED.

**P11 reconciler (60초, 부팅 시 1회)**
1. queue_control X.
2. 다음 불일치를 찾아 멱등하게 고친다.
   - 승격 누락: WAITING인데 선행이 모두 SUCCEEDED → J02
   - 연쇄 누락: 선행 실패 → J03/J04
   - 종료 누락: R10 조건 충족인데 run 비종료
   - content 포인터 불일치: PRODUCING인데 run 종료, 또는 그 반대 → **수정하지 않고 오류 이벤트와 경고만 남긴다**(Q-INV-05)
3. run 단위 별도 TX로 처리한다.

**P12 operation 결과 반영 (Codex → 큐)**
1. Codex의 operation 해소 TX는 큐 TX와 분리해 먼저 커밋한다.
2. 큐 쪽은 P6과 같은 잠금 순서로 J22~J27을 적용한다.
3. 큐 반영 전에 crash하면 reconciler가 "operation 종료인데 job이 RECOVERY_REQUIRED"를 찾아 재적용한다(INT-06).

---

## 6. 실행 규칙

### 6.1 획득(claim) 적격 조건

다음을 모두 만족해야 한다.
- 큐: queue_control.dispatch_enabled
- 전역 RUNNING < max_running_jobs
- 프로세스 permit 확보
- 채널 ACTIVE(잠금 없는 읽기)
- run 비종료, ¬cancel, ¬ff
- job: READY/RETRY_WAIT, available_at ≤ DB now, 선행 전부 SUCCEEDED

채널 상태를 잠그지 않는 이유
- 실제 지출 차단은 비용 계층(예약 TX가 budget_control S → channel S 아래에서 재검사, ADR-042)과 dispatch intent TX(INT-03)가 한다.
- claim 단계의 채널 검사는 불필요한 실행을 줄이는 1차 필터다.
- pause 커밋과 claim이 경합해 claim된 job이 생겨도, 비용 단계에서 차단된다(ACTION_REQUIRED, PREREQUISITE_NOT_MET).

### 6.2 Lease, heartbeat, fencing

| 항목 | 값·규칙 |
|---|---|
| lease 길이 | 60초(`AI_STUDIO_QUEUE_LEASE_SECONDS`) |
| heartbeat 주기 | 15초(`AI_STUDIO_QUEUE_HEARTBEAT_SECONDS`). 기동 검증: heartbeat × 3 < lease |
| 실행 기한 | `job_attempt.execution_deadline_at = started_at + execution_timeout_seconds`(job별 고정). lease는 기한을 넘어 연장되지 않는다 |
| heartbeat SQL | `UPDATE automation_job SET lease_expires_at = LEAST(UTC_TIMESTAMP(6) + INTERVAL ? SECOND, ?deadline), heartbeat_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE id = ? AND status = 'RUNNING' AND lease_token = ? AND current_attempt_no = ? AND lease_expires_at > UTC_TIMESTAMP(6) AND UTC_TIMESTAMP(6) < ?deadline` |
| heartbeat 결과 | 1행: 계속. 이어서 `cancel_requested_at`(job·run)을 읽어 worker에 협력적 중단 신호를 준다. 0행: **lease 상실**. worker는 남은 결과를 늦은 결과로만 보고한다 |
| fencing 토큰 | `(lease_token, current_attempt_no)` 쌍. 모든 쓰기 조건식에 포함 |
| 만료 판정 | sweep만 한다(P7). `lease_expires_at ≤ DB now` |
| 강제 탈취 | 만료되지 않은 lease는 어떤 경로로도 회수하지 않는다(관리자 포함) |

heartbeat는 전용 스케줄러에서 돈다. worker 스레드가 멈춰도 heartbeat는 계속될 수 있지만, 실행 기한이 상한이라 **최대 execution_timeout_seconds + 60초 + sweep 주기** 안에 회수된다(CDR-14 해결).

### 6.3 늦은 결과

- fencing이 실패한 완료 보고(lease 만료·대체됨)는 job 상태를 바꾸지 않는다.
- requires_operation = FALSE인 job: 결과를 버리고 `LATE_RESULT_DISCARDED` 이벤트만 남긴다. 내부 job은 다시 실행해도 안전하다는 계약을 Fake/T04 executor가 지킨다.
- requires_operation = TRUE인 job: 결과를 **해당 attempt에 귀속된 불변 증거**로 Codex의 operation 증거 저장소에 접수한다(INT-07, A2-04). 채택은 활성 owner 또는 reconciler가 CAS로 하고, 큐는 P12로 반영한다.

### 6.4 취소

- run 단위 취소만 지원한다. job 단위 취소 API는 없다(CDR-20).
- RUNNING job은 협력적으로 중단한다. 다음 heartbeat에서 신호를 받는다. 실행기가 중단하면 COMPLETE(CANCELLED 의미 → J17)로 보고하고, 이미 끝났으면 실제 결과로 보고한다(성공은 성공, J10).
- 이미 발생한 비용과 operation은 보존한다. 미확정 operation이 있으면 run은 비종료로 남는다.
- 취소는 되돌릴 수 없다(재개 불가). 재제작은 새 run으로 한다.

### 6.5 재시도와 재개

| 항목 | 규칙 |
|---|---|
| 자동 실행 한도 | `max_auto_attempts` 기본 **3(최초 포함)**. pipeline 정의가 job 유형별로 정하고 job 행에 고정 저장한다. T03 Fake 기본 3. T07 유료 job은 별도 승인 |
| 수동 재개 한도 | `max_manual_resumes` 기본 2, job 행 고정 |
| backoff | `delay_n = min(300s, 5s × 2^(n−1))`, n = 실패한 자동 실행 순번. equal jitter: `delay_n / 2 + rand(0, delay_n / 2)` |
| Retry-After | 지연이 backoff보다 길면 그 값을 쓴다. 3600초를 넘으면 ACTION_REQUIRED(RETRY_AFTER_TOO_LONG) |
| 분류 | executor·비용 계층이 `SUCCEEDED / RETRYABLE / ACTION_REQUIRED / FATAL / OUTCOME_UNKNOWN`으로 분류해 넘긴다. HTTP 상태만으로 분류하지 않는다(A2-08). 예산 기간 재계획 소진은 내부 사유 `BUDGET_PERIOD_REPLAN_EXHAUSTED`로 RETRYABLE, 발신 후 타임아웃은 OUTCOME_UNKNOWN(INT-08) |
| 비즈니스 오류 | BUDGET_EXCEEDED, POLICY_VIOLATION, PREREQUISITE_NOT_MET은 ACTION_REQUIRED. 자동 재시도하지 않는다 |
| 재개 API | `POST /jobs/{jobId}/retry`는 **ACTION_REQUIRED → READY 재개 전용**(J21). 다른 상태는 409 INVALID_STATE_TRANSITION. 한도 초과는 422 POLICY_VIOLATION |
| RECOVERY_REQUIRED | 재개 API 대상이 아니다. Codex의 증거·판정 절차(attestation) 결과로만 바뀐다(J22~J27) |
| 재개 사전 검사 | 채널 ACTIVE, 콘텐츠 비보관, ¬cancel, ¬ff. 예산 여유는 사전 검사하지 않는다(실제 판정은 예약 TX). 재개 후 같은 사유로 다시 ACTION_REQUIRED가 되면 재개 1회가 소모된 것이다(UI에 고지) |

### 6.6 상한 구분 (확정 방향 15)

| 상한 | 정의 | 강제 위치 |
|---|---|---|
| **DB 유효 lease 상한** | `COUNT(status = 'RUNNING') ≤ queue_control.max_running_jobs(= 2)` | claim TX, queue_control X 아래. 여러 프로세스가 붙어도 성립한다. RUNNING은 만료됐지만 아직 sweep되지 않은 lease를 포함하므로 "유효 lease 수 ≤ RUNNING 수 ≤ 2"다 |
| **프로세스 worker 상한** | `AI_STUDIO_QUEUE_WORKER_THREADS`(기본 2)개의 permit | 프로세스 메모리. DB 상한보다 크게 설정해도 DB 상한이 우선한다 |
| **실제 외부 실행 상한** | 발신했지만 결과 미확정인 operation 수 | Codex dispatch intent TX(INT-09). lease가 만료된 이전 worker가 아직 외부 호출 중일 수 있어 lease 상한으로는 보장되지 않는다. T03 Fake에서는 측정만 하고, 상한 값은 T07에서 승인한다 |

`queue_worker_instance`로 활성 프로세스(최근 `last_seen_at` < 3 × heartbeat)가 2개 이상인지 탐지한다. 탐지되면 경고 로그와 `GET /queue/status`의 `activeWorkerInstances`로 노출한다. 차단하지는 않는다. DB 상한이 안전을 보장하기 때문이다.

### 6.7 시각과 설정

환경변수(ADR-013 `AI_STUDIO_` 접두사)
- `AI_STUDIO_QUEUE_WORKER_THREADS`(2)
- `AI_STUDIO_QUEUE_POLL_MILLIS`(1000)
- `AI_STUDIO_QUEUE_LEASE_SECONDS`(60)
- `AI_STUDIO_QUEUE_HEARTBEAT_SECONDS`(15)
- `AI_STUDIO_QUEUE_SWEEP_SECONDS`(10)
- `AI_STUDIO_QUEUE_RECONCILE_SECONDS`(60)
- `AI_STUDIO_QUEUE_SHUTDOWN_GRACE_SECONDS`(30)
- `AI_STUDIO_FAKE_EXECUTOR_ENABLED`(false)
- `AI_STUDIO_FAKE_SCENARIO_API_ENABLED`(false)

설정 규칙
- 기동 시 범위를 검증하고, 어기면 기동을 중단한다(변수 이름만 출력).
- test profile은 lease·heartbeat를 짧게 쓴다(예: 2초/0.5초).
- prod profile은 `FAKE_*`가 true면 기동을 중단한다(Fake가 운영 데이터에 섞이는 것 방지).

### 6.8 스케줄러와 생명주기 (확정 방향 17)

| 구성 | 실행기 | 비고 |
|---|---|---|
| Dispatcher(poll 1초, 유휴 시 최대 1초 대기) | 전용 `ScheduledExecutorService` + worker 스레드 풀 | permit 기반 |
| Heartbeat(15초) | 전용 단일 스레드 스케줄러 | worker 실행과 독립, 기한 상한 |
| Sweep(10초), Reconciler(60초) | Spring `@Scheduled`(단일 인스턴스, 고정 지연, 겹침 없음) | 각 TX는 queue_control X로 직렬화되므로 다중 프로세스에서도 안전 |
| Quartz JDBC JobStore | **도입하지 않음** | 지속 일정 기능(예약 제작 T09 또는 예약 게시 T08)과 함께 도입(T3-ADR-07) |

부팅 순서
1. worker_instance 등록
2. (Codex) 미확정 operation 진단
3. reconciler 1회
4. sweep 1회
5. dispatcher 시작

종료 순서
1. claim 중지
2. 진행 중 작업을 최대 grace 동안 대기(heartbeat 유지)
3. 미완료 작업은 heartbeat를 중지하고 lease 자연 만료에 맡긴다(다음 기동이나 다른 프로세스의 sweep이 처리)
4. `stopped_at` 기록

종료 시 비용 예약을 자동으로 해제하지 않는다.

---

## 7. DAG 생성 및 확장 계약

### 7.1 Pipeline 정의와 선택

- pipeline은 **코드에 정의된 불변 버전**이다(`pipelineVersion` 문자열, 예 `fake-shorts-v1`). 한 번 배포한 버전의 노드·간선·job별 정책(max_auto_attempts, max_manual_resumes, execution_timeout_seconds, requires_operation)은 바꾸지 않는다. 바꾸려면 새 버전을 만든다.
- 서버가 `(execution_mode, content_type)`으로 현재 버전을 고른다. 클라이언트는 고르지 않는다(확정 방향 20).
- 애플리케이션 기동 시 모든 등록 pipeline을 검증하고, 실패하면 기동을 중단한다.
  - 위상 정렬 성공(순환 없음)
  - job_key 유일
  - 노드 ≤ 64(정적), 간선 ≤ 256
  - BARRIER는 EXPANDER를 직접 선행으로 가짐
  - 정책 값 범위

**T03 Fake pipeline (C 단계, 비용 없음)**
```
research → script → storyboard → visual ─┐
                              └→ narration ─┴→ assemble → finalize
```
- 모든 노드는 TASK, `requires_operation = FALSE`다.
- `visual`·`narration`은 fan-out/fan-in을 검증한다.

**D 단계(비용 연동)**
- `visual`·`narration`을 `requires_operation = TRUE`로 바꾼 `fake-*-v2`를 추가한다(INT-01).
- v1은 그대로 남는다. 버전 불변성을 보여주는 사례다.

**Fake 시나리오**
- 허용 목록: SUCCESS, TRANSIENT_FAIL_ONCE, TRANSIENT_FAIL_ALWAYS, FATAL, ACTION_REQUIRED, SLOW_PAST_DEADLINE, LOST_RESPONSE_AFTER_DISPATCH, CRASH_BEFORE_COMPLETE.
- 적용 대상은 `targetJobKey`(선택)로 정한다.
- `AI_STUDIO_FAKE_SCENARIO_API_ENABLED = true`(local·test 한정)일 때만 API로 지정할 수 있다. 아니면 SUCCESS로 고정되고, 지정하면 422 POLICY_VIOLATION이다.
- Fake 실행기는 외부 네트워크를 호출하지 않는다.

### 7.2 생성

P3에서 다음을 한 TX로 처리한다.
1. job을 전부 WAITING으로 INSERT
2. 간선 INSERT(트리거가 후행 WAITING 확인)
3. 루트를 READY로 승격

DB 제약
- 복합 FK가 cross-run 간선을 차단한다.
- CHECK가 자기 간선을 차단한다.
- 트리거가 수정·삭제를 차단한다.

순환
- 기동 시 pipeline 검증(위상 정렬)과 생성 시 재검증으로 막는다.
- 생성 후 간선 추가는 §7.3 확장 계약으로만 가능하고, 그 규칙이 구성상 순환을 막는다.

### 7.3 EXPANDER → CHILDREN → BARRIER 계약 (스키마·검증은 T03, 실행은 T04~T05)

**pipeline 선언**
- 확장 노드 E(node_kind = EXPANDER)와 장벽 노드 B(node_kind = BARRIER)를 선언한다. 정적 간선 E → B가 필수다.
- E의 확장 명세:
  - 자식 job_type 목록과 자식 간 간선 패턴(장면마다 `visual → narration` 등)
  - `max_children`(장면 수 상한)
  - 자식별 정책(시도 한도·기한·requires_operation)

**확장 실행** — E의 성공 완료 TX(P6) 안에서만, 같은 run 잠금 아래에서 한다.
1. E의 결과(자식 개수와 각 자식의 입력 요약)를 검증한다. `max_children`를 넘으면 E를 FATAL(EXPANSION_LIMIT_EXCEEDED)로 처리한다.
2. 자식 job들을 WAITING, `spawned_by_job_id = E`로 INSERT한다. `job_key = <E.job_key>/<group>:<index 3자리>/<type>`.
3. 간선을 INSERT한다. 허용 형태는 셋뿐이다.
   - `E → 자식`
   - `자식 → 자식`(같은 확장 그룹 안에서 명세 패턴만)
   - `자식 → B`
4. E를 SUCCEEDED로 바꾸고, 선행이 모두 성공한 자식을 READY로 승격한다. B는 자식이 남아 있으므로 WAITING을 유지한다. 자식이 0개면 B를 바로 승격한다.

**순환 불가 근거(구성)**
- 새 간선의 후행은 항상 새 자식이거나 B다. 둘 다 WAITING이고, 트리거가 WAITING 노드로 들어가는 간선만 허용한다.
- E는 이미 SUCCEEDED라 새 선행을 받을 수 없다. 새 노드가 기존 노드(E 이전)의 조상이 될 수 없다.
- 자식 간 간선은 명세 패턴(정적 DAG) 안에서만 생긴다.

**상한**
- run당 job ≤ 500, 간선 ≤ 2000(T05 측정 후 확정).
- 넘으면 확장을 거부한다(E는 FATAL).

**멱등**
- E 성공은 fencing으로 한 번만 커밋된다.
- 재처리가 와도 `UNIQUE(run_id, job_key)`가 중복 INSERT를 차단한다.

**진행률**
- 종료되지 않은 EXPANDER가 있으면 `expansionPending = true`이고, 전체 개수가 늘어날 수 있다.

**T03 구현 범위**
- `node_kind`, `spawned_by_job_id`, 트리거, pipeline 검증기의 EXPANDER/BARRIER 규칙, 단위 테스트(검증기 거부 사례)
- 엔진의 확장 실행 함수는 T04에서 첫 EXPANDER(storyboard)와 함께 구현한다.

---

## 8. API 명세 (OpenAPI 0.4.0 후보, `/api/v1` 접두사)

### 8.1 공통 계약 (기존 유지)

- 세션 쿠키 + 상태 변경 요청은 `X-XSRF-TOKEN`
- 오류 `ErrorResponse`(19개 코드)
- ID·금액은 문자열, 시각은 UTC ISO 8601
- 목록은 `page`/`size`(기본 20, 최대 100), 정렬은 서버 기준 + id tie-break
- 평탄 스키마 + `additionalProperties: false`(ADR-062)
- **비멱등 POST는 `Idempotency-Key` 헤더**(`components.parameters.IdempotencyKey` 재사용, 패턴 `^[A-Za-z0-9:._-]{8,128}$`)
  - 최초 성공은 201(생성) 또는 202/200(명령)
  - 같은 키·같은 내용의 replay는 현재 표현을 200으로 반환
  - 같은 키·다른 내용은 409 RESOURCE_CONFLICT
  - 본문에 `requestKey`는 두지 않는다
- optimistic lock은 `VersionRequest.version`(integer), revision 생성은 `baseRevisionNo`(channel config와 같은 패턴)
- 직접 status PATCH는 금지

### 8.2 Topics

| Method / Path | 요청 | 응답 | 오류 |
|---|---|---|---|
| GET `/topics` | query `channelId`(필수), `q`, `page`, `size` | 200 `TopicPage` | 400, 401 |
| POST `/topics` | 헤더 Idempotency-Key. `{channelId, title(1~255), note?(≤500)}` | 201 `Topic` | 400, 401, 403, 404(채널), 409 DUPLICATE_RESOURCE(details: `{field:"title", reason:"DUPLICATE_TOPIC:<topicId>"}`), 409 PREREQUISITE_NOT_MET(채널 보관) |
| GET `/topics/{topicId}` | — | 200 `Topic` | 404 |

`Topic`: `{id, channelId, title, normalizedTitle, note|null, contentCount, createdBy, createdAt}`

### 8.3 Contents · Revisions

| Method / Path | 요청 | 응답 | 오류 |
|---|---|---|---|
| GET `/contents` | `channelId?`, `contentType?`, `status?`, `topicId?`, `q?`, `page`, `size` (정렬 id desc) | 200 `ContentSummaryPage` | 400 |
| POST `/contents` | Idempotency-Key. `{channelId, topicId, contentType, workingTitle, brief, language?, targetDurationSeconds, settings?, channelConfigRevisionNo?, changeReason?}` | 201 `ContentDetail` | 400 VALIDATION_FAILED(길이 범위 등), 404, 409 PREREQUISITE_NOT_MET(채널 보관), 409 RESOURCE_CONFLICT(설정 revision 불일치) |
| GET `/contents/{contentId}` | — | 200 `ContentDetail` | 404 |
| POST `/contents/{contentId}/archive` | `VersionRequest {version}` | 200 `ContentDetail` | 409 INVALID_STATE_TRANSITION(PRODUCING·ARCHIVED), 409 RESOURCE_CONFLICT(version) |
| GET `/contents/{contentId}/revisions` | `page`, `size` (revision_no desc) | 200 `ContentRevisionPage` | 404 |
| GET `/contents/{contentId}/revisions/{revisionNo}` | — | 200 `ContentRevision` | 404 |
| POST `/contents/{contentId}/revisions` | Idempotency-Key. `{baseRevisionNo, workingTitle, brief, language, targetDurationSeconds, settings?, baseRunId?, changeReason?}` | 201 `ContentRevision` | 409 RESOURCE_CONFLICT(baseRevisionNo), 409 INVALID_STATE_TRANSITION(PRODUCING·ARCHIVED), 409 DUPLICATE_RESOURCE(NO_CHANGES), 400(baseRunId 무효) |

스키마
- `ContentSummary`: `{id, channelId, topicId, contentType, title, status, currentRevisionNo, activeRun: RunBadge|null, lastRun: RunBadge|null, updatedAt}`
- `RunBadge`: `{id, runNo, status, executionMode, attention{actionRequired, recoveryRequired}}`
- `ContentDetail`: `{id, channelId, topicId, contentType, title, status, version, currentRevision: ContentRevision, activeRun: RunSummary|null, lastRun: RunSummary|null, createdBy, createdAt, updatedAt, archivedAt|null}`
- `ContentRevision`: `{id, contentId, revisionNo, channelConfigRevisionNo, baseRunId|null, workingTitle, brief, language, targetDurationSeconds, settings|null, revisionHash, hashVersion, changeReason|null, createdBy, createdAt}`
- 예산 요약(`budgetSummary`)은 Codex 스키마를 `ContentDetail`에 평탄화 필드로 추가한다(INT-11).

### 8.4 Runs

| Method / Path | 요청 | 응답 | 오류 |
|---|---|---|---|
| POST `/contents/{contentId}/runs` | Idempotency-Key. `{revisionNo, fakeScenario?: {code, targetJobKey?}}` | 201 `RunDetail` | 409 INVALID_STATE_TRANSITION(활성 run 존재·보관), 409 RESOURCE_CONFLICT(revisionNo가 현재 아님), 409 PREREQUISITE_NOT_MET(채널 비활성·Fake 실행기 비활성), 422 POLICY_VIOLATION(시나리오 API 비활성) |
| GET `/contents/{contentId}/runs` | `page`, `size` (run_no desc) | 200 `RunSummaryPage` | 404 |
| GET `/runs/{runId}` | — | 200 `RunDetail` | 404 |
| POST `/runs/{runId}/cancel` | Idempotency-Key. `{reason(1~500)}` | 202 `RunDetail`(미종료) / 200(종료) | 409 INVALID_STATE_TRANSITION(이미 종료), 409 RESOURCE_CONFLICT(다른 사유로 같은 키) |
| GET `/runs/{runId}/jobs` | `status?`, `page`, `size` (id asc) | 200 `JobSummaryPage` | 404 |
| GET `/runs/{runId}/events` | `page`, `size` (id desc) | 200 `JobEventPage` | 404 |

- `RunSummary`: `{id, contentId, revisionNo, runNo, status, executionMode, pipelineVersion, cancelRequestedAt|null, failFastAt|null, createdAt, startedAt|null, finishedAt|null, progress, attention, expansionPending}`
- `RunDetail` = RunSummary + `{revisionId, cancelReason|null, requestedBy, outputManifestHash|null, unresolvedOperations(INT-05)}`
- `pipelineVersion`, `executionMode`는 응답 전용이다.

### 8.5 Jobs

| Method / Path | 요청 | 응답 | 오류 |
|---|---|---|---|
| GET `/jobs` | `status?`, `channelId?`, `jobType?`, `page`, `size` (id desc) | 200 `JobSummaryPage` | 400 |
| GET `/jobs/{jobId}` | — | 200 `JobDetail` | 404 |
| GET `/jobs/{jobId}/attempts` | `page`, `size` (attempt_no desc) | 200 `JobAttemptPage` | 404 |
| GET `/jobs/{jobId}/events` | `page`, `size` | 200 `JobEventPage` | 404 |
| POST `/jobs/{jobId}/retry` | Idempotency-Key. `{reason(1~500)}` | 200 `JobDetail` | 409 INVALID_STATE_TRANSITION(ACTION_REQUIRED 아님), 409 PREREQUISITE_NOT_MET(채널·콘텐츠), 422 POLICY_VIOLATION(재개 한도) |

- `JobDetail`: `{id, runId, contentId, channelId, jobKey, jobType, nodeKind, spawnedByJobId|null, requiresOperation, status, statusReason|null, attemptCount, autoAttemptsUsed, maxAutoAttempts, manualResumesUsed, maxManualResumes, availableAt, leaseExpiresAt|null, heartbeatAt|null, cancelRequestedAt|null, predecessorJobIds[], errorCode|null, errorMessage|null, resultSummary|null, operation: OperationSummary|null(INT-11), createdAt, updatedAt, finishedAt|null}`
- 응답에 `leaseToken`, `leaseOwner`는 넣지 않는다. 내부 fencing 값이기 때문이다. 진단용 instance 정보는 attempt의 `workerInstanceId`만 노출한다.
- 초안의 `POST /jobs/{id}/cancel`은 삭제한다. 초안의 `/operations*`는 Codex API다.

### 8.6 큐 상태 · 대시보드

| Method / Path | 요청 | 응답 | 오류 |
|---|---|---|---|
| GET `/queue/status` | — | 200 `{dispatchEnabled, maxRunningJobs, version, running, ready, retryWait, actionRequired, recoveryRequired, oldestReadyAgeSeconds|null, activeWorkerInstances: [{instanceId, hostLabel, workerThreads, startedAt, lastSeenAt}], generatedAt}` | 401 |
| POST `/queue/control/dispatch` | `{enabled, reason(1~500), version}` (`SpendingChangeRequest`와 같은 형태) | 200 queue status | 409 RESOURCE_CONFLICT(version) |
| GET `/dashboard/summary` (기존) | — | 기존 필드 불변 + `production: {producingContents, runningJobs, actionRequiredJobs, recoveryRequiredJobs}` | — |

- 대시보드 필드 추가는 기존 클라이언트를 깨지 않는 확장이다. 응답 스키마가 `additionalProperties: false`이므로 생성 타입 재생성과 계약 테스트 갱신이 필요하다.

### 8.7 docs/04 기준선과의 대응 (T3-ADR-08)

| docs/04 | 신규 |
|---|---|
| `POST /contents {channelId, topic, title, format}` | `POST /contents {channelId, topicId, contentType, workingTitle, …}` |
| `POST /contents/{id}/produce` | `POST /contents/{id}/runs` |
| `POST /contents/{id}/revisions {baseRevisionId, changes}` | `{baseRevisionNo, …}` |
| `POST /jobs/{id}/retry` | 경로 유지, ACTION_REQUIRED 재개 전용 |
| Idempotency-Key | 유지·확대 |

---

## 9. UI 변경안

디자인 시스템(ADR-063, `docs/design/UI_DESIGN_SYSTEM.md`)과 공통 컴포넌트(`frontend/src/ui`, `frontend/src/layout`)만 사용한다. 새 의존성·임의 색은 금지한다. 세션 세대 보호(ADR-066)를 재사용한다.

| 화면 | 위치 | 내용 |
|---|---|---|
| 메뉴 | `layout/nav.ts` | docs/12의 "Topics", "Production" 활성화. 큐 상태는 Production 하위 |
| 소재 목록·등록 | `/topics` | 채널 필터(필수), 검색, 등록 대화상자. 중복 409이면 기존 소재 링크를 표시 |
| 콘텐츠 목록 | `/contents` | 채널·유형·상태·소재 필터, 서버 페이지. 상태 배지와 활성 run 배지(attention 수) |
| 콘텐츠 생성 | 대화상자 | ACTIVE 채널, 소재, 유형, 제목, 지시문, 목표 길이(채널 설정 범위 안내). 채널·유형은 생성 후 변경 불가 안내 |
| 콘텐츠 상세 | `/contents/:id` | 탭 구성은 아래 별도 설명 |
| 실행 상세 | `/runs/:id` | 진행률 미터(성공/전체, `expansionPending`이면 "확장 후 늘어날 수 있음"), 상태별 개수, job 목록(선행 표시), 이벤트, 취소 |
| 작업 상세 | `/jobs/:id` | 상태·사유, 시도 한도(자동 n/3, 재개 n/2), lease 만료 시각(상대·절대), attempt 목록, 이벤트, 오류 요약. ACTION_REQUIRED면 "재개" 버튼, RECOVERY_REQUIRED면 Codex 복구 화면 링크 |
| 큐 상태 | `/production/queue` | 실행 중/상한, 대기 개수, 가장 오래된 대기 시간, 활성 프로세스 목록(2개 이상이면 경고), dispatch 토글 |
| 대시보드 | 기존 | production 카드 4개 추가(기존 ADR-052 카드 유지) |

콘텐츠 상세 탭
- 개요: 현재 revision, 활성 run 요약
- 설정 이력: revision 목록(기존 `RevisionHistory` 재사용), 새 revision(`baseRevisionNo` 포함)
- 실행: run 목록, 실행 시작
- 비용: Codex 스키마

상태 라벨(한국어)과 `StatusBadge` 톤(기존 토큰)
- Job: 대기(WAITING, neutral), 실행 대기열(READY, info), 실행 중(RUNNING, info), 재시도 대기(RETRY_WAIT, warning), 조치 필요(ACTION_REQUIRED, warning), 복구 필요(RECOVERY_REQUIRED, danger), 성공(SUCCEEDED, success), 실패(FAILED, danger), 취소됨(CANCELLED, neutral), 건너뜀(SKIPPED, neutral)
- Run: 대기(QUEUED), 진행 중(RUNNING), 성공, 실패, 취소됨
- Content: 초안(DRAFT), 제작 중(PRODUCING), 보관됨(ARCHIVED)

규칙
1. **Fake 구분**: `executionMode = FAKE`인 run·job·결과에는 "모의 실행" 배지와 "실제 영상·비용이 아닙니다" 안내를 붙인다. 성공한 Fake run을 검수·게시 가능처럼 보이게 하지 않는다.
2. **위험 작업 확인 대화상자**: 실행 시작(D 단계부터 비용 예약 가능 — 문구 포함), run 취소(되돌릴 수 없음, 진행 중 작업은 협력적 중단, 발생 비용 보존), 재개(한도 1회 소모, 같은 사유면 다시 조치 필요), dispatch 토글.
3. **미확정을 실패로 단정하지 않는다**: RECOVERY_REQUIRED·미확정 operation이 있으면 run이 "종료되지 않음 — 복구 필요"로 보인다.
4. 진행률은 성공 수 / 전체 수다. 실패·취소·생략을 성공으로 세지 않고, 종료 수는 별도로 표시한다.
5. **폴링**: 활성 run이 있는 화면만 3초 폴링한다. 숨김 탭에서는 중지한다(`refetchIntervalInBackground: false`). 세션 세대가 바뀌면 이전 응답·토스트를 폐기한다. 401은 1회 세션 만료 처리, 429는 Retry-After 표시.
6. **시각 표시**: 저장 UTC. 콘텐츠·run·job 화면은 채널 timezone으로 보여주고 UTC를 보조로 표시한다(AGENTS). 큐 상태 화면은 사용자 timezone.
7. ID는 문자열이다. 서버 정렬을 보존한다(Number 비교 금지, ADR-066).
8. 모든 데이터 화면은 로딩·빈 상태·오류·권한·세션 만료를 처리한다. 라이트·다크·모바일을 함께 검증한다.

---

## 10. ADR 변경 제안 (미승인 — 번호는 통합 담당자가 DECISIONS 최신(ADR-066) 이후로 배정)

| 임시 ID | 제안 | 대체·보완하는 기존 결정 | 영향 |
|---|---|---|---|
| T3-ADR-01 | Revision = 불변 입력 사양, 산출물 run 귀속, `base_run_id`, 승인 = (revision_id, run_id, manifest_hash) | ADR-002 해석, ADR-016 usage 기준, docs/06 "장면 수정 시 새 revision" 해석 | §2, §3.4~3.5, T04~T06 |
| T3-ADR-02 | Job 상태 10종, `status_reason`, CANCEL_REQUESTED 없음, 취소 = `cancel_requested_at` | docs/08 job 상태·BLOCKED 정의 대체 | §4.1, §4.5 |
| T3-ADR-03 | Run 5종 + attention 파생, Content T03 = DRAFT/PRODUCING/ARCHIVED, content CANCELLED 폐기 | docs/08 content 상태 일부 | §4.2~4.4 |
| T3-ADR-04 | 활성 run 1개 = `content.active_run_id` + 복합 FK + CHECK. 미확정 operation이 남으면 run 비종료 유지 | 초안 D01 구체화 | §3.3, §4.2 |
| T3-ADR-05 | 큐 잠금 계층(queue_control → content → content_run → automation_job → job_operation → attempt/event), queue_control 전역 직렬화, 예산 계층 선행 교차 규칙 | ADR-055 보완(교차 규칙은 Codex와 통합 승인) | §5 |
| T3-ADR-06 | 큐 시각은 DB `UTC_TIMESTAMP(6)`, 예산은 앱 Clock, 비교식 혼용 금지 | ADR-017 보완 | §5.3, §6.7 |
| T3-ADR-07 | **Quartz JDBC JobStore 도입을 지속 일정 기능(T08 예약 게시 또는 T09 예약 제작) 단계로 연기.** T03 poll·heartbeat는 전용 스케줄러, sweep·reconciler는 Spring Scheduler. 도입 시 Flyway 관리·자동 초기화 금지 원칙은 유지 | ADR-001(Quartz 기준선)의 시점, ADR-022의 "구현 T03", docs/08의 "lease 회수는 Quartz" 문장 | §6.8 |
| T3-ADR-08 | API 대응: `/produce` → `/runs`, `/jobs/{id}/retry`는 ACTION_REQUIRED 재개 전용, 비멱등 POST는 Idempotency-Key 헤더, job 단위 취소 없음, pipelineVersion 서버 선택 | docs/04 기준선 | §8 |
| T3-ADR-09 | T03 범위: topic 최소 테이블 포함, content_scene·claim은 T04, media는 T05, ai_request_log는 T07(operation 참조) | docs/03 구현 단계 표기, ADR-015·016 구현 시점 | §2.4 |
| T3-ADR-10 | 시도 정책: 자동 3회(최초 포함), 수동 재개 2회, job별 고정 저장, backoff 5s·×2·cap 300s·equal jitter, Retry-After 3600s 초과는 ACTION_REQUIRED | docs/08 "3회(제안)" 확정, 초안 D07(5회) 기각 | §6.5 |
| T3-ADR-11 | 채널 보관은 활성 run이 있으면 409 INVALID_STATE_TRANSITION. pause는 허용(새 claim 중지) | ADR-042 보완(T02 ChannelService 변경 수반) | §5.2 |
| T3-ADR-12 | 상한 구분: DB RUNNING 상한 `queue_control.max_running_jobs`(1~2), 프로세스 permit, 외부 실행 상한(T07 승인). 2 초과는 별도 ADR | 초안 D06 구체화 | §6.6 |
| T3-ADR-13 | Fake 실행: `execution_mode` 컬럼, T03 CHECK = 'FAKE', prod profile에서 Fake 설정이 켜져 있으면 기동 중단, 시나리오 API는 local·test 한정 | ADR-061 보완 | §3.5, §7.1 |
| T3-ADR-14 | 명령 멱등: `queue_command_log`(전역 키 공간, 효과와 같은 TX, replay = 현재 표현) | ADR-026의 멱등 원칙을 큐 명령에 확장 | §3.11 |

---

## 11. CDR-01~25 대응표

| CDR | 심각도 | 처리 | 근거·위치 |
|---|---|---|---|
| 01 BLOCKED 의미 충돌 | HIGH | **해결** | WAITING / RECOVERY_REQUIRED + status_reason, docs/08 대응표(§4.5, T3-ADR-02) |
| 02 산출물 귀속·승인 대상 | HIGH | **해결(계약)**, 산출물 테이블은 T04~T06 | §2.2, base_run_id·output_manifest_hash(§3.4~3.5), T3-ADR-01 |
| 03 장면 fan-out | HIGH | **해결(계약·스키마)**, 실행 T04~T05 | §7.3, node_kind·spawned_by_job_id·간선 트리거 |
| 04 취소 이중 표현·누락 전이 | HIGH | **해결** | CANCEL_REQUESTED 제거, J05~J07·J17~J20·J25(§4.1) |
| 05 사람 판정 경로 | HIGH | **인터페이스 해결, 판정 API는 Codex 설계** | J22~J27 매핑, P12, INT-06 |
| 06 CONTENT_TYPE 정책 미적용 | MEDIUM | **Codex 영역으로 이관** | 도메인 측은 content 복합 UNIQUE·job의 content_id/channel_id 제공(INT-12, INT-13) |
| 07 멱등 키 전달 방식 | MEDIUM | **해결** | Idempotency-Key 헤더, queue_command_log(§8.1, §3.11) |
| 08 fan-in 승격 경쟁·잠금 순서 | MEDIUM | **해결** | queue_control 전역 직렬화 + run 잠금 하 후행 평가(P6), reconciler(P11) |
| 09 활성 run 포인터 | MEDIUM | **해결** | content.active_run_id + 복합 FK + CHECK(§3.3) |
| 10 Run·Content 상태 모순 | MEDIUM | **해결** | Run 5종 + attention, Content 3종(§4.2~4.4) |
| 11 복합 FK 대상 키 | MEDIUM | **해결** | channel_config UNIQUE(channel_id, id), content UNIQUE 2종, revision.channel_id, job 비정규화(§3) |
| 12 Quartz 역할 | MEDIUM | **해결(ADR 변경 제안)** | 전용 스케줄러·Spring Scheduler, JDBC JobStore 연기(T3-ADR-07) |
| 13 단일 인스턴스 미강제 | MEDIUM | **해결** | DB RUNNING 상한(queue_control X), worker_instance 탐지(§6.6) |
| 14 멈춘 worker lease 연장 | MEDIUM | **해결** | execution_deadline_at, heartbeat 조건·LEAST 상한(§6.2) |
| 15 재시도 횟수 충돌 | MEDIUM | **해결(ADR 제안)** | 자동 3 + 수동 2, job별 고정(T3-ADR-10) |
| 16 시각 원천 혼재 | MEDIUM | **해결(ADR 제안)** | 큐 DB 시각, 예산 앱 Clock, 혼용 금지(T3-ADR-06) |
| 17 topic·scene 범위 | MEDIUM | **해결** | topic 최소 테이블 T03, scene·claim T04(T3-ADR-09) |
| 18 job_operation ↔ ai_request_log | MEDIUM | **보류(T07)** | operation 참조 provider 상세로 정의 방향만 고정(INT-10). T03에는 provider가 없음 |
| 19 title 이중 원천 | LOW | **해결** | 현재 revision 복제본, 같은 TX 갱신, 정합성 검사(§2.3) |
| 20 job 취소·retry 의미 | LOW | **해결** | run 단위 취소만, retry = ACTION_REQUIRED 재개(§6.4~6.5) |
| 21 claim 인덱스 순서 | LOW | **해결** | priority 제거, `(status, available_at, id)`(§3.6). EXPLAIN 실측은 T03-C |
| 22 권한·bin 목록·트리거 | LOW | **해결** | §3.12, 각 테이블 트리거 명세 |
| 23 language 길이·hash 정규화 | LOW | **해결** | VARCHAR(16)+채널과 같은 정규식, hash_version 1 규칙·Fingerprints 이전(§3.4) |
| 24 pipelineVersion·configId·대시보드·결과 저장 | LOW | **해결** | 서버 선택, channelConfigRevisionNo, production 필드, result_summary_json 불변(§8, §3.6) |
| 25 배정·경로 정합 | INFO | **보류(통합 담당자)** | TASKS.md에 T03-A1/A2/A3 행 기록 필요. 초안 미추적 파일 정리는 사용자 결정. 공통 파일은 이 작업에서 수정하지 않음 |

---

## 12. 필수 테스트 및 합격 조건

원칙
- 통합 테스트는 Testcontainers 격리 DB만 쓴다(ADR-024). 사용자 개발 DB는 쓰지 않는다.
- 경쟁 테스트는 sleep 대신 두 커넥션 barrier·latch와 명시적 fault injection을 쓴다.
- 각 시나리오 끝에서 job, attempt, event, run, content의 불변조건(Q-INV)을 assert한다.
- 미실행은 통과로 기록하지 않는다.

**불변조건 검사 Q-INV (모든 통합 테스트의 공통 사후 검사)**
- Q-INV-01: RUNNING ⇔ lease 컬럼 NOT NULL. RUNNING ⇒ 열린 attempt가 정확히 1개
- Q-INV-02: 종료 job ⇒ 열린 attempt 0개, finished_at NOT NULL
- Q-INV-03: content.title = 현재 revision.working_title
- Q-INV-04: PRODUCING ⇔ active_run_id NOT NULL ⇔ 그 run 비종료
- Q-INV-05: 종료 run ⇒ 모든 job 종료 ∧ 모든 operation 종료(INT-05)
- Q-INV-06: `COUNT(RUNNING) ≤ max_running_jobs`
- Q-INV-07: RECOVERY_REQUIRED ⇒ requires_operation ∧ 미해결 operation 존재
- Q-INV-08: job_event 이벤트 순서가 전이표와 일치(허용되지 않은 from→to 없음)

| ID | 시나리오 | 합격 조건 |
|---|---|---|
| DB-01 | 빈 DB V1→신규 migration, V5 상태 DB→신규 migration | 성공. MigrationConventionTest(이전 체크섬 불변, collation·bin 목록) 통과. 기존 T02 데이터 보존 |
| DB-02 | 불변 트리거 | topic·revision·dependency·event·command_log의 UPDATE/DELETE가 SQLSTATE 45000. content의 channel_id·content_type·topic_id 변경 거부. 종료 run·job 변경 거부. 종료 attempt 변경 거부 |
| DB-03 | 복합 FK | 다른 콘텐츠의 revision·run을 current/active로 지정, 다른 채널 config·topic, 다른 run의 간선·attempt 이벤트가 각각 FK 오류 |
| DB-04 | CHECK | PRODUCING+active NULL, ARCHIVED+archived_at NULL, RUNNING+lease NULL, execution_mode='LIVE', pending_manual_resume+비READY, 카운터 초과가 각각 거부 |
| DB-05 | 운영 권한 | ProductionGrantsIT: 신규 테이블 권한 목록 일치. 앱 계정 DELETE 불가 |
| DM-01 | topic 중복 | 대소문자·전각·연속 공백 차이는 409 DUPLICATE_RESOURCE + 기존 ID. 다른 채널은 허용 |
| DM-02 | 콘텐츠 생성 원자성 | content·revision 1·포인터가 한 TX. 중간 실패 주입 시 잔여 행 0. 같은 키 replay 200 동일 ID, 다른 본문 409 |
| DM-03 | 동시 revision 생성 | 같은 baseRevisionNo로 2요청 barrier → 1건 201, 1건 409. revision_no 연속, title 일치 |
| DM-04 | revision hash | 같은 입력이면 같은 hash(필드 순서·settings 키 순서 무관). 변경 없음 409. Fingerprints 이전 후 예산 fingerprint 회귀 동일 |
| DM-05 | 동시 run 생성 | 같은 콘텐츠에 다른 키 2요청 barrier → 정확히 1 run. 같은 키 2요청 → 같은 run |
| Q-01 | DAG 검증 | 순환·자기 간선·키 중복·BARRIER 규칙 위반 pipeline은 기동 실패. 생성 후 간선 INSERT(후행 비WAITING)는 트리거가 거부 |
| Q-02 | fan-out/fan-in 승격 | visual·narration 동시 완료(barrier) → assemble 정확히 1회 READY. 승격 누락 0 |
| Q-03 | claim 경쟁·상한 | 프로세스 2개 상당(dispatcher 2 + 스레드 4)으로 READY 10개 → 동시 RUNNING이 언제나 ≤ 2(샘플링 + DB 사후 확인). 같은 job 이중 claim 0 |
| Q-04 | heartbeat ↔ sweep 경합 | lease 만료 직전 heartbeat와 sweep barrier → 둘 중 하나만 성공. 상태가 일관됨 |
| Q-05 | fencing | 만료 후 이전 worker의 완료·heartbeat가 변경 0건. 새 attempt 상태 보존. 늦은 결과 이벤트 기록 |
| Q-06 | 실행 기한 | SLOW_PAST_DEADLINE: heartbeat가 계속돼도 deadline + lease 이내에 sweep이 DEADLINE_EXCEEDED 처리 |
| Q-07 | 재시도 | TRANSIENT_FAIL_ALWAYS: 자동 3회 후 ACTION_REQUIRED. 재개 2회 후 FAILED. backoff 범위(지터 하한·상한), Retry-After 존중·3600초 초과 처리 |
| Q-08 | 오류 분류 | BUDGET_EXCEEDED·POLICY_VIOLATION·PREREQUISITE_NOT_MET은 ACTION_REQUIRED(자동 재시도 0회). `BUDGET_PERIOD_REPLAN_EXHAUSTED`는 RETRYABLE. 같은 HTTP 503이라도 발신 후 타임아웃은 OUTCOME_UNKNOWN(INT-08 fake) |
| Q-09 | 취소 | 각 상태(WAITING·READY·RETRY_WAIT·ACTION_REQUIRED·RUNNING·RECOVERY_REQUIRED)에서 취소 → 전이표대로. 취소와 완료 barrier → 성공이 먼저면 SUCCEEDED, 이후 run CANCELLED. 미확정 operation이 있으면 run 비종료 |
| Q-10 | fail-fast | FATAL → 미시작 job SKIPPED. RUNNING은 계속 진행. 그 성공의 후행도 SKIPPED. run FAILED |
| Q-11 | 상태 전이 전수 | J01~J28·R01~R10·C01~C05 각 1건 이상, 금지 전이는 409 또는 내부 거부 |
| Q-12 | 재기동 | CRASH_BEFORE_COMPLETE 후 프로세스 재시작 → sweep이 회수, 자동 재시도. requires_operation job의 미확정은 RECOVERY_REQUIRED(재호출 0) |
| Q-13 | reconciler | 승격·종료 누락을 인위로 만들면(테스트 전용 직접 SQL) reconciler가 멱등 복구. content 포인터 불일치는 수정하지 않고 경보 |
| Q-14 | 큐 정지 | dispatch_enabled=false이면 새 claim 0, RUNNING은 정상 완료. 채널 PAUSED이면 해당 채널만 claim 중지 |
| Q-15 | Fake 격리 | prod profile + FAKE_*=true이면 기동 실패. 시나리오 API 비활성이면 422. Fake 실행 중 외부 네트워크 호출 0(테스트 보안 관리자 또는 프록시 차단으로 확인) |
| Q-16 | 시각 | 모든 큐 시각이 DB 시각으로 기록(JVM 시계를 이동시켜도 lease 판정 불변) |
| API-01 | 계약 | 신규 엔드포인트 전부 OpenApiContractTest(요청·응답 검증, 미정의 필드 거부, 평탄 스키마 검사). Idempotency-Key 누락 400, 형식 오류 400 |
| API-02 | 인증·CSRF | 비인증 401, CSRF 누락 403, 공통 오류 형식 |
| UI-01 | 화면 | 소재·콘텐츠·실행·작업·큐 화면의 로딩·빈 상태·오류·권한·세션 만료, 한국어 라벨, 라이트·다크·모바일 |
| UI-02 | 안전 | 위험 작업 확인 대화상자, 모의 실행 배지, 미확정 표시, 세션 세대 변경 시 이전 응답 폐기, 폴링 숨김 탭 중지 |
| E2E-01 | 브라우저 | mock E2E(기존 방식): 소재 → 콘텐츠 → 실행 → 성공. 취소. 조치 필요 → 재개 |
| REG-01 | 회귀 | T02 백엔드 단위·통합, 프론트 lint·typecheck·test·build, OpenAPI 재생성 diff, gitleaks, CI 전 job 통과 |
| PERF-01 | 직렬화 비용 | queue_control X 보유 시간 p95, claim 처리량을 READY 1k·10k에서 측정해 보고(허용치는 측정 후 사용자 승인. 안전성 완화 금지) |

---

## 13. 비용 영역과 통합이 필요한 인터페이스 (Codex 병렬 설계)

| ID | 인터페이스 | 큐(이 문서)가 제공·요구하는 것 | Codex 결정 필요 |
|---|---|---|---|
| INT-01 | `job_operation` 소유 | 요구: `job_id` FK, `(job_id, operation_slot)` UNIQUE(A2-06 논리 효과 슬롯), `created_attempt_id` → `job_attempt(job_id, id)` 복합 FK. 잠금 순서상 job 다음 | 상태 집합, 키·fingerprint, 테이블 상세 |
| INT-02 | 교차 잠금 순서 | 제안: 예산 계층(budget_control, channel)을 먼저, 큐 계층을 나중에. 큐 자원 보유 TX는 예산 자원을 잡지 않음(§5.2) | 동의 여부와 dispatch·정산 TX 실제 순서 |
| INT-03 | dispatch intent 펜싱 | 요구: dispatch intent TX가 `automation_job` 행을 X로 잡고 `status='RUNNING' AND lease_token AND current_attempt_no AND lease_expires_at > DB now`, job·run의 `cancel_requested_at IS NULL`, `fail_fast_at IS NULL`을 확인한 뒤에만 DISPATCHING 커밋. 이 커밋이 실행 허가의 선형화 시점(A2-03) | 지출 차단·채널 pause 재검사 포함 여부와 잠금 |
| INT-04 | operation 분류 함수 | 요구: 큐가 읽는 3분류 `NONE / PRE / INFLIGHT`(§4 표기). PRE = 미발신이 증명 가능. INFLIGHT = DISPATCHING 이후 미확정 | 각 operation 상태의 분류 매핑 |
| INT-05 | run 종료 조건 | 요구: "run의 모든 operation 종료" 판정 함수와 `unresolvedOperations` 집계. 미해결이면 run 비종료(R10) | operation 종료 정의, PRE 취소(예약 해제) 처리 주체와 시점 |
| INT-06 | 판정 결과 → job 전이 | 제공: J22~J27 매핑과 P12. 요구: operation 해소 결과 enum `EXECUTED_RESULT_ADOPTED / NOT_EXECUTED / EXECUTED_FAILED`와 해소 TX 커밋 후 큐 반영 호출(멱등). crash 시 reconciler 재적용 | attestation API, 증거 필수 항목 |
| INT-07 | 늦은 결과·증거 귀속 | 제공: `(job_id, attempt_id, lease_token)` 식별자. 요구: 증거 저장(불변)과 CAS 채택 규칙. NOT_EXECUTED 후 같은 operation 재사용 여부 | 증거 테이블, 채택 권한 |
| INT-08 | 실행 결과 분류 | 요구: 비용 호출 결과를 `SUCCEEDED / RETRYABLE / ACTION_REQUIRED / FATAL / OUTCOME_UNKNOWN`과 내부 사유 코드(예 `BUDGET_PERIOD_REPLAN_EXHAUSTED`)로 반환. HTTP 상태만으로 분류하지 않음 | 예산 서비스 내부 사유 노출 방식(API 계약 변경 없이) |
| INT-09 | 외부 실행 상한 | 요구: 발신 미확정 operation 수 기반 상한을 dispatch intent TX에서 강제(T07 값 승인) | 집계 방식 |
| INT-10 | ai_request_log(T07) | 방향: operation 참조 provider 상세 | T07 |
| INT-11 | API 스키마 결합 | 제공: `JobDetail.operation`, `RunDetail.unresolvedOperations`, `ContentDetail.budgetSummary` 자리. 평탄 스키마로 합성 | `OperationSummary`, `ContentBudgetSummary` 필드 |
| INT-12 | 예산 테이블 FK | 제공: `content UNIQUE(id, channel_id)`, `UNIQUE(id, content_type)`. Codex migration이 bucket·reservation FK와 CHECK 해제 담당. **migration 순서: 도메인 테이블 migration이 비용 FK migration보다 먼저** | 번호·순서 합의 |
| INT-13 | 예약 명령 입력 | 제공: job의 `content_id`, `channel_id`, content_type(조인). LIFETIME 예약에 필요한 콘텐츠 식별을 job 기준으로 전달 | ReserveCommand 확장, fingerprint |
| INT-14 | 보관·정지와 정산 | 제공: content ARCHIVED, channel PAUSED·ARCHIVED 상태 의미. 비용 정산은 상태와 무관하게 허용(ADR-042) | 확인 |
| INT-15 | 부팅 순서 | 제공: §6.8 2단계 슬롯(operation 진단) | 진단 절차 |

---

## 14. 사용자·통합 결정이 필요한 미결 사항

1. T3-ADR-01~14의 채택 여부(특히 07 Quartz 연기, 10 시도 정책, 11 채널 보관 제한)
2. INT-02 교차 잠금 순서, INT-03 dispatch 선형화 시점에 대한 Codex 설계와의 합의
3. 수치 확정(측정 후)
   - run당 job·간선 상한(500/2000)
   - queue_control X 보유 시간 허용치(PERF-01)
   - `innodb_lock_wait_timeout` 큐 세션 값
4. `dispatch_enabled` 기본값 TRUE 수용 여부(§3.10 근거)
5. `queue_worker_instance` 보존·정리 정책
6. CDR-25: TASKS.md 배정 기록과 미추적 초안 파일의 처리

이 문서는 설계 수정안이다. 사용자 최종 승인 전에는 구현 착수, migration 작성·적용, 커밋·푸시를 하지 않는다.

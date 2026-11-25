# T03-A4 비용 안전성 및 영속 복구 설계 수정안

2026-11-25 KST · 기준 main `47ef69731a599c570846c1482dcabf8ead8fe2e4` · 설계 승인 대기

근거는 [A1 리뷰](../../reviews/T03_CLAUDE_DESIGN_REVIEW.md), [A2 리뷰](../../reviews/T03_CODEX_DESIGN_REVIEW.md), [기존 제약](../T03_PLAN.md), [승인 ADR](../../../DECISIONS.md)이다. A1의 CDR-04/05(취소 의도와 불명 결과), attempt 소유권 및 run 잠금 제안을 반영한다. 도메인·DAG 세부 계약은 A3와 통합한다.

## 1. 발견 사항 대응

| 발견 | 기준선 근거 | 해결 명세 |
|---|---|---|
| A2-01 | BudgetMapper.reservationBucketIssues는 subject 없는 DAY/MONTH만 유효로 판정 | §2–3의 기대 집합과 레거시 분기, §9 양방향 검사 |
| A2-02 | V5의 no-content CHECK와 (policy_id, subject_key, period_start) UNIQUE | §2–3의 단일 LIFETIME bucket, 복합 FK와 정책 집합 동결 |
| A2-03 | BudgetReservationService의 독립 reserve TX, 종료 직후 연결 공백 | §4–6의 직렬화 제어 행, 송신 허가 커밋 및 회수 |
| A2-04 | 초안의 job 우선 claim과 run 우선 집계, lease 만료 후 응답 | §4의 공통 순서와 §7의 불변 증거 |
| A2-05 | OpenAPI resolve/adjustments 및 replayEvent의 현재 예약 조회 | §8의 정산 의도 guard와 확정 결과, 기존 쓰기 차단 |
| A2-06 | V5 키 형식·유일성 및 Fingerprints, 예산 서비스 키 재생 | §3·7의 논리 슬롯, 정규화한 본문 해시, 이벤트 순번 고정 |
| A2-07 | BudgetControlService.runConsistencyCheck의 control X 아래 전체 스캔 | §9의 측정 예산, 검사 실패 처리 및 CR-02 복구 |
| A2-08 | 기간 재계획 소진 503과 외부 결과 불명 503 | §6의 단계·커밋 증거 기반 분류 |

## 2. LIFETIME 정책 선택과 적용 시점

1. 신규 콘텐츠 예약은 콘텐츠 행에서 채널·유형을 읽는다. 호출자의 값과 불일치하면 409로 종료한다. 채널은 ACTIVE, 콘텐츠는 예약 가능 상태여야 한다. 유형과 채널은 콘텐츠 생성 이후 불변이다. 아카이브 후 기존 정산·조회는 허용한다.
2. SYSTEM DAY/MONTH 활성 정책은 각각 정확히 하나가 필요하다. CHANNEL DAY/MONTH는 기존 방식으로 해당 채널의 활성 정책을 모두 적용한다. CONTENT_TYPE은 LIFETIME으로 해당 유형의 활성 전역 정책과 해당 채널 활성 정책의 합집합을 사용한다. 둘 다 활성일 때 두 한도를 각각 검사한다. 최초 예약 시 비활성 정책은 집합에서 제외하며 활성 후보가 0개면 전제 미충족이다. 활성 0원 정책은 한도 0원이다. 신규 유료 예약은 양수만 허용한다. 지출 차단·정합성 오류·FX 전제는 기존 계약을 유지한다.
3. 첫 예약이 성공적으로 커밋할 때 콘텐츠별 LIFETIME 정책 ID 집합을 동결한다. RELEASED·환불·취소·revision/run 변경 뒤에도 동결은 유지한다. 활성 정책 후보와 동결 집합이 다르면 신규 예약을 차단한다. 정책 생성·활성화·비활성화는 control X 안에서 동결 콘텐츠의 적용 집합을 바꾸는 경우 409로 거부한다. 전역 변경은 채널 전체, 채널 변경은 해당 채널을 확인한다. 집합이 달라지는 활성 정책을 콘텐츠 비용 이력에 소급하거나 기존 집합을 자동 승계하지 않는다. 긴급 차단은 spending_enabled를 사용한다.
4. 정책 금액 개정은 같은 policy ID의 기존 bucket 한도와 현재 한도 revision만 갱신한다. bucket ID, 누적액, 예약액, 기존 링크·스냅샷은 유지한다. 한도 이하로 재조정될 때까지 신규 예약만 차단하며 초과 실비도 원장에 남긴다. 날짜 경계 변경은 DAY/MONTH에서 기존 동작을 유지한다. LIFETIME 시간대는 UTC 고정이다. T02에서 저장된 유형 정책의 시간대는 이력에 남기고 LIFETIME 경계를 정하는 데 사용하지 않는다.
5. 모든 예약은 마지막 필요한 락 취득 후 확정한 단일 UTC 결정 시각에 기간과 환율을 검증한다. 일/월 경계가 바뀌면 전체 롤백 후 다시 계획한다. 동일 금액을 각 적용 bucket에 반영하지만 정책 간 합계를 실제 비용으로 표시하지 않는다. 실제 비용 총계는 원장을 한번만 집계하고, 정책별 사용량은 각 한도로 보여 준다.

## 3. 테이블과 V5 호환

아래는 논리·물리 제약 명세다. ID는 양수 signed BIGINT, 시각은 UTC DATETIME(6), 식별 키는 VARCHAR(128) utf8mb4_bin, SHA-256은 BINARY(32) 또는 동일 비교를 보장하는 CHAR(64) utf8mb4_bin으로 고정한다. 금액과 환율은 V5의 DECIMAL 스케일을 보존한다. API의 ID·금액은 문자열이다. 필수 FK는 RESTRICT다. 잠금 대상의 불변 식별자는 최초 INSERT 이후 변경을 금지한다.

| 테이블 | 주요 컬럼 및 제약 |
|---|---|
| content | A3 소유. UNIQUE(id, channel_id, content_type). channel_id와 유형은 NOT NULL 및 불변 |
| budget_policy | V5의 CONTENT_TYPE 기존 전역/채널 지원과 `content_id IS NULL` 유지. 정책 스코프·대상·기간 불변. LIFETIME 직접 CONTENT 정책은 범위 밖 |
| budget_bucket | content_id, content_channel_id, content_type은 셋 모두 NULL 또는 셋 모두 NOT NULL. 복합 FK → content. 정책 ID+bucket ID 지정을 위한 UNIQUE(policy_id,id). nullable generated lifetime_content_key=content_id, UNIQUE(policy_id,lifetime_content_key). 기존 UNIQUE(policy_id,subject_key,period_start) 및 DAY/MONTH 조회 유지. 콘텐츠 키 존재 시 §3 sentinel CHECK 적용. 참조 정책의 scope/type/채널 일치는 예약/변경 경로와 정합성 검사로 강제 |
| content_budget_policy | content_id, channel_id, content_type, policy_id, first_applied_at. PK(content_id,policy_id), 콘텐츠 복합 FK, 정책 FK. 기존 변경·삭제 금지. (content_type,channel_id,content_id) 인덱스는 정책 상태 변경 가드에 사용. 입력은 첫 성공 예약 TX에 한정 |
| budget_reservation | V5에 content_type과 link_contract_version 추가. v0은 T02 및 비콘텐츠 경로, content_id·content_type NULL. v1은 content_id·channel_id·content_type NOT NULL, 콘텐츠 복합 FK. 예약 key/fingerprint/FX 및 created_at 불변. v1에서 기대 집합 누락은 ERROR |
| reservation_expected_bucket | reservation_id, bucket_id, policy_id, policy_revision_no, applied_limit_krw, period_start/end, decided_at. PK(reservation_id,bucket_id), UNIQUE(reservation_id,policy_id), FK 예약, FK(policy_id,bucket_id), FK(policy_id,policy_revision_no). 결정 시 잠긴 bucket에서 실제 사용한 한도와 revision을 복사한다. 이 행은 append-only. 시간대는 필요 시 당시 bucket 값도 복사한다 |
| budget_reservation_bucket | V5 PK·FK·불변 유지. v1 기대 행과 실제 링크는 같은 예약 커밋에 INSERT. 애플리케이션과 검사에서 집합 일치 강제 |
| job_operation | operation_id UUID, job_id, effect_slot, operation_seq, created_attempt_id, request schema version, immutable payload·fingerprint, reservation_request_key, reservation_id nullable, state, cancel_intent_at, dispatch_id nullable, version, timestamps. UNIQUE(job_id,effect_slot), UNIQUE(job_id,operation_seq), UNIQUE(reservation_request_key), UNIQUE(reservation_id), 복합 FK(job_id,created_attempt_id) → attempt. 실행 경로에서 mutable 상태는 CAS. 상태/updated_at/ID 복구 인덱스. 슬롯은 run/단계/대상에서 결정하고 A3 job 귀속으로 검증 |
| budget_reservation_intent | request_key PK, operation_id UNIQUE FK, request fingerprint, immutable command/ownership, phase OPEN/RESERVED/VOID/CLOSE_REQUESTED/RELEASED, nullable reservation_id UNIQUE FK, opens/closes/version. DB 직렬화의 기준이며 FK가 가리키는 operation 식별 행은 물리 삭제 금지. OPEN/VOID에는 reservation_id NULL. RESERVED 및 그 후 예약이 존재한 단계는 NOT NULL |
| operation_dispatch | dispatch_id UUID PK, operation_id UNIQUE FK(T03은 효과당 허가 한 건), job_id, attempt_id, lease_token snapshot, request fingerprint, approved_at. attempt 소유권 복합 FK와 operation 소유권 FK. 불변. HTTP 호출 객체는 이 행을 새로 커밋한 in-memory 실행자에게만 반환 |
| operation_evidence | evidence_id, dispatch_id FK, source, external reference, payload hash, 종류, 수신 DB 시각, artifact hash/ref와 sanitized metadata. UNIQUE(dispatch_id,source,canonical_digest). append-only. 증거 채택은 operation의 CAS 및 별도 append-only event로 남김 |
| operation_event_intent | operation_id FK, event_seq, event_key UNIQUE, entry_type, immutable canonical body/fingerprint, 상태, cost_log_id nullable, 확정 결과 snapshot, version. PK(operation_id,event_seq). 원장 이벤트와 완료 결과를 기록한 뒤 해당 결과 필드 불변. 현재 진행 건 (state,updated_at) 인덱스 |
| settlement_operation | UUID PK, reservation_id FK, creation_request_key UNIQUE, claimed prior receipt nullable, canonical body·fingerprint, event_key UNIQUE, status, requested_by FK, created_at, terminal result snapshot, cost_log_id nullable FK, version. UNIQUE(reservation_id,id)와 (reservation_id,status,created_at,id) 인덱스. payload와 확정 결과는 각각 생성/확정 뒤 불변 |
| settlement_guard | reservation_id PK FK, operation_id UNIQUE, 복합 FK(reservation_id,operation_id) → settlement_operation. 미완결 의도 있을 때 한 행. 해제는 실행 확정 또는 미실행이 락 아래 증명된 취소와 같은 TX에서만 가능 |
| operation_attestation | attestation_id, operation_id FK, idempotency_key UNIQUE, fingerprint, actor_user_id FK, decided_at, reason, evidence ref/hash, billing_outcome, artifact_outcome. 두 판정 값은 독립 enum. append-only. 감사를 위한 audit_event도 같은 TX에서 추가 |
| budget_control 검사 확장 | active_check_id, 검사 상태 RUNNING/FAILED, 검사 세대. 이전 consistency_status 및 최근 완료 결과를 별도 보존. 시작 및 게시 CAS는 control X 아래 수행 |
| consistency_check_run / consistency_issue | 검사 UUID, 실행 상태, 시작·완료 시각, 총 오류 건수, 기준 시점과 검사 버전 / 검사 ID FK, issue_code, entity_type/id, 정규화한 차이. 조회용 샘플/페이지는 제한하며 오류 개수와 판정은 전체를 기준으로 한다 |

LIFETIME 기간은 **[2000-01-01 00:00:00.000000, 9999-12-31 23:59:59.999999) UTC** 고정 제안이다. content_id NOT NULL인 bucket은 양 경계 및 timezone UTC를 CHECK로 강제한다. 이 범위 밖의 신규 콘텐츠 예약은 명시적으로 거절한다. DB sentinel을 API·UI의 실제 날짜 또는 다음 리셋으로 노출하지 않는다. PeriodCalculator 및 subject_key=0 기간 조회는 DAY/MONTH 전용으로 유지하고 LIFETIME은 policy_id+content_id로 조회한다. 기존 범위 필터·정렬은 period kind/subject를 분리한다. 활성 LIFETIME bucket의 금액 개정은 해당 policy subject의 모든 기존 bucket을 대상으로 하며 DAY/MONTH 변경과 같은 락 순서를 사용한다. 4자리 최대 날짜의 JDBC/MyBatis round-trip, DB CHECK, 엄격 모드, DST와 UI 직렬화는 §10에서 검증한다. sentinel 없이 별도 테이블을 두는 대안은 기간 의미가 명확하나 기존 cost delta 구현과 조회를 이중화한다.

V1~V5는 해시를 보존한다. 승인된 V6 이후 단계에서 새 소유 키와 테이블을 만들고 예약/버킷의 두 no-content CHECK를 새 제약으로 교체한다. 기존 link와 원장은 그대로 둔다. T02 예약에 가상의 콘텐츠·정책 이력을 채우지 않는다. 새 경로 활성화 전에 DDL·writer·정합성 검사 버전을 맞춘다. 구버전 writer와 혼재한 상태에서 v1 예약을 활성화하지 않는다. V5 업그레이드와 V1 신규를 각각 검증한다. V1/V2 collation, V3 감사 불변, V4 채널/설정 FK, V5 키·원장 append-only 권한을 보존한다.

## 4. 잠금 계약과 실행 허가

| 작업 | 취득 순서 및 경계 |
|---|---|
| 일반 큐 | queue_control → content → content_run → automation_job → job_operation → attempt/event. 채널 락도 필요하면 **queue_control 전**에 channel. 후보는 비잠금 검색 후 정순으로 재검증. job을 먼저 잠근 뒤 run 확보 금지 |
| 콘텐츠 예약 | budget_control S → 예약 의도 X → channel S → 적용 policy X(ID 오름차순) → bucket X(ID 오름차순) → reservation → 기대 행·링크·event INSERT. 신규 bucket은 모든 관련 policy 락 이후 생성한다. 콘텐츠 소유권은 불변 FK로 보호한다. 실행 가능 상태의 최종 확인은 허가 TX가 담당 |
| 정책·지출·채널 변경 | 기존 budget_control X 선행. 필요한 channel/policy/bucket 순서 유지. 큐 상태를 함께 변경하면 비용 락 이후 큐 순서를 따른다. 변경 경로는 예약·허가에 선형화된다 |
| 실행 허가·미송신 회수 결정 | budget_control S → 예약 의도 X → channel S → 필요한 policy, bucket, reservation → queue_control → content → run → job → job_operation → dispatch/attempt/event. 불필요한 락 생략. 비용 서비스는 이 TX에서 호출하지 않는다 |
| 정산 | budget_control S → 연결된 bucket X(ID 오름차순) → reservation X → settlement_guard/settlement_operation 또는 operation_event_intent → cost_log·audit INSERT. 자동/관리자 정산을 같은 reservation 경계에서 직렬화. 기존 정산은 spending/채널 차단으로 거절하지 않는다 |
| 관리자 의도 등록·취소 | reservation X → guard/operation → audit. 이 경로에서 bucket/control을 후취득하지 않는다. 원장 적용은 별도 최상위 TX |
| 증거 접수 | 이미 커밋된 불변 dispatch 식별자로 append. 큐/비용 가변 상태 잠금 없이 INSERT. 채택은 별도 큐 TX |

정렬은 실제 락 순서다. ORDER BY가 엔진의 행 잠금 순서를 보장한다고 가정하지 않는다. 필요하면 정렬한 PK를 하나씩 잠그고 재검증한다. SQL 종류별 InnoDB FK 검사·유일 인덱스의 암묵 락과 계획을 격리 환경에서 확인한다. 복합 허가 경로는 A3의 queue_control 선행 규칙에 **큐 내부 순서**라는 범위를 부여한다. 이미 queue_control을 잡은 코드가 budget_control/channel을 얻는 경로는 금지한다. 취소는 큐만 잠그고 의도를 기록한 후 비용 연결·해제를 다른 TX에서 수행한다. 예산 변경에서 큐 반영이 필요 없는 경우 기존 비용 잠금만 갖는다.

LockRetryingTransactions의 최상위/READ COMMITTED, deadlock·timeout의 롤백 후 재시도를 유지한다. 새 예약·정산 진입점은 이 경계를 열고 participant 로직을 그 안에서 호출한다. 외부 TX에서 이를 열거나 예산 로직을 REQUIRES_NEW로 감싸는 방식은 금지한다. 후자는 바깥 TX의 락을 유지한 상태로 새 연결이 기다릴 수 있다. 허가 트랜잭션은 독립 경계이며 외부 호출이 없다.

허가 TX는 차단/ERROR, 필수 정책 활성 및 현재 적용 집합, 예약 상태와 링크, 채널·콘텐츠·run·job 취소, 일치하는 owner/token과 DB UTC 기준 유효 lease를 검증한다. 예약에서 보장한 금액을 다시 예약하지 않는다. 중간에 한도가 낮아져 이미 보유한 예약의 집행을 허용할지는 별도 결정이 필요하다. 기본 제안은 해당 bucket의 spent+reserved가 개정 한도를 넘으면 신규 허가를 차단하는 것이다. 이 확인에 쓰는 정책/bucket/reservation 락은 큐 순서 전에 취득한다. 잠긴 budget 상태의 재검증과 DISPATCHING, dispatch_id INSERT를 한 TX에서 수행하고 **성공이 명확한 커밋**을 허가 시점으로 정의한다. DB UTC 판정은 락 하의 마지막 조건부 갱신에서 수행한다. 커밋 응답 불명 또는 재시작 후 행 조회만으로 송신 권한을 재발급하지 않는다.

차단 커밋 이후 신규 허가는 0건이다. 이전에 허가된 실행은 차단 커밋 뒤에 실제 송신될 수 있다. 송신 직전 프로세스 내 재검사는 노출 시간을 줄일 수 있으나 원자성을 제공하지 않는다. 운영에 더 강한 중단 보장이 필요하면 T07 전에 송신자 직렬화·drain 및 외부 대상의 취소/조회 보장을 별도 승인한다. DB 락을 네트워크 호출 동안 유지하는 대안은 차단 자체를 지연시키며 원격 효과를 되돌릴 수 없어 채택하지 않는다.

## 5. 예약 의도와 고아 회수

- TX1은 큐에서 operation과 키를 먼저 영속화한다. TX1b가 별도 의도 행을 등록한다. TX1b 전의 취소는 의도 행에 **같은 키의 VOID tombstone을 먼저 생성할 수 있어야** 한다. 양쪽이 불변 operation payload에 기반해 삽입하고 UNIQUE 충돌 뒤 잠긴 행을 다시 읽는다. VOID 이후 OPEN 전환은 금지한다.
- TX2는 budget_control S 아래 의도 X를 잡고 OPEN 및 fingerprint를 확인한다. 예약·버킷·RESERVE 이벤트·의도의 RESERVED 및 reservation_id를 한 커밋에 반영한다. SQL 실행 중인 상태는 의도 행의 락으로 표현된다. 시험 중 조회에 행이 안 보이거나 락 대기 중이면 결과 불명이다. RESERVE 재생은 새 허가로 해석하지 않는다.
- 회수자는 의도 X를 잡아 TX2와 직렬화된 뒤 operation과 큐 상태를 §4 순서로 확인한다. 미허가가 확정된 OPEN은 VOID로 닫는다. RESERVED라면 dispatch가 존재하지 않고 활성 실행의 재개 권한을 종료했을 때만 CLOSE_REQUESTED를 영속화한다. 이 상태부터 허가는 차단된다. 허가가 먼저 커밋했으면 미송신 추정으로 해제하지 않는다.
- RELEASE 이벤트의 고정 키와 payload는 회수 결정에 결합해 영속화한다. 별도 최상위 정산 TX에서 CLOSE_REQUESTED의 증거 및 reservation 상태를 확인하고 동일 키로 RELEASE를 원장에 기록한다. 이 검증이 의도 락을 필요로 할 때는 budget_control 다음에 취득한다. RELEASE 커밋과 의도 RELEASED를 원자적으로 저장하고 마지막 큐 반영은 별도 반복 가능 단계다. 외부 과금 불명은 RECONCILIATION_REQUIRED로 유지한다.
- 스캔 후보는 OPEN 오래된 건, 미연결 RESERVED, CLOSE_REQUESTED, 의도 등록 전 PREPARED, 미완료 정산이다. TTL은 후보 선택용이다. 최종화는 락+버전 재확인과 커밋된 증거에 따른다. 무단 삭제로 키를 재사용할 수 없게 한다.

## 6. 커밋 전후 복구 행렬

커밋 전 crash는 해당 TX 전체 롤백, 커밋 응답 유실은 해당 행의 현재 커밋 여부를 키·버전·증거로 조회한다. 분산 시스템에서 응답 없음은 롤백의 증거가 아니다.

| 커밋 | 직전 장애 | 직후 장애 또는 응답 유실 |
|---|---|---|
| claim/attempt | 이전 큐 상태 유지, 다시 claim 가능 | 같은 lease를 가진 실행자만 진전. 인계는 sweep 이후, 외부 재송신 권한 없음 |
| TX1 operation | 같은 job/slot을 재계산, 부작용 0 | 기존 operation·키 채택, 새 키 발급 0 |
| TX1b 의도 등록/VOID | 동일 키로 등록/취소 경합 재개 | OPEN이면 reserve 가능, VOID면 예약·허가 0 |
| TX2 예약 | 의도 락 해제 후 OPEN 확인, 전체 롤백을 확인한 뒤 같은 키로 재시도 | RESERVED 및 예약·원장 1세트. 연결을 재개하거나 회수 판정 |
| TX3 operation 연결 | 의도와 예약은 유지, 동일 ID로 연결 재개 | 링크는 유지. 다음 허가 TX가 차단/lease를 다시 검사 |
| TX4 허가 | 커밋 없음과 유효 owner가 확인돼야 다시 허가 검토 | dispatch 존재 시 새 송신 권한 0. 실제 송신 전 crash도 결과 불명 |
| 외부 송신/수신 | DB 커밋이 없는 구간. 타임아웃·전송 불명은 조회/증거 대기 | 결과를 받았으나 접수 전 crash면 외부 처리 불명으로 유지 |
| TX5 증거 접수 | 동일 digest 재접수 또는 증거 없음으로 대기 | 재수신은 같은 증거, 상태 변경 없음 |
| TX5b 증거 채택·정산 payload | 접수 증거 재검토, 이벤트 순번 신규 확정은 1번 | 채택본과 event intent로 계속. 상충 증거 격리 |
| TX6 정산 적용 | event 미확정이면 확정된 같은 payload·키로 재개 | 원장·집계·결과 함께 확정. 현재 예약으로 응답을 재구성하지 않음 |
| TX7 큐 반영 | TX6 결과 조회 후 재시도 | 완료·dependency 승격은 조건부 반복. 비용 효과 발생 없음 |
| 회수 CLOSE_REQUESTED / RELEASE | 이전 단계 재검증, 미확정 송신에서는 접근 불가 | 고정 RELEASE 재개 또는 저장 결과 채택. 이중 해제 0 |
| 관리자 의도 생성 / 취소 | 같은 키나 현재 guard 조회 | 기존 operation 노출. 취소 커밋이면 execute 0, 성공 실행이면 같은 결과 |
| attestation | 미확정 상태 보존 | 같은 키 판정과 감사 재생. 과금 처리는 TX6으로 진행 |
| 검사 게시 / 보정 승인 | 이전 ERROR 유지. 검사/보정 미확정이면 계속 차단 | 같은 검사/보정 ID를 조회. 보정 뒤 전체 재검사 전 지출 재허용 금지 |

예산 달력 재계획 소진 503은 **외부 허가 전·예약 롤백 확인**일 때 같은 operation 및 키로 제한적 재시도한다. DB 커밋 불명 503은 먼저 ID 조회와 직렬화 확인이 필요하다. 외부 결과 불명 503은 RECOVERY_REQUIRED로 전환하고 자동 재호출 0건이다. BUDGET_EXCEEDED, POLICY_VIOLATION, PREREQUISITE_NOT_MET은 사용자 조치로 분류하며 상태를 다시 검사한 명시 재개만 허용한다. DB 재시도 횟수와 A3 job 시도 횟수는 별개다.

## 7. 논리 키, 지연 응답 및 관리자 판정

- 서버 UUID와 effect_slot은 도메인 ID, 단계, 대상 슬롯으로 결정한다. retry/lease token이나 발생 시각을 슬롯에 쓰지 않는다. operation당 reserve key는 `job-budget:v1:<uuid>`, ledger용 새 정산 키는 `job-settle:v1:<uuid>:<seq>`, 관리자 정산은 `admin-settle:v1:<uuid>`를 제안한다. zone/clock 변경이나 worker 재할당과 무관하게 재사용한다. `reserve:`, `reserve-v2:`는 기존 내부만 사용한다. 도메인 키와 생성 키를 사용자 입력과 서버 내부 namespace로 분리한다.
- fingerprint는 버전별 UTF-8 canonical 구조와 고정 필드 순서, 명시적 null, 문자열 ID, 정규화된 DECIMAL을 사용한다. 논리 효과·대상·통화·금액·필요한 가격 버전을 포함한다. 임시 trace, 세션, attempt는 제외한다. 기존 T02 해시를 재계산하지 않는다. 기존 Java fingerprint에 필요한 비즈니스 발생 시각 등의 파라미터는 event intent에 최초 값을 저장하고 그대로 재생한다. 동일 키·동일 본문은 기존 ID와 결과, 다른 본문은 RESOURCE_CONFLICT와 원장 증분 0. 동일 operation에서 이벤트 순번은 operation 락 아래 확정하고 의미당 한 번만 할당한다.
- 늦은 응답은 발급된 dispatch와 attempt에 귀속된다. 인증된 내부 경로에서 요청 지문·dispatch 소유권·검증 가능한 원격 참조 또는 fake 응답을 검사한다. 성공한 산출물과 청구 여부를 따로 증거화한다. 만료 worker는 evidence 행만 추가할 수 있다. 활성 owner 또는 한 reconciler가 CAS로 증거를 채택하고 정산 intent를 만든다. 증거 동일성은 canonical digest로 중복 제거한다. 내용이 모순되거나 소유권 불일치는 격리하며 자동 송신·상태 덮어쓰기가 없다. 확정 후 모순이 발견되면 원장과 결과 receipt는 유지하고 조사 및 별개 정정 절차로 넘긴다.
- 관리자 attestation은 ADMIN+CSRF 및 명시 확인을 요구한다. 근거 reference/hash, 사유, 실제 actor, DB UTC, 멱등 키·fingerprint, 이전 버전을 감사 기록에 남긴다. `CHARGED`는 확인된 비용의 정산을 허용하고 `NOT_CHARGED`는 승인된 근거로 예약 해제를 허용한다. 둘 다 artifact 성공을 뜻하지 않는다. artifact 상태는 VERIFIED/SUSPECTED_OR_UNKNOWN/FAILED 같은 별도 값으로 기록하며 검증된 산출물 없이는 job 성공 승격이 불가하다. 불명 판정은 미확정 유지이며 강제 성공·자동 재호출 권한이 없다.
- T03에서는 격리 환경 fake만 사용한다. T07 전 어댑터별 조회/멱등 동작, 원격 키 보존 기간과 실제 송신 중단 요건을 확인해야 한다. Outbox만으로 외부 요청의 정확히 한 번 실행을 입증할 수 없다.

## 8. P-T02-05 수동 정산 API 전환

ADMIN 세션과 CSRF, 기존 19개 공통 오류 규격을 사용한다. 예약 조회 권한과 작업 소유 FK를 모든 요청에서 확인한다. operation은 브라우저·세션과 독립이며 actor는 서버 인증으로 기록한다.

| 경로 | 제안 계약 |
|---|---|
| POST /api/v1/budget-reservations/{id}/settlement-operations | 생성 멱등 키, 정산 종류, 근거·사유·금액, 기대 예약 version, 직전 terminal operation 확인 ID(있을 때) 입력. 락 하에 version·guard 확인. 최초 201, 동일 재생 200과 같은 operation ID. 다른 payload 409. 다른 미완결 의도가 있으면 409와 권한 확인된 기존 ID 반환. 자동 apply 없음 |
| GET /api/v1/budget-reservations/{id}/settlement-operations | 미완결과 최근 완료 이력, 정규화 요약 및 결과 참조. 탭·기기 교체, 생성 응답 유실 시 우선 조회 |
| GET /api/v1/settlement-operations/{id} | PENDING/COMMITTED/REJECTED/CANCELLED 및 영속 결과. 실행 응답 유실 시 이 상태와 원장 참조를 조회 |
| POST /api/v1/settlement-operations/{id}/execute | operation ID만 사용. 잠금 후 immutable payload로 적용. COMMITTED 결과는 같은 저장 응답 200. 커밋 상태 불명이면 조회 안내 및 동일 operation만 재시도. 새 생성 금지 |
| POST /api/v1/settlement-operations/{id}/cancel | reservation→guard 순서에서 아직 이벤트가 적용되지 않은 PENDING만 취소. 기존 결과가 확정되면 취소 불가 |

정산은 한 최상위 예산 TX에서 bucket→reservation→guard 순으로 잠근다. operation 생성 시의 예약 버전이 달라졌으면 원장 쓰기 없이 이유를 가진 REJECTED를 확정한다. 예약 버전 검사는 기존 COMMITTED 결과 확인 뒤에 한다. 정산은 승인된 수식·FX·link를 사용해 원장, 예약/bucket delta, 결과 snapshot, 감사, guard 해제를 함께 커밋한다. 검증 가능한 확정 결과에는 operation id, 확정 시각, 기존/확정 금액 및 상태, ledger event id/key, currency, 요청 기준·조정 delta를 저장한다. 동시 execute의 패자는 이 snapshot을 재생하며 이후 adjustment로 값이 변하지 않는다. operation과 기존 원장 이벤트가 모순되면 임의 성공처리 대신 CR-02 대상이다. 작업 큐의 자동 정산도 같은 reservation 락 아래 guard를 확인한다. 미완결 관리자 의도가 있으면 자동 적용 경로는 대기·정합성 조사로 전환한다. 관리자가 작업 큐 자동 정산을 대신하려면 동일 자동 intent와 결합한 권한 경로가 필요하다. 기본 제안은 큐 소유 예약의 CONFIRM/RELEASE를 §7 판정 및 동일 자동 intent로만 처리하는 것이다.

미완결 guard만으로는 첫 요청이 완료된 직후 재입력을 구분할 수 없다. 신규 의도에는 조회한 직전 terminal receipt와 현재 예약 version을 명시한다. 서버 검증과 별도로 UI가 기존 결과를 보여 주고 **별개 조정**을 사용자가 확인한 뒤 새 키를 만든다. 최초 실행 당시 화면을 복원할 수 없어도 진행 상태와 최근 결과는 서버에서 찾을 수 있다. 금액이 동일한 정상 후속 조정은 이 확인으로 허용된다. 동일 payload 해시를 전역 중복 제거에 쓰지 않는다.

**권장 전환**은 기존 resolve/adjustments의 직접 쓰기를 중단하고 신규 생성·실행 경로를 승인된 OpenAPI/생성 타입에 반영하는 것이다. 구 UI에 대한 전환 기간에는 기존 경로가 공통 `409 PREREQUISITE_NOT_MET` 및 전환 안내를 반환한다. 기존 원장·예약 GET은 유지한다. 다른 대안은 동일 기존 경로에서 서버 의도를 생성하고 operation ID를 반환하는 어댑터다. 경로 호환은 되지만 즉시 적용을 기대하는 클라이언트와는 응답 의미가 달라지며 ID 조회 지원이 여전히 필요하다. 기존 요청을 한 번의 호출로 자동 등록·실행하는 호환안은 새 키에 의한 별개 실행을 막지 못하므로 제외한다.

## 9. 정합성 검사, 성능, CR-02

- v0는 현재 SQL 규칙을 유지한다. SYSTEM DAY/MONTH 각 1, CHANNEL 기간별 ≤1, 무효 0. 과거 정책 상태를 현재 데이터에서 다시 추론해 v0를 재분류하지 않는다. v1은 필수 SYSTEM/유형 정책 형태, 동결 집합 일치, 소유권, 정책 revision·한도 snapshot, 시점·날짜 경계를 검사한다. 기대→실제 및 실제→기대의 anti-join 둘을 수행한다. 같은 정책에서 다른 bucket이나 잘못된 revision도 오류다. 과거 limit snapshot을 지금의 bucket limit로 대조하지 않는다.
- 원장 비용/예약 delta를 각 불변 링크에 한 번씩 적용한 채 bucket 합계와 대조하고 reservation에는 원장 합계를 한 번만 대조한다. 0원이어도 링크 유실을 식별한다. 후보에 stale lease나 미완결 operation이 있다는 것만으로 ledger ERROR를 만들지 않는다. 실제 불일치만 ERROR로 게시하고 지출 차단을 유지한다.
- 첫 구현은 기존 control X 하의 일관 검사와 단일 결과 게시를 유지한다. 비용·링크 writer가 모두 control S/X에 참여해야 한다. 대형 결과 본문 대신 오류 총수와 제한된 페이지를 반환한다. 실패/타임아웃은 이전 OK로 성공 처리하지 않는다. 별도 control X에서 검사 실패를 기록하고 신규 유료 예약·허가를 차단한다. 검사 트랜잭션 중단과 차단 기록 사이의 공백을 막기 위해 실행 시작 상태를 작은 TX에서 먼저 게시하고 예약/허가는 RUNNING/FAILED를 차단 상태로 취급한다. 재시작 시 RUNNING을 그대로 안전 차단으로 유지하고 동일 검사 세대 결과만 게시한다. 정산은 안전 상태 플래그와 무관하게 허용하되 검사 control X 중 잠금 대기할 수 있다.
- 1천/1만/10만 예약 각각에 T02 및 콘텐츠 예약 혼합, 신규 콘텐츠 예약당 링크 3~6개와 다회 정산 이벤트를 준비한다. 적합 데이터와 손상 fixture를 모두 측정한다. 후보 합격치는 격리 MariaDB 11.8.8, 워밍업 후 각 규모 최소 20회에서 전체 검사 p95 ≤ 1/3/10초, control X 보유 p95 ≤ 1/3/10초, 동시 부하 없는 검사 외 예약/정산 p95 ≤ 250ms, 검사 중 정산 p95 ≤ 12초, timeout·deadlock 미복구 0건이다. 예약은 검사 차단 상태의 신속한 거절과 검사 완료 후 정산 지연을 따로 계측한다. 하드 타임아웃 제안은 15초이며 운영 SLO가 아니다. 행 수, CPU/IO, EXPLAIN, 캐시 조건, 락 대기를 기록한다. 목표 미달 시 T07 전에 스냅샷+검사 세대 또는 변경분 검사와 주기적 전체 검사를 재설계한다. 기간 필터만 적용해 전체 OK를 기록하는 방식은 오래된 오류를 숨긴다.
- CR-02 runbook 제안. 지출 차단과 incident ID를 고정 → 읽기 전용 증거 묶음(예약·기대/실제 링크·정책·revision·이벤트·operation·합계) 추출 → 별도 DB에 재현 → 차이/영향/계산 결과와 복구안·권한·롤백 또는 전진 복구 계획을 승인받음 → 승인된 전문 계정이 예상 버전과 해시를 확인하고 감사 가능한 보정만 적용 → 전체 검사에서 오류 0 및 2인 검토 → 지출 상태 변경은 별도 승인. 기본 운영 앱에는 기존 링크나 원장 수정 권한을 부여하지 않는다. 기존 불변 링크에 오류가 있으면 원본을 보존하고 승인된 후속 설계로 별도 교정 근거를 도입해야 한다. 실제 비용 정정은 합법적인 추가 조정/환불 이벤트로 기록한다. 자동 수정과 원장 UPDATE/DELETE는 금지한다.
- CR-01은 remoteAddr 계약을 유지한다. 운영 배포 전에 신뢰할 프록시 목록, 헤더 정제·신뢰 hop 및 제한 키를 별도 승인하고 검증한다.

## 10. 통합 테스트와 합격 조건

격리된 Testcontainers MariaDB 11.8.8 기준이다. barrier/latch와 프로세스 중단 지점을 사용하여 순서를 통제한다. 실제 외부 호출은 0건이다.

| 검증 | 합격 조건 |
|---|---|
| V1→최신 및 V5→최신 | V1~V5 checksum 동일. 기존 예약·링크·이벤트 PK, 건수, 금액, FX 동일. v0 정상 fixture 오류 0. 기존 원장 UPDATE/DELETE와 앱 DDL 실패 |
| LIFETIME 동시 최초 생성 | 같은 policy/content bucket 정확히 1건. 전역과 해당 채널 정책이 모두 활성일 때 정확히 2개 LIFETIME 링크와 SYSTEM/CHANNEL 기대 집합. 한 bucket 초과면 예약·원장·동결 신규 반영 0 |
| 정책 변경과 reserve | 예약 먼저 커밋 시 집합 변경 409. 정책 변경 먼저면 그 집합으로 최초 동결. 후속 개정·revision·run·환불 및 RELEASE 후 새 allowance 발생 0 |
| 복합 FK와 날짜 | NULL 우회, 타 채널/유형, 중복 bucket 및 잘못된 sentinel 거절. 양 끝·최대 연도 mapper round-trip 통과, 기존 DST 및 시간 표현 유지. LIFETIME 날짜 필터/요약이 DAY/MONTH 집계에 유입 0 |
| 기대/실제 검사 | 누락·초과·타 bucket·타 revision·타 채널 fixture 각각 ERROR. 정상 혼합 데이터 오류 0. 창 버전이 다르면 낡은 결과로 차단 해제 0 |
| 예산/큐 락 | claim/cancel/aggregate, policy/pause/stop/dispatch, reserve/recover 역순 경합 없음. 주입한 deadlock은 전체 롤백 후 동일 키로 재시도, 상위 TX 호출 즉시 거절. 비즈니스 오류 자동 재시도 0 |
| 허가 선점과 lease | 차단 커밋 우선이면 dispatch 0. 허가 커밋 우선이면 해당 발급자만 fake 송신 시도 ≤1, 후속 허가 0. 만료 token으로 큐 상태 갱신 0. 복구 주체만 증거 채택 |
| 커밋 행렬 | §6 모든 DB 커밋 전/후 fault를 각각 주입. 같은 논리 슬롯의 operation·reserve·각 event key 최대 1세트. READY로 돌아온 job이 미확정 effect를 재발급하는 경우 0 |
| 고아 회수 | 회수 우선 VOID 뒤 예약 0. TX2 우선이면 기존 예약 정확히 1건 채택 또는 미허가 증명 후 RELEASE 한번. DISPATCHING 이후 예약 자동 해제 0 |
| 지연·충돌 증거 | 동일 수신 N회에도 증거 1건. 늦은 증거 저장이 상태/ledger 직접 변경 0. 상충 증거 시 자동 재호출 0, 원장 보존 |
| 정산 응답 유실·다중 기기 | 두 생성 요청에서 열린 guard 최대 1. execute 2회와 타 탭 재조회에서 같은 원장 ID/확정 내용. 후속 조정 뒤 재생 결과 동일. 새 키·동일 금액 승인 시 의도적 이벤트 한 건 추가. 기존 쓰기 API side effect 0 |
| 판정 및 오류 분류 | 관리자 판정에 근거·사유·actor·시간·키 누락 거절. 과금 확인만으로 artifact 성공 0건. 안전 503은 같은 키 유지, 불명 503은 외부 재송신 0 |
| 성능/복구 | §9 임계와 실패 시 차단을 확인. 검사 결과와 차이 총수는 기준 데이터와 일치. CR-02 dry-run 동안 원장 수정 0, 실패 후 자동 재허용 0 |

## 11. 신규 ADR 제안 및 A3 접점

| 제안 식별자 | 결정 및 영향 |
|---|---|
| T03-A4-P01 | 동결된 CONTENT_TYPE 집합과 LIFETIME bucket 유일성, UTC sentinel, 기대 링크 스냅샷. ADR-029/055 확장, V6 이후 DDL·조회 영향 |
| T03-A4-P02 | 예약 의도 직렬화 및 비용→큐 공통 순서, 커밋 기반 dispatch와 지출 차단 의미. ADR-055/061 확장. A3의 claim·취소·channel 효과 전이에 동일 순서 반영 |
| T03-A4-P03 | 논리 effect/키, immutable dispatch·지연 증거·attestation, 503 분류. A3 lease/attempt 및 ACTION_REQUIRED/RECOVERY_REQUIRED와 맞춤 |
| T03-A4-P04 | 영속 관리자 정산 guard·확정 결과·기존 API 전환. P-T02-05 승인 조건과 OpenAPI·UI 변경 |
| T03-A4-P05 | 검사 차단 상태, 측정 임계 및 CR-02 승인 기반 복구. T07 게이트와 권한 영향 |

정식 ADR 번호는 통합 시 배정한다. A3와 공통 락 순서, 취소/보관 전이, job/attempt FK 및 dispatcher 중단 정의를 맞춘 뒤 A4 통합 검토를 요청한다. 잔여 승인 사항은 sentinel·지출 허가 정책, 한도 개정 후 보유 예약의 허가 여부, 레거시 쓰기 전환, 운영 측정 기준과 수동 복구 권한이다.

검증 기록. Git 상태와 기준 DDL/Java/Mapper/OpenAPI를 읽기 전용으로 대조했다. 이 문서는 정적 상세 설계이며 통합 테스트·DB DDL 및 성능 검증은 미실행이다. 다음 작업은 A3와 계약 대조 및 사용자 설계 승인이다.


# T03-A5 통합 설계 검증

2026-11-25 KST · Codex · 권고 **HOLD**

## 1. 기준과 협업 규칙

- 제품 기준은 main `47ef69731a599c570846c1482dcabf8ead8fe2e4`. backend, infra, OpenAPI, DECISIONS에 기준 대비 변경 없음. 검토 입력은 작업 트리의 A3/A4 수정안과 기존 초안, A1/A2 리뷰다. 수정안의 제안은 현재 ADR을 대체하지 않는다.
- AGENTS.md, CLAUDE.md, TASKS.md, CODEX_COLLABORATION.md에서 승인된 분담을 확인했다. A3가 Claude의 마지막 설계 배정이다. 이후 Claude는 제품 구현과 테스트를 담당하며, Codex가 독립 리뷰한다. Codex 구현은 별도 Codex 세션이 리뷰한다. ChatGPT가 설계·결과를 통합 검토하고 사용자가 설계·착수·종료·병합을 승인한다. TASKS의 A1~A4 완료는 산출물 상태이며 B~F는 BLOCKED다. T02 배정 이력의 Claude 리뷰는 당시 기록이다.
- 양쪽 프로젝트 Skill은 같은 blob `15caf1fedebf40154b46232db29fddc63cdafcb9`이며 AGENTS·TASKS·협업 가이드를 참조한다. 이전 역할과 직접 충돌하는 규칙은 없다. 일반적인 구현·오류 수정 지침은 배정된 작업 범위에 적용된다. 초안 README/HANDOFF의 Codex 또는 Claude 위임 문구는 통합본에서 역사적 안내로 구분할 필요가 있다.
- 정적 비교만 수행했다. 이전 CI 기록은 T03의 검증 근거로 쓰지 않았다. DB·DDL 적용, 빌드·테스트 및 성능 검증은 미실행이다. 본 보고서만 신규 작성했다.

근거 문서: [A3](../plans/t03-revision/T03_DOMAIN_ENGINE_REVISION.md), [A4](../plans/t03-revision/T03_COST_RECOVERY_REVISION.md), [초안](../plans/t03-draft/README.md), [A1](T03_CLAUDE_DESIGN_REVIEW.md), [A2](T03_CODEX_DESIGN_REVIEW.md), [승인 ADR](../../DECISIONS.md). 기준 구현 위치는 backend/src/main/java/com/aistudio 아래 BudgetMapper, BudgetReservationService, BudgetControlService, BudgetPolicyService, ChannelService, LockRetryingTransactions와 resources/db/migration V1~V5, docs/api/openapi.yaml이다.

## 2. 통합 충돌 및 구현 승인 차단 항목

| ID | 판정 | 양쪽 근거와 권장안 |
|---|---|---|
| A5-01 | HIGH · 차단 | A3 §3.3/INT-12의 두 쌍 UNIQUE는 A4 §3의 3열 FK 대상 키가 아니다. A3의 기존 쌍은 해당 자식 FK를 위해 유지하고 `UNIQUE(id,channel_id,content_type)`를 추가한다. 자식의 signed BIGINT, 유형 문자열 길이·collation·NULL 규칙은 MariaDB 실제 DDL로 고정한다. job ID, attempt ID는 BIGINT, operation·dispatch·settlement UUID는 별도 타입으로 선언한다. effect_slot/operation_slot은 이름 차이지만 길이·조합·유일성·재시작 시 동일성은 계약으로 통일한다. |
| A5-02 | HIGH · 차단 | A3 §5.2는 허가에서 queue_control 생략 및 run S를 제안한다. A4 §4–5는 예약 의도, 비용 재검사와 큐 전체 선행 순서를 요구한다. 공통 순서 §4를 채택하고 in-flight 여부에 영향을 주는 run 변경을 같은 락으로 직렬화한다. A3 채널 변경 표의 control S와 A4 X 표현을 구분한다. 공유 락은 현행 channel S/X 직렬화를 활용한 대안이며 정책·지출 변경 X를 대체할 수 없다. T03 내 채널 상태 변경도 control X로 통일하는 안을 추천하며 경합 증가를 계측한다. |
| A5-03 | HIGH · 차단 | A4 reserve/정산 INSERT의 복합 FK는 content, operation, reservation, cost_log의 부모 락을 암묵적으로 획득할 수 있다. 예를 들어 비용 TX가 bucket 보유 후 content FK를 기다리는 동안 다른 TX가 content 보유 후 bucket을 얻으면 역전된다. A3의 operation 연결에서 reservation FK를 갱신하는 경로도 같은 위험이다. §4에 실제 쓰기·FK 검사까지 포함하고 TX3, TX5b를 사전 순서대로 재구성한다. 재시도만으로 안전을 주장할 수 없다. |
| A5-04 | HIGH · 차단 | A3 J23 NOT_EXECUTED→RETRY_WAIT와 A4 UNIQUE(operation_id) dispatch 1건 및 동일 슬롯의 신규 효과 발급 금지가 동시에 적용되면 재개가 막히거나 제약을 우회한다. 미허가 확인 후 같은 키/슬롯의 안전 재개만 허용한다. 허가 커밋 후 NOT_EXECUTED 판정은 예약 해소와 결과 기록에 사용한다. 같은 operation에 두 번째 dispatch는 T03에서 거절하고 재실행이 필요하면 이전 효과 종결 후 사용자가 새 run을 명시적으로 요청한다. 실서비스에서 동일 논리 효과의 재송신을 지원하려면 T07 공급자 보장과 별도 ADR이 필요하다. |
| A5-05 | HIGH · 차단 | A3 R10은 모든 operation 종료를 요구하지만 A4에는 terminal의 공통 판정과 P12 전달 계약이 없다. J05의 PRE 취소도 release 커밋 전 Run을 종료시키면 비용이 남는다. §5의 상태 매핑 및 불변 receipt를 정의하고 모든 종료·보관·활성 포인터 해제 경로에 같은 조건을 적용한다. 과금 확정은 산출물 성공과 독립이다. |
| A5-06 | HIGH · 차단 | A4 §7/8 관리자 판정과 정산 등록의 엔드포인트·적격 전이·큐 소유 보호가 별개다. 기존 OpenAPI resolve/adjustments는 event_key 직결이며 BudgetReservationService.replayEvent는 현재 reservation을 반환한다. 직접 쓰기 전환, 독립적인 artifact 판정, reservation 최신 version/receipt와 session 세대, 저장 결과 재생을 §6대로 계약화한다. 일반 관리자 guard가 큐 정산을 영구 대기시키지 않도록 예약 소유 유형에 따라 등록을 차단한다. |
| A5-07 | HIGH · 차단 | A4의 양방향 링크 검사는 방향이 맞지만 기대 집합과 실제 링크 모두 같은 정책을 누락하면 두 집합이 일치할 수 있다. 동결 집합과 기대 LIFETIME을 추가 대조하고 신규 예약이 적용한 SYSTEM/CHANNEL의 선택 결과를 예약 결정 시 확정한다. 검사에서 현재 활성 상태로 과거를 재해석하지 않는다. 방어 예로 스코프별 개수, 정책/버전/한도, 결정 시각 소속, 복합 소유권을 교차 검증한다. LIFETIME 한도 개정 후 기존 예약 dispatch 허용 여부, 정책 동결 변경 범위, 시각 원천은 승인 필요다. |
| A5-08 | HIGH · 차단 | 기존 ADR-022는 Quartz JDBC JobStore의 T03 도입을 명시한다. A3 T3-ADR-07은 Spring 스케줄러/전용 실행기로 대체하고 T08/T09로 연기한다. A4는 이 변경을 승인하지 않는다. 큐 poll/heartbeat와 지속 일정을 분리하는 방향은 타당하다. 사용자 승인 전 ADR-022가 우선하며 연기안 채택 시 적용 단계와 코어 스케줄러 역할을 명시한다. |
| A5-09 | MEDIUM · 계약 차단 | A3 §8과 A4 §8은 operation 관련 실제 요청·응답 스키마를 제공하지 않는다. 현행 OpenAPI의 문자열 BIGINT, DECIMAL·환율 문자열, UTC ISO 8601, 공통 19개 오류 코드를 유지한다. UUID 형식은 별도 스키마, operation 응답 필드와 오류 details·replay 상태·예산 오류 노출 위치를 확정한다. A4 신규 필드는 인터페이스를 바꾸므로 타입 재생성 및 UI 교체를 수반한다. |
| A5-10 | MEDIUM · 검증 조건 | A4 §9 RUNNING/FAILED 차단과 세대 관리, control X 전체 스캔은 A3 부팅/스케줄 순서에 연결되지 않았다. 부팅은 검사·operation 중단을 탐지하고 불확실성 유지 상태에서 시작한다. 비용 실행 차단과 큐 토글을 별도로 평가한다. 성능 수용 기준과 권한·트리거를 검증한다. |

## 3. 통합 테이블 및 소유권

| 소유 및 쓰기 담당 제안 | 테이블과 필수 연결 |
|---|---|
| 도메인·큐 · Claude Code | topic→channel, content→동일 channel topic, content_revision→동일 content와 channel_config, content_run→동일 content의 revision, automation_job→동일 content의 run 및 channel, job_dependency→같은 run의 양 job, job_attempt→job 및 worker, job_event→동일 job/run/attempt. current_revision_id와 active_run_id는 합성 FK로 자식 소유권 보장, 초기 NULL 후 동일 TX 포인터 설정. job current_attempt 는 복합 FK로 같은 job에 한정 |
| 큐 제어 · Claude Code | queue_control 싱글턴, queue_worker_instance, queue_command_log. 명령 효과와 키 로그 동일 TX. terminal 불변 및 명령/이벤트 append-only. 큐 명령의 현재 표현 replay는 수동 정산 영속 receipt와 구분 |
| 비용 · Codex | budget_policy/revision, budget_bucket, budget_reservation 및 기존 링크·원장, content_budget_policy, reservation_expected_bucket, 검사 run/issue와 budget_control 확장. 콘텐츠 복합 FK, 버전별 NULL CHECK, generated lifetime 유일 키. v0는 레거시, v1은 소유권과 스냅샷 필수 |
| 큐↔비용 계약 · 양쪽 구현, Codex 통합 | job_operation→job 및 생성 attempt 복합 FK, UNIQUE(job_id,slot)·UNIQUE(job_id,seq)·reserve key UNIQUE. dispatch→동일 operation/job, 실제 발급 attempt 복합 FK. immutable dispatch UNIQUE(operation_id). evidence→dispatch, digest 중복 제거. operation_event_intent→operation·원장 참조, PK(operation_id,event_seq), event key UNIQUE. reservation_id 연결 시 request key·소유권 일치 강제. 비용 결과 저장과 큐 상태 갱신은 분리 |
| 예약·수동 정산 · Codex | budget_reservation_intent→operation 및 reservation과 안정 키, 상호 연결은 FK 생성 의존성 및 락 순서 확인. settlement_operation→reservation, settlement_guard PK(reservation_id)와 복합 FK로 같은 예약의 operation, attestation→operation 및 감사 actor. 원장 참조 시 event/reservation 귀속도 검사하거나 복합 UNIQUE/FK로 강제 |

식별자 문자열/BIGINT/UUID·해시 타입·정렬 규칙은 단일 데이터 사전으로 정한다. V4 channel_config는 (channel_id,id) 대체 키가 필요하다. CHECK는 MariaDB NULL 평가 우회를 막기 위해 명시적으로 IS NULL/IS NOT NULL을 사용한다. 불변 컬럼과 메서드 단위 변경 허용 목록을 정의한다. 운영 앱은 필요한 행을 조회·삽입하고 명시된 가변 상태만 갱신한다. guard 삭제가 필요한 경우 해당 테이블에만 DELETE 또는 명시적 종료형 싱글턴 제안이 필요하다. 원장·증거·감사·revision·확정 영수증 UPDATE/DELETE 차단, 신규 키와 bin 예외, 신규 트리거의 migration 계정과 binlog ON 검증은 배포 전 확인한다.

## 4. 전역 잠금·트랜잭션 권장안

- 기본 순서 **budget_control → reservation_intent → channel → policy → bucket → reservation → queue_control → content → content_run → automation_job → job_operation → attempt/dispatch/receipt/event**. settlement_guard와 settlement_operation은 reservation 이후 독립 정산 분기다. 같은 종류는 ID 정순으로 취득한다. 일반 큐는 필요한 channel을 queue_control 앞에서 잠근다. queue_control 이후 예산 락 진입 금지. 합계 락과 개별 이벤트 append를 분리하며 업그레이드 락과 FK 검사까지 검토한다.
- 위 순서는 경로별 명시 락뿐 아니라 부모 FK에도 적용한다. 비용 예약의 content/operation 부모를 명시적으로 같은 순서에 포함하거나 정적으로 분리된 불변 참조를 사용한다. **추천은 명시 락 방식**이다. queue_control 범위를 무작정 추가하는 대신, 큐에 영향 없는 비용 TX는 cost→필요 부모만 취득하고 그 뒤 큐 제어 락을 얻지 않는다. TX3에서 reservation FK가 필요하면 reservation을 큐 락 전 확보한다. TX5b가 예약·정산 원장에 FK를 쓰게 되면 별도 비용 단계로 이동한다. operation-event parent와 reservation/receipt 간 FK가 필요한 TX6은 reservation 이후 job_operation을 확보하고 cost_log→receipt를 일관되게 적용한다. 반대 방향의 큐 코드에서 원장/예약 락을 취득하지 않는다. FK 부모와 UNIQUE 충돌의 상세 경로를 DDL 승인 전에 매트릭스로 고정할 필요가 있다.
- 일반 큐 claim은 후보 비잠금 검색 → 정순 획득 → DB UTC·상태 재검증이다. run/operation 간 가변 상태를 보는 경로는 run 락과 같은 도메인 순서를 사용한다. 채널 archive와 run 열기는 공통 channel 락으로 직렬화한다. heartbeat의 축약 경로가 보유한 job 락 뒤 상위 락을 얻지 않아야 한다.
- 예약 TX는 의도 행 X 아래 예약·스냅샷·RESERVE·RESERVED를 원자 커밋한다. LockRetryingTransactions는 active TX를 거부한다. 큐 TX·허가 TX 안에서 예약/정산 서비스를 중첩 호출하지 않는다. 보정은 새 최상위 TX에서 키를 재생한다.
- 허가 TX는 비용·큐 상태를 함께 보며 지출, 검사, 예약, 채널/콘텐츠, queue dispatch toggle, run fail-fast·취소, 정확한 current attempt/token 및 유효 lease·deadline을 재검증한다. DISPATCHING 및 유일 dispatch의 **성공이 확정된 커밋**이 허가다. 응답 유실 후 SELECT로 발송 권한을 재발급하지 않는다. 허가된 요청은 차단 이후에 송신될 수 있다. T03은 Fake 격리만 허용하며 강한 실제 송신 중단, drain, 원격 멱등성·조회·동시 발신 상한은 T07 게이트다.
- DB UTC는 lease·deadline·허가 증거·큐 시각, 기존 앱 Clock은 예산 기간·환율과 당시 bucket 결정에 유지하는 안을 추천한다. 하나의 템포럴 비교에는 같은 시각 원천을 사용한다. A4 단일 UTC 결정 시각의 원천 표기를 맞춘다. 격리 DB에서 skew 및 기간 경계 실패 시 롤백을 검증한다.

## 5. 장애 복구와 Run 종료 계약

- PRE = 허가 행 0이고 PREPARED/OPEN/RESERVED/CLOSE_REQUESTED이거나 등록 이전으로 확인 가능한 미발신 단계. TX2 커밋 미확정은 OPEN 조회만으로 종결하지 않는다. 의도 키 락에서 reserve와 회수가 직렬화되고 VOID tombstone이 선행하면 다음 예약은 차단된다. CLOSE_REQUESTED는 RELEASE가 확정될 때까지 미해결이다.
- INFLIGHT = dispatch 존재 및 외부 판정/정산/충돌 처리 미완료. lease 만료나 취소로 INFLIGHT가 PRE 또는 terminal이 되지 않는다. 늦은 worker는 동일 dispatch의 불변 evidence만 접수한다. 현재 owner/reconciler가 CAS로 증거를 채택한다. 상충 증거는 자동 호출과 자동 비용 확정을 막는다. 확정 후 충돌은 재개·원장 덮어쓰기 대신 별도 incident다.
- NONE = operation 없음 또는 불변 terminal receipt를 검증한 상태. 종료하려면 요청 의도·예약 정리가 완료되고 필수 비용 효과가 정확히 한 번 기록되어야 한다. PRE에서 예약 없이 VOID라면 ledger event 0건이 정상이고, 예약이 있었다면 RELEASE/CONFIRM 등 해당 결과를 확인한다.
- 비용 확정과 receipt를 같은 TX에 기록한다. P12는 receipt의 operation ID/버전 또는 불변 결과 키를 사용해 전이를 한 번 반영한다. TX6–TX7 사이 crash는 reconciler가 재생한다. Run은 terminal job, 결제 미해결 0, 필수 큐 반영 완료를 함께 검사한 뒤 종료/active 포인터 해제를 동일 TX에 기록한다. 종료 후 신규 operation 생성은 차단한다. `unresolvedOperations`는 이 정의와 같은 집계를 쓴다. 이미 종료한 Run의 일반 환불·수동 조정은 비용 원장 후속 효과이며 자동으로 Run을 재개시키지 않는다.
- P12 결과 후보는 EXECUTED_RESULT_ADOPTED(검증된 artifact와 비용 확정), NOT_EXECUTED(확정 근거 및 예약 처리), EXECUTED_FAILED(artifact 실패 또는 종료가 명시적으로 선택되고 비용은 확정). 비용 확인만 있고 artifact 미판정이면 RECOVERY_REQUIRED를 유지한다. J05/23 및 TTL 회수와 이 전이표의 연결을 통합본에 명시한다.
- 논리 슬롯당 한 operation, 불변 canonical payload·버전·fingerprint와 영속 seq/event key 생성. attempt마다 새 효과 금지, 같고 동일한 요청은 재생, 같은 키의 다른 payload는 409. reserve:/reserve-v2:는 보호한다. A3 자동 3회(최초 포함)·수동 2회 제안은 기본 초안 5회와 별도 승인 사항이다. 내부 DB 재시도 최대 3회 추가와 분리한다. 확정 롤백·허가 전 예산 기간 재계획 503만 안전한 동일 키 재시도 대상으로 하고 커밋 불명은 조회 후 판정, 외부 불명은 자동 재호출 0건이다.

## 6. 관리자 절차, API 및 UI

attestation은 큐 소유 효과의 근거·사유·actor·DB UTC·멱등 키를 감사하며 청구 판정과 산출물 판정을 분리한다. 큐 해소에 필요한 CONFIRM/RELEASE는 저장된 자동 정산 의도로 승계한다. 일반 settlement_operation은 T02 예약의 resolve/adjustment 및 이미 해소된 예약의 허용 조정을 기록한다. 재시도 가능한 미확정 큐 operation을 일반 정산으로 우회할 수 없다. 범위와 상태 전이를 OpenAPI에 명시한다.

미완결 guard는 예약당 1개다. 새 요청은 현재 예약 version·직전 영수증 확인·명시적인 신규 조정 확인을 요구하고 HTTP Idempotency-Key 하나로 등록한다. 동일 키 재생은 version의 후속 변화와 독립적으로 먼저 조회한다. 최초 201·동일 재생 200 제안, execute는 동일 operation ID와 immutable payload를 사용하며 확정 영수증을 원장과 원자 저장한다. 서로 다른 탭/기기 및 세션 재인증 뒤에는 GET으로 진행 또는 완료를 찾고 재실행은 같은 ID를 쓴다. 서로 다른 의도로 확인한 동일 금액 조정은 허용한다.

현행 resolve/adjustments의 즉시 적용 유지안은 구 UI 호환이 쉽지만 P-T02-05를 우회한다. **권장**은 직접 쓰기를 종료하고 전환 기간 409 PREREQUISITE_NOT_MET 및 새 절차를 안내하는 것이다. 같은 경로에서 operation ID만 반환하는 어댑터도 응답 의미가 바뀌므로 동일 계약 검증이 필요하다. UI 배포를 새 API와 조정한다. 기존 GET/원장·예산 표시 유지, 정산 화면은 미확정 상태와 확인 다이얼로그, 재조회, 세션 세대·오류 처리를 포함한다. LIFETIME sentinel은 API 날짜로 표시하지 않는다. ContentBudgetSummary는 적용 여부와 정책별 한도/사용/예약/잔여 문자열을 노출하고 LIFETIME·SYSTEM·CHANNEL 중복 합산을 금지한다. OperationSummary는 식별자·phase·비용/결과 상태 및 안전한 근거 요약만 제공한다. UUID 검증, nullability, 오류 details 필드는 계약 초안에서 확정할 항목이다.

## 7. INT-01~15 해결 추적

| INT | 판정 | 통합 잔여 |
|---|---|---|
| 01 | 부분 | 슬롯 명칭·타입·UNIQUE, attempt FK와 A3/A4 writer 확정 |
| 02 | 미해결 | 생략 락과 암묵 FK 포함 실경로 통일 A5-02/03 |
| 03 | 부분 | 커밋 경계 합의, 검사 상태·toggle·deadline 등 guard 확정 |
| 04 | 부분 | §5 분류와 CLOSE_REQUESTED 반영 |
| 05 | 부분 | terminal 영수증, P12 완료와 Run 종료 가드 |
| 06 | 부분 | 세 판정 enum과 versioned receipt/attestation API |
| 07 | 부분 | CAS/불변 증거 방향 일치. 허가 후 NOT_EXECUTED 재개 충돌 |
| 08 | 설계 방향 일치 | 내부 errno/reason DTO와 503 회귀 테스트 |
| 09 | T07 | 실제 발신 상한·원격 멱등 계약, T03 Fake 측정만 |
| 10 | T07 | ai_request_log는 operation 참조 상세. 시점 이동 ADR 필요 |
| 11 | 미해결 | 평탄 OperationSummary/ContentBudgetSummary/OpenAPI 확정 |
| 12 | 미해결 | 복합 UNIQUE 및 마이그레이션 선후행 |
| 13 | 부분 | job-derived content/channel/type 및 ReserveCommand 기준 입력 고정 |
| 14 | 설계 방향 일치 | pause/archive 신규 차단, 기존 정산 허용. 활성 run 채널 archive 제한은 승인 |
| 15 | 부분 | 부팅 시 미확정 진단과 검사 실행/실패 처리 및 큐 진입 차단 명세 |

## 8. CDR·A2 기존 발견 추적

아래 판정은 설계 수준이며 실측 합격을 의미하지 않는다.

| 발견 | 판정 및 잔여 |
|---|---|
| CDR-01 | WAITING/RECOVERY_REQUIRED 분리, docs/08 변경 승인 |
| CDR-02 | revision 입력/run 결과·manifest 분리 제안, T06 승인 키 확장 승인 |
| CDR-03 | EXPANDER/CHILDREN/BARRIER 계약, 실제 확장은 후속 승인 |
| CDR-04 | 취소 속성 채택, PRE 해제 전 종료 및 INFLIGHT 표시 A5-05 |
| CDR-05 | attestation 주체·artifact 독립성 보완, A5-06 |
| CDR-06 | A4 LIFETIME 적용, A5-07 검증 필요 |
| CDR-07 | Idempotency-Key 기준 일치. A4 생성 키의 헤더 계약 확정 |
| CDR-08 | run 기반 fan-in, claim 정순. 교차/FK 락 A5-02/03 |
| CDR-09 | active_run_id 대안 채택 제안. 단일 TX 및 DB 보호 검증 |
| CDR-10 | 최소 상태+attention 제안, 비용 종결 조건 A5-05 |
| CDR-11 | V4 대체 UNIQUE 방향 일치, 3열 FK 미해결 |
| CDR-12 | 스케줄 경로 구분, Quartz 연기 승인 대기 |
| CDR-13 | 큐 DB 상한 및 worker 확인 제안, 실제 발신 상한 T07 |
| CDR-14 | heartbeat deadline 제안, 허가·sweep 경쟁 테스트 |
| CDR-15 | 3+2 제안, A5-04 및 횟수 승인 |
| CDR-16 | DB UTC/Clock 비교 분리, A4 예약 결정 시각 표기 통합 |
| CDR-17 | topic T03, scene/claim T04로 제안 |
| CDR-18 | ai_request_log T07, 별도 범위 승인 |
| CDR-19 | title을 현재 revision의 복제본으로 관리하는 안 |
| CDR-20 | run 취소 및 ACTION_REQUIRED 전용 재개, 불명 상태는 판정 절차 |
| CDR-21 | FIFO 인덱스 제안, EXPLAIN·경합 실측 대기 |
| CDR-22 | A3 도메인 권한 제안, A4 비용 ACL/트리거/guard 삭제 미결 |
| CDR-23 | 언어 타입·hash 버전 명세 제안, 공용 fingerprint 회귀 필요 |
| CDR-24 | 서버 pipeline 선택·설정 revision·production UI 방향, API 스키마 미결 |
| CDR-25 | TASKS 반영 확인. 통합 초안 추적·배정·보고 경로는 사용자 지정 |
| A2-01 | 기대/실제 양방향·v0/v1 제안, 기대 집합 누락 검증 A5-07 |
| A2-02 | sentinel·유일성·동결 제안, 복합 FK와 집합 변경 계약 승인 필요 |
| A2-03 | intent와 허가 개선, 교차 락·암묵 FK 미해결 |
| A2-04 | 불변 증거·CAS 제안, 재개와 P12 정합성 미해결 |
| A2-05 | 서버 guard·저장 receipt 구조 제안, 기존 쓰기 전환·권한/적격 계약 미결 |
| A2-06 | 영속 키·순번 제안, 재시도 시 효과 재발급 규칙 A5-04 |
| A2-07 | 규모별 임계·검사 실패 차단 제안, 부팅·부하·CR-02 승인 미결 |
| A2-08 | 원인별 503 방향 일치, 내부 사유와 회귀 테스트 필요 |

## 9. T03 선행 제약 10개

| 번호 | 설계 판정 |
|---|---|
| 1 | 부분. LIFETIME과 링크 검사 연결, FK·정책 기대 집합 미결 |
| 2 | 충족 방향. 예약/정산 최상위 TX 분리. 실제 호출 그래프 검증 필요 |
| 3 | 부분. 영속 연결·고아 회수 제안, 교차 락과 큐 종료 미결 |
| 4 | 충족. 예약 내부 접두사 보호 |
| 5 | 충족 방향. 503 원인별 분류. 승인 및 구현 검증 필요 |
| 6 | 부분. 성능 목표와 차단 세대 제안, 실측 미실행 |
| 7 | 운영 배포 게이트 CR-01. 신뢰 프록시·IP 정책 승인 전 운영 이월 |
| 8 | 부분. CR-02 조사/승인 절차 제안. 복구 권한과 링크 교정 계약 별도 승인 |
| 9 | 조건부. 앱/DB 시각 구분과 다중 프로세스 감지. 분산 로그인/시계는 운영 확장 전 검토 |
| 10 | 부분. 영속 정산 모델은 갖췄으나 계약 전환 및 복구 테스트가 실제 비용 전 필수 |

## 10. ADR·사용자 결정 목록

후보 ID는 정식 번호가 아니다. A3와 A4 안을 아래처럼 통합하되 기존 승인 ADR을 유지한다.

| 후보 | 포함 및 충돌 |
|---|---|
| 도메인 귀속과 수명 | A3-01/03/04/09. revision/run/manifest, topic·후속 범위. ADR-002/015/016 영향과 시점 조정 승인 |
| 상태·루프·재개 | A3-02/08/10 및 A4-P03의 분류. docs/08·초안 5회 충돌, 큐 전이와 허가 후 재발급 금지 |
| 공통 락과 허가 | A3-05/06/11/12와 A4-P02. 두 잠금 문서를 하나로 대체. channel X, budget Clock, 허가 경계·한도 하향, 운영 상한 승인 |
| 스케줄러와 Fake 격리 | A3-07/13. ADR-022 도입 시점 변경 여부, queue dispatch 기본값, 프로세스/timeout·그래프 상한 승인 |
| LIFETIME·정합성 | A4-P01/P05. 정책 동결·sentinel, 집계/구버전 활성화 조건, 검사 1k/10k/100k 목표와 CR-02 권한 |
| 효과/판정·정산 API | A4-P03/P04, A3-14. 키·영속 영수증·P12, 관리자 판정 및 기존 쓰기 경로 전환, 승인 단계와 보존 정책 |

승인 전 사용자 결정이 필요한 핵심은 Quartz 시점, lease 재개와 송신 경계, 기존 보유 예약의 하향 한도 처리, LIFETIME 정책·sentinel, 터미널 비용 판정과 구 API 전환이다. 부팅 기본값, 성능 허용치·타임아웃·보존 기간과 T07 게이트는 명시적으로 추적한다.

## 11. 마이그레이션과 구현 배정 제안

1. **A5 통합**. Codex가 저장소 대조와 보고서를 제공하고 ChatGPT가 통합 설계를 확정안으로 작성한다. 사용자 설계·착수 승인을 받은 뒤 아래 순서를 시작한다.
2. **B 도메인 · Claude Code**. V6 이후 한 담당자가 번호를 관리한다. V4 대상 대체 키 추가 → topic → content(포인터 FK 후행) → revision → run → 역참조 FK 및 트리거 순서. 신규 우회 NULL을 막고 소유권을 검증한다.
3. **C 큐 · Claude Code**. queue_control·worker → job → attempt → job의 current attempt FK → dependency/event/command_log → 승인된 스케줄러 스키마. C와 D의 작업은 고정 인터페이스·분리 worktree에서 병렬 가능하나 비용 FK 적용은 도메인·큐 키 이후다.
4. **D 비용 · Codex**. 콘텐츠 FK/CHECK/유일성 → 기대 집합/의도/operation의 순방향 테이블 → dispatch/evidence/receipt/settlement/attestation → 순환 FK 후행 → 검사와 운영 권한. 기존 V1~V5 파일 불변. V5 데이터 보존 및 신규 DB 테스트, 구 writer 혼용 금지. V6 이후 번호는 통합 담당자가 실제 브랜치에서 예약한다.
5. **E UI · Claude Code 또는 별도 배정 Codex**. 최종 OpenAPI와 생성 타입을 공통 소유자가 동결한 뒤 UI/Mock IT 병렬화. 정산 API 전환은 UI·백엔드 배포와 묶는다.
6. **F 검증 · Codex**. Claude 구현 독립 리뷰, Codex 구현은 별도 Codex 세션 리뷰. 구현 담당자가 결함 수정. 최신 PR HEAD CI와 통합 검증 → ChatGPT 최종 검토 → 사용자 종료·병합 승인. 공유 파일과 migration 조율은 지정된 Codex 통합 담당자에게 배정 제안한다.

합격 기준은 격리 MariaDB 신규/V5 업그레이드, 기존 링크·FX·원장 해시 및 권한 회귀, 두 커넥션과 FK/UNIQUE 락 실측, 각 커밋 전후 crash, 허가–차단/고아–예약/lease–지연 증거 경쟁, 다중 탭·session 및 legacy write 차단이다. 중복 효과 0, stale 상태 쓰기 0, 미해결 비용이 있는 terminal Run 0, 동일 API replay의 확정 원장 ID 불변을 검사한다. 검사 p95는 A4의 1k/10k/100k 1/3/10초를 검증 후보로 쓰고 큐 측 lock hold와 병행 보고한다. 변경 OpenAPI와 생성 타입 diff, UI 계약, CI 전체를 확인한다.

## 12. 승인 권고

**HOLD.** 방향은 통합 가능하다. A5-01~08과 API 계약 차단 항목을 해결하려면 단일 테이블/잠금 명세, 종료·재개 매핑, 정산 경로 전환 및 Quartz 결정이 필요하다. ChatGPT 통합 검토와 사용자 승인 전 B~F는 BLOCKED다.


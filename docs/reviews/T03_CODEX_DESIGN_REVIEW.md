# T03-A2 비용 안전성 및 데이터 정합성 독립 리뷰

## 1. 프로젝트 인수 상태

- 2026-11-25 KST, Codex, T03-A2 설계 검증. 현재 작업 디렉터리는 이 문서 기준 저장소 루트 `../..`다. 원격은 `https://github.com/Hjinsu/ai-studio.git`이다.
- STATE.md에서 T01·T02 DONE을 확인했다. GitHub PR API의 병합 커밋과 원격 main이 로컬 HEAD와 일치한다. T03-A2는 사용자 명시 배정이며 TASKS.md의 제품 구현 BLOCKED와 양립한다.
- GitHub Actions jobs API에서 종료 검증 run 45578405327의 성공을 확인했다. 기준은 `49334bc`이며 현재 병합 커밋과의 차이는 문서 7개다. 개별 로그와 현재 merge SHA의 별도 run 결과는 확인하지 않았다. [PR #1](https://github.com/Hjinsu/ai-studio/pull/1) · [종료 CI](https://github.com/Hjinsu/ai-studio/actions/runs/45578405327)
- 집 PC의 소스 Pull·DB 복원·서버 기동은 사용자 확인을 근거로 삼았다. 개발 DB의 Flyway 적용 상태, 실제 지출 설정값, 운영 DB 및 서버 동작은 이번 정적 검증에서 독립 확인하지 않았다.

## 2. 검토 기준 HEAD

`main` / `47ef69731a599c570846c1482dcabf8ead8fe2e4`. 로컬 origin/main 및 `git ls-remote --symref origin HEAD` 일치. 검토 시작 시 추적 파일 변경 0건, 미추적 `docs/plans/T03_MASTER_DESIGN.md`와 `docs/plans/t03-draft/` 존재. 제품 근거는 HEAD, 제안 근거는 현재 작업 트리의 초안이다. 새 결과물은 본 보고서 한 파일이다. Git 확인 명령은 status, log, remote -v, rev-parse, worktree list, ls-remote, diff --name-only다. 제품 테스트·빌드·migration은 미실행이다.

## 3. 개발 규칙과 Skill

AGENTS, CLAUDE, STATE, TASKS, DECISIONS, README, 인덱스, 협업·로컬 실행 가이드와 UI 설계 시스템을 대조했다. 현재 Codex 스킬 목록에서 ai-studio를 인식하고 적용했다. .agents 및 .claude 양쪽 SKILL.md의 Git blob 해시는 동일하다(`15caf1f…`). README의 T01/V2 기술은 현행 단계와 불일치하며 상태는 STATE의 DONE 기록과 T02 실행 가이드의 V5 기준을 따른다. 회사 PC의 예시 절대경로는 사용하지 않았다.

고정 스택은 Java 21, Spring Boot 4.1.3, Gradle 9.8.2, MyBatis starter 4.1.0, Flyway 12.4.0, MariaDB Compose/CI 11.8.8, React 19.3.1, TS 6.0.3, Node 24 계열(패키지 제약 24.19.0 이상 25 미만)이다. Quartz JDBC JobStore와 DB 큐는 승인 설계이며 현재 backend/build.gradle.kts에 Quartz starter가 없다. 신규 버전·인프라 도입은 승인 대상이다.

## 4. T03 설계 문서

지정한 초안 11개가 모두 있다. README, PLAN, DATABASE_DESIGN, STATE_MACHINE, JOB_ENGINE_DESIGN, COST_INTEGRATION, API_DESIGN, UI_DESIGN, TEST_PLAN, DECISIONS, HANDOFF를 현행 계획 및 T02 종료 리뷰·보고와 대조했다. 초안 HANDOFF의 보고서 위치보다 현재 사용자 배정을 우선했다. ADR-065 및 P-T02-03·05, CR-01·02, UI-F06·F08·F09·F10 보류를 유지한다.

## 5. T03-D01~D12 개별 판정

| 결정 | 판정 | 승인 조건 |
|---|---|---|
| D01 활성 run 1개 | 조건부 | 미확정 operation과 취소 대기까지 같은 DB guard에 포함 |
| D02 채널·유형 불변 | 조건부 | 예약 복합 FK와 아카이브 후 정산 허용 |
| D03 도메인 분리 | 조건부 | automation_job 명칭 및 job/run/attempt/operation 소유 FK 확정 |
| D04 고정 DAG | 조건부 | 그래프 재생성 금지, 동일 run FK와 비용 미확정 중 승격 금지 |
| D05 lease 60초·heartbeat 15초 | 보류 | 펜싱과 늦은 증거 수집, 시각·경합 계약 필요 |
| D06 worker 2, 단일 인스턴스 | 조건부 | 프로세스 중첩 때의 슬롯 상한 범위 명시 |
| D07 최대 5회 | 보류 | 기존 3회 정책과 최초 실행 포함 여부, DB 재시도와 분리 승인 |
| D08 조치/복구 상태 분리 | 조건부 | 불명확 비용은 일반 retry·종료 경로에서 차단 |
| D09 LIFETIME | 보류 | 정책 적용 집합의 영속 증거, 한도·유일성 계약 확정 |
| D10 영속 operation·분리 TX | 보류 | 아래 복구 행렬·dispatch 승인점과 증거 채택 계약 필요 |
| D11 관리자 영속 정산 | 보류 | 레거시 쓰기 차단·다중 탭 경쟁·결과 영속성 필요 |
| D12 기존 UI 확장 | 조건부 | API 확정, 불확실 상태와 승인된 UI 보류 항목 보존 |

## 6. T02 필수 제약 10개

| 번호 | 판정 | 근거 및 미결 |
|---|---|---|
| 1 | 부분 | LIFETIME·기존 예약 분기 제안. 정확한 링크 집합과 DDL 변경 미정 |
| 2 | 충족 | COST_INTEGRATION TX2/6 최상위 호출. LockRetryingTransactions 31–37과 일치 |
| 3 | 부분 | TX1~7과 reconciler 제안. 경쟁·재시작 시 확정 판정 및 쓰기 소유권 미정 |
| 4 | 충족 | reserve:/reserve-v2: 예약 전용. 신규 접두사 분리 |
| 5 | 부분 | 정책/예산 오류 분리. 동일 503의 발생 위치별 처리 미정 |
| 6 | 부분 | 1k/10k/100k 측정 제안. 차단 시간과 운영 수용 기준 미정 |
| 7 | 이월 | 신뢰 프록시 범위는 운영 배포 전 승인. 지금 remoteAddr 유지 |
| 8 | 부분 | 승인 기반 진단·복구 방향. 승인 기록·검증·적용 방법 미정 |
| 9 | 조건 충족 | 단일 인스턴스 전제. 분산 로그인/시각은 확장 승인 전 검토 |
| 10 | 부분 | settlement_operation 제안. 레거시 우회 및 동시 생성 의미 미정 |

## 7. 설계 충돌 및 누락

### A2-01 · HIGH · 승인 차단

**현행 제약 확인, T03 실패는 정적 분석.** COST_INTEGRATION LIFETIME, DATABASE_DESIGN 기존 비용 테이블. V5의 ck_budget_bucket_no_content·ck_budget_reservation_no_content와 BudgetMapper 238–248, 509–539는 콘텐츠 링크를 지원하지 않는다. CHECK만 풀거나 SQL에 scope 한 종류만 추가하면 정상 예약이 ERROR를 일으키거나 잘못된 유형/채널 링크가 통과한다. 기존 검사도 CHANNEL 개수 상한만 확인하므로 누락한 선택 정책을 새 데이터에서 그대로 판별할 수 없다.

예약 TX에서 활성 정책·revision·적용 한도·bucket의 기대 집합을 확정하고 실제 링크와 양방향 비교한다. 예전 NULL 콘텐츠 예약에는 기존 규칙을 적용하고 신규 콘텐츠 예약에는 대조 근거를 필수로 둔다. 현재 정책이나 created_at만으로 과거 적용 집합을 복원하는 대안은 저장량이 적으나 동시 변경·같은 시각·비활성 이력을 구분하지 못한다. V5 업그레이드 fixture와 누락·추가·다른 유형·다른 콘텐츠·다른 채널·잘못된 revision 및 기간을 각각 주입해 ERROR와 신규 차단을 검증한다.

### A2-02 · HIGH · 승인 차단

**설계 미결.** COST_INTEGRATION LIFETIME, DATABASE_DESIGN 소유권 및 비용 테이블, ADR-029. V5의 UNIQUE(policy_id,subject_key,period_start)는 시작 시각이 다르면 동일 정책·콘텐츠의 bucket 둘을 허용한다. 앱 검증 경로만 사용하면 채널 소유권이 다른 reservation을 저장할 위험도 있다. (content_id,channel_id) 복합 FK와 NULL 조합 CHECK를 쓰고 정책·콘텐츠에 안정적인 단일 식별 제약을 둔다. 기존 SYSTEM/CHANNEL subject 0과 분리하며 정확한 sentinel 및 종료 표현을 계약화한다. 두 정책을 서로 다른 한도로 적용하면 두 bucket 모두 검사하되 원장 비용 한 건을 합산액 두 건으로 보이지 않게 한다.

전역·채널 유형 정책을 겹쳐 적용할지, 비활성 채널별 override가 전역 fallback을 허용할지, 새 정책이 기존 콘텐츠 지출을 승계할지를 결정해야 한다. 하나의 유효 정책만 택하면 구현은 간결하지만 채널 정책 교체가 잔여액을 초기화할 수 있다. 복수 정책이 필요하다면 신규 정책에 누적 비용을 반영하는 승인 규칙 없이는 적용을 지연하거나 대상 콘텐츠의 정책 변경을 금지한다. revision/run 변경, 동시 최초 생성, 정책 교체, 기존 예약 지연 정산과 소유권 오류를 검증한다.

### A2-03 · HIGH · 승인 차단

**설계 위험.** COST_INTEGRATION TX1~4 및 JOB_ENGINE Claim/fencing. BudgetReservationService 87–125, 138–172와 LockRetryingTransactions 31–37을 보면 예약 자체는 독립 TX다. 예약 lookup이 비어 있다고 orphan을 먼저 닫으면 이후 이미 시작한 TX2가 커밋할 수 있다. 취소·pause·지출 차단을 재확인하고 TX4를 커밋한 다음 네트워크 송신까지도 틈이 있다. lease token은 DB 변경을 막지만 외부 전송을 취소할 수 없다.

재확인 및 dispatch intent를 일관된 잠금 아래 확정하고, 그 커밋을 실행 허가의 선형화 시점으로 명시한다. 차단 완료 이후 실제 송신 0건까지 요구한다면 별도 승인된 dispatch 직렬화 및 대기 계약이 필요하다. 미확정 호출은 fencing만으로 대체 worker가 재송신할 수 없다. reserve 불확실 상태는 영속 key로 반복 탐색하고 이미 진행 중인 예약이 종료되기 전에 미실행 확정 또는 operation 삭제를 하지 않는다. TX 안에서 외부 호출을 보유하는 대안은 장기 잠금과 응답 불명확성을 남긴다. 두 커넥션 barrier로 TX2 커밋과 orphan sweep, TX4와 pause/차단, 만료 직후 이전 worker 재개를 검증한다.

### A2-04 · HIGH · 승인 차단

**설계 위험.** JOB_ENGINE Claim/fencing, STATE_MACHINE Job·Run, COST_INTEGRATION TX5–7. 예시 claim은 job을 먼저 잠그고 집계·취소는 run 잠금을 사용한다. 두 경로의 잠금을 한 TX에서 반대로 취득하면 교착한다. 이전 worker 응답을 모두 버리면 과금 근거를 잃고, 이전 worker에게 결과 확정 권한을 허용하면 새 owner 상태를 덮어쓸 수 있다.

후보 검색 후 공통 순서로 잠그고 eligibility/token/DB 시각을 다시 검사한다. 비용 순서 control→channel→policy→bucket→reservation→event를 유지하며 큐 락을 가진 호출자가 예산 최상위 TX를 열지 않도록 한다. 큐 내부는 채널이 필요한 경우 먼저 잠그고 run→job→operation 순서로 통일하는 안을 검토한다. 만료 응답은 dispatch/attempt에 귀속된 제한된 불변 증거로만 접수하고 활성 owner 또는 reconciler가 CAS로 채택한다. 상충 응답은 격리한다. 모든 늦은 응답 폐기 방식은 단순하나 정산 자료를 잃는다. claim/취소/heartbeat/sweep/종료 barrier와 충돌 증거를 검증한다.

### A2-05 · HIGH · 승인 차단

**현행 경계 확인, 신규 해결책 미결.** API_DESIGN 25–30, COST_INTEGRATION P-T02-05, OpenAPI 645–710. 기존 resolve/adjustments는 각 호출의 Idempotency-Key를 이벤트 키로 사용한다. create 응답 유실 후 키가 다른 두 탭이 같은 의도를 다시 등록할 수 있고, 동시 execute 또는 레거시 endpoint가 새로운 이벤트를 만들 수 있다. 기존 BudgetReservationService 350–425의 같은 키 재생은 현재 reservation 조회를 반환하므로 후속 조정 뒤 최초 확정 화면 결과를 그대로 재생한다는 보장이 없다.

서버에 미완결 정산 의도 guard를 두고 등록→실행→조회에서 불변 payload·fingerprint·event key·확정 결과 참조를 유지한다. 생성 키 유실 후 예약 기준 pending/unknown 조회와 명시적 결과 확인을 지원한다. 원장 커밋 응답 유실은 저장 이벤트의 키·fingerprint·reservation 소유권으로 확인하고 operation 결과를 복구한다. 레거시 경로는 같은 guard를 공유하거나 승인된 계약으로 제거한다. 내용 동일성만으로 중복 제거하면 합법적인 반복 조정을 잃고, 클라이언트 저장만 사용하면 다른 기기를 보호하지 못한다. 세션 만료·다중 탭·create/execute 응답 유실·이전 경로·동일 내용 별개 승인 및 동시 실행을 검증한다.

### A2-06 · HIGH · 승인 차단

**설계 위험.** COST_INTEGRATION 키·TX4–7, JOB_ENGINE Retry. V5 키는 case-sensitive 8~128자이고 BudgetReservationService 103–132, 366–390은 fingerprint 불일치를 거부한다. UUID 형식은 키 제약과 양립하지만 새 job/retry마다 새 operation을 만들면 같은 외부 효과를 재요청할 수 있다. settlement sequence가 재시작 시 재할당되면 같은 정산이 새 원장 이벤트가 된다.

job 내 논리 효과 슬롯과 immutable request schema version을 DB 유일 제약으로 고정한다. 발생 시각·attempt·trace 같은 변동 메타데이터를 재생 payload에 섞지 않고 이벤트 순번과 정규화 payload를 송신 전에 보존한다. 같은 키·다른 fingerprint는 409 및 복구 보류다. 새 키 발급은 미호출이 증명됐거나 사용자 승인 아래 신규 논리 효과를 시작할 때만 허용한다. provider 외부 멱등 키나 조회 지원은 어댑터별 검증 대상이며 없으면 불확실 송신 자동 재시도 금지다. outbox는 송신 의도를 복구하지만 외부의 중복 처리 방지 계약 없이는 재전송 안전성을 보장하지 못한다. 소켓 오류·응답 유실·프로세스 재시작·서로 다른 본문 경합을 fake로 검증한다.

### A2-07 · MEDIUM · 승인 조건

**현행 잠금 및 잠재 성능 문제.** COST_INTEGRATION CR-02, TEST_PLAN C08, BudgetControlService.runConsistencyCheck 및 BudgetMapper.consistencyIssues/reservationBucketIssues. control X 아래 전체 예약/링크/원장을 읽어 예약·정산 대기를 늘린다. 규모별 측정 전 지연을 실제 장애로 단정할 수 없다. 일관된 DB 스냅샷과 변경 세대를 사용해 장시간 scan을 분리하고, 결과 게시 TX가 검사한 세대와 현재 세대를 확인하는 방안을 검토한다. 단순 기간 필터는 이전 오류를 놓쳐 잘못 OK를 기록할 수 있다. 초기 전체 검사 유지 시 규모별 시간·잠금 대기 p95·타임아웃·검사 실패 상태 유지 기준을 승인한다. CR-02는 읽기 전용 진단→격리 재현→승인된 보정→재검사로 기록하며 원장 수정과 자동 복구를 금지한다.

### A2-08 · MEDIUM · 승인 조건

**설계 충돌.** JOB_ENGINE Retry와 초기 docs/08의 재시도·상태명, COST_INTEGRATION TX2, BudgetReservationService 112–116. 예산 기간 재계획 소진 503은 동일 업무 키를 쓰는 제한적 안전 재시도 후보지만 외부 타임아웃·커밋 불명 503까지 재실행하면 재청구 위험이 있다. 오류의 HTTP 상태 외에 단계·커밋 증거·외부 결과 확실성을 분류한다. 예산 초과·정책·전제 오류는 ACTION_REQUIRED로 보내고 사용자 재개 시 전제를 재검사한다. DB 최대 3회 재시도와 job 시도 한도는 따로 세며 5회 정책은 ADR로 승인한다. 기존 3회 유지 대안은 부하가 낮으나 장시간 장애 복구 여유가 줄어든다. 같은 503으로 safe retry와 recovery가 각각 선택되는 테스트가 필요하다.

## 8. 비용 안전성

SYSTEM DAY/MONTH 필수, 활성 CHANNEL 적용 정책, 지출 차단과 정합성 ERROR는 신규 유료 예약을 차단한다. 기존 정산은 허용하고 예약 FX를 고정한다. 한도 초과 실비와 지연 과금을 기록하며 RELEASED→SETTLED는 근거·사유·키·감사가 필요하다. PROVISIONAL 하락 시 남은 예약 복원, KRW 전용 조정에서 원통화 유지, bucket 링크 불변과 원장 INSERT-only를 유지한다. 개발 DB와 운영 지출 활성화는 이번 검토에서 확인 대상이 아니다.

## 9. 동시성과 장애 복구

| 마지막 내구 상태 | 복구 판정 |
|---|---|
| TX1 PREPARED | 영속 operation과 reserve key 조회. 진행 중 예약 여부 판정 후 동일 요청으로 복구 |
| TX2 예약 커밋, TX3 링크 전 | key 및 fingerprint로 기존 예약 채택. 취소 시 미송신을 경쟁 없이 증명한 후 해제 |
| TX3 RESERVED, TX4 전 | 새 owner가 lease/취소/차단 재확인 후 허가. 이전 owner의 변경은 0건 |
| TX4 DISPATCHING | 송신 전 crash도 송신 후 응답 유실과 동일하게 미확정. 조회·증거로 복구 |
| TX5 결과 내구화 | 불변 결과를 채택한 worker/reconciler만 같은 event key로 정산 |
| TX6 정산 커밋, TX7 전 | 원장 이벤트를 다시 읽어 COMMITTED 결과 재구성. 새 이벤트 키 금지 |
| TX7 완료 | run 집계·DAG 승격을 멱등 처리. 과거 revision 결과의 현재 상태 덮어쓰기 차단 |

## 10. P-T02-05 해결 여부

영속 settlement_operation 방향은 적합하다. 완료 판정에는 동시 의도 guard, 생성 응답 유실 후 검색, 불변 결과 재생, 레거시 경로 차단, 관리자 교체·세션 재인증 후 복구가 모두 필요하다. 실제 비용 발생 및 유료 API 활성화 게이트는 이 검증의 통과를 요구한다.

## 11. 권장 설계 수정 및 검증

A2-01~06의 제약을 ERD·전이표·계약에 함께 반영한다. 기존 C01~08, Q01~06은 방향이 적절하다. 두 세션 결정적 barrier, 각 커밋 직전/직후 fault injection, 서버 재시작 뒤 멱등 재생, DB UTC 경계, exact event/link count 및 금액 delta 검증을 구체적인 합격 조건으로 추가한다. CR-01은 프록시 신뢰 경계와 IP 판정의 운영 승인을 별도로 유지한다.

## 12. 권장 구현 순서

설계 통합 승인 → 격리 DB의 V5 업그레이드·기존 원장 호환 → 콘텐츠 소유권과 LIFETIME·링크 검사 동시 구현 → 큐 lease 및 fake 장애 복구 → 영속 수동 정산과 기존 API 전환 → 계약·UI·전체 회귀 및 독립 리뷰 순서가 적합하다. 비용 유발 동작은 안전 계약과 테스트가 완료되기 전 실행 경로에 연결하지 않는다.

## 13. 추가 승인 사항

콘텐츠 정책 적용 우선순위·승계, LIFETIME 유일성 및 기간 표현, dispatch 허가/지출 차단 경계, 늦은 증거 채택 권한, operation 키·canonicalization, 수동 정산 API 전환, job 시도 횟수·상태명, 정합성 검사 운영 한계와 CR-02 수동 복구 절차를 확정해야 한다. T03 신규 ADR 번호는 통합 시 실제 DECISIONS 최신 번호와 대조한다.

## 14. 최종 설계 승인 권고

**HOLD.** 프로젝트 인수와 T03-A2 정적 리뷰를 완료했다. 내용이 다른 정책·콘텐츠의 예약을 식별하는 DB 계약, dispatch·lease·orphan 경쟁의 복구, 수동 정산 경로의 영속 멱등성은 승인 전 해결이 필요하다. 통합 설계 검토와 사용자 최종 승인 이후에만 제품 단계로 이동한다.


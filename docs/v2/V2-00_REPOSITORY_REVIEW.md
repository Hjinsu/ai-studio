# V2-00 저장소 검토 및 착수 제안

2026-11-26 KST · Codex · 검토 및 기준선 승인 완료. 아래는 전환 전 조사 기록이다. 최종 승인 범위와 현재 상태는 [결정 기록](../../DECISIONS.md)과 [인수인계](../NEXT_SESSION.md)를 따른다. [v2 착수 패키지](README.md) 다섯 문서를 저장소와 대조했다. 제품 기준은 채널 설정 후 소재 입력 없이 실제 IFNOVA 영어 Shorts 40~60초, 1080×1920 MP4와 출처·비용·게시 정보·manifest가 완성되는 것이다. FAKE는 회귀 검증에 사용한다. 비공개 업로드는 MVP2에서 사람 승인과 별도 실행 허가 후 확인한다.

## 기준 브랜치와 보존 상태

| 원격 | `git ls-remote --heads origin` 결과 | 상태 |
|---|---|---|
| `main` | `47ef69731a599c570846c1482dcabf8ead8fe2e4` | T02 완료, 제안 기준의 조상 |
| `t03/integration` | `dab7013c4557ba87d4440dfc86417344bb2c4e1d` | T03-B PR #3 병합, **v2 분기 권고 SHA** |
| `t03/c-queue` | `1e888767a61407a7e9176df0e362df9d6143f1b2` | 제품 WIP `e7ef738`과 문서 전용 중단 인수인계. C worktree clean |
| `t03/d-budget` | `3a976e1fbb1a5c8fbda50257b12643ff6cfbca3a` | D 제품 WIP. 공통 계약 `6cd0c4e2c7618f90249f670f000938113c37d456` 포함, C에는 미반영 |

로컬 integration worktree는 `d495296`으로 원격보다 5커밋 뒤에 있고 미추적 T03-B 리뷰 2개가 있다. D에는 이전 MVP의 추적 문서 편집 7개, 미추적 기획·검토 문서, `.vscode/`, 새 `docs/v2/`가 있다. 기존 문서는 해당 작업 트리에 보존한다. 조사 시점에는 로컬과 원격에 제안한 v2 브랜치가 없었다. C에 기존 계약을 적용하거나 C/D를 합류할 필요 없이 v2 준비가 가능하다.

승인 후 `dab7013`에서 새 worktree의 `v2/integration`을 만들고 승인된 v2 문서와 규칙 전환을 먼저 기록한다. V2-01은 그 **문서 반영 커밋**을 공통 기준으로 삼는다. 병렬이면 `v2/product`와 `v2/safety`를 각각 별도 worktree로 사용한다. 브랜치 생성, 문서 커밋·푸시, 구현 착수와 병합은 사용자 승인 범위별로 진행한다.

## 코드·테이블 선택

| 분류 | 실제 파일·모듈 | 의존성 및 선택 |
|---|---|---|
| 그대로 재사용 | T01/T02 Gradle Wrapper·CI·DB guard, `security/`, `audit/`, `channel/`, `shared/api`, 프론트 `ui/`·`layout/` 및 로그인/채널/예산 화면 | 인증·CSRF·세션·최소 권한 유지. 단일 JVM/loopback. 상세 운영 화면은 첫 동선의 완료 조건에서 제외한다. |
| 그대로 재사용 | V5 `budget_control`, `budget_policy(_revision)`, `budget_bucket`, `budget_reservation(_bucket)`, `exchange_rate`, `cost_log`; `BudgetReservationService`, `BudgetControlService`, `BudgetMapper`, `Money`, `PeriodCalculator` | 기존 예약·원장 이벤트·환율·기간·지출 스위치의 동작과 API를 보존. V5 경로만으로 제작 요청 전체 한도, 영속 dispatch·불명 결과 복구가 확보되지는 않는다. |
| 그대로 재사용 | V6 `topic/content/content_revision/content_run/queue_command_log`; `TopicService`, `TopicNormalizer`, `ContentService`, `RevisionHash`, `IdempotentCommands`; 제품 OpenAPI·생성 타입 | 후보 확정 후 동일 채널 Topic 등록 및 연결. 기존 정규화 hash UNIQUE는 동시 등록의 최종 방어이며, 제목이 달라진 소재는 과거 콘텐츠와의 유사성 비교가 추가로 필요하다. |
| 선별 검토 | C `JobTransitions`, `RetryBackoff`, 상태 타입과 테스트; V7의 Job/Attempt/이벤트·소유권·lease 제약 | 순수 판정과 리스 만료·불명 결과 규칙부터 이식 검토. V7 전체 도입 전에 선행 제작 요청 소유권과 고정 단계 모델이 맞는지 평가한다. C에는 dispatcher/실행 복구/완결 adapter가 없다. |
| 선별 검토 | D `ReservationPolicies`, `ContentReservationDecision`, `ReservationBucketEvidence`, `SettlementIntentDecision` 및 테스트; `operation/contract/*` | 정책 선택·누락 검출·정산 재생 로직과 포트의 실패 의미/거래 테스트를 활용한다. 공통 포트는 C JobOwner/Attempt 전제라 초기 발굴 경로에는 그대로 맞지 않는다. D 판정 코드와 V8 초안은 완료된 예약·dispatch·receipt 어댑터가 아니다. |
| v2 첫 단계에서 제외 | C의 기존 FAKE `PipelineRegistry/Validator` 전체 채택, 범용 DAG/동적 확장, D의 기존 V8 초안 일괄 도입, 여러 worker 운영 최적화, 100k 부하 검증, 기업용 RBAC·다중 서버·외부 접속·분산 인프라 | 한 worker로 시작하고 DB 유효 lease 상한은 2로 유지. 고정 단계·제한된 장면, 실제 경합·장애 테스트 유지. 기존 파일은 보존한다. |
| 후속 제품 범위 | 예약/Quartz, Longform, 여러 채널 동시 운영, 실시간 트렌드·경쟁 채널 상세 분석, 성과 기반 소재 최적화, AI 영상·템플릿 편집기 | 기존 채널 데이터 구조는 유지. 제작·검수·최소 정산·게시 안전 경로를 우선한다. |

## DB 및 안전 계약

- V1~V5는 main과 기준 SHA에서 동일하고 V1~V6은 통합/C/D의 Git blob이 동일하다. 원본 그대로 재사용한다. V6 LF SHA-256은 `304900fda20821c83a9f3f3a56c9568f4215544158020569bd6f36e9d2412588`로 B 보고와 일치한다.
- V7은 C Flyway 리소스에만 있고 LF SHA-256은 `5cff506d1bddda8f98489a6ab5e5419a3a8e276f8a0d675e61457b0ba12f5d81`이다. C 보고상 격리 DB에서만 적용했다. D V8은 `docs/plans/t03-final/V8__create_content_budget_contract.draft.sql`에만 있는 초안이며 적용 보고가 없다. **v2 기본 기준은 V1~V6**이다. V7을 채택한다면 같은 파일을 유지한 뒤 신규 Migration으로 확장한다. 채택하지 않으면 C의 V7 이력과 혼동되지 않도록 신규 번호를 승인 단계에서 명시하고 V7 이력 DB 호환/이관을 별도로 검증한다. 기존 DB의 버전은 미확인이다.
- 새 격리 MariaDB에서 빈 DB 설치, V5/V6 업그레이드, 운영 binlog ON/OFF 및 권한·체크섬, 두 커넥션 경합을 검증한다. 사용자 DB의 읽기·쓰기나 파일 백업·이관은 별도 승인 대상이다.
- **비용 선행 조건**. V6의 `content.topic_id NOT NULL`, revision 소유권과 Run FK 때문에 기존 Run만으로 DISCOVER를 시작하기 어렵다. V2-01 상세 계약은 채널 설정 스냅샷과 상한을 지닌 영속 제작 요청 ID를 두는 안을 우선 검토한다. DISCOVER/SELECT의 효과 키·예약·영수증을 이 요청에 연결하고, 선정 후 같은 요청을 Topic/Content/Run에 원자적·멱등으로 연결한다. 사전 지출도 그 영상의 한도에 합산하며 이후 예약에서 누락하거나 이중 계산하지 않는다. 미선정·취소·불명 상태에서도 원장과 증거를 보존한다. 임시 Topic이나 비용 없는 스텁으로 유료 경계를 우회하지 않는다.
- **동등성 조건**. FD/C-G/CD-G의 안전 결과를 v2 게이트에 매핑한다. Q0에 해당하는 생성·실행 포인터의 원자성, fencing·고아 탐지·재기동, R10에 해당하는 종료 전 비용/receipt 해소, TX4의 동일 소유자 단일 dispatch 커밋과 TX7/P12의 receipt/상태 원자 적용을 두 커넥션 및 장애 주입으로 검증한다. 최상위 READ COMMITTED·FK/UNIQUE 포함 잠금 순서와 비용 TX 역진입 금지, DB TX 밖 네트워크, 불명 결과 재호출 금지, 영속 정산·정책 이력·CR-02 fail closed를 유지한다. 정책 세대나 증거 구조 축소는 과거 예약 재현·정책/양방향 bucket 누락 검출·동시 한도 변경과 정산 검증으로 동등성을 입증한 뒤 승인받는다.
- **품질과 게시**. V2-03은 실제 확인한 검색 결과와 주장-출처 연결을 보존한다. 검색 실패·근거 부족은 제한된 다른 후보 시도나 검수 대기로 처리한다. MP4 해시·입력 버전·권리·출처와 게시 metadata를 manifest로 묶는다. MVP2는 승인된 revision/run/manifest와 실제 파일 해시가 일치할 때만 비공개 업로드하고, 원격 ID 또는 불명 상태를 영속 보존한다. 불명 시 재업로드를 차단하며 공개는 별도 승인이다.

## 문서 효력과 첫 배정 제안

| 적용 대상 | 승인 후 v2 브랜치에서 반영할 내용 |
|---|---|
| `AGENTS.md` | v2 브랜치의 제품·단계 기준은 `docs/v2/`. V2 ID와 단일 JVM·고정 단계 우선 명시. Quartz는 예약 단계로 이동. 승인/보존·보안·비용·검증 규칙 유지. |
| `CLAUDE.md`, 협업 가이드 | Claude V2 제품·실행 자체 테스트, Codex 비용·공유 계약·통합/CI 및 Claude 독립 리뷰. Codex 비용 구현은 다른 Codex 세션에서 읽기 전용 리뷰. 동시 구현은 별도 worktree. |
| `TASKS.md`, `STATE.md` | V2-00 검토 결과와 V2-01 배정/선행 조건 등록. T03-C/D는 보존된 WIP로 중단, 기존 T 배정 기록은 이력. 다음 세션 첫 대상은 V2-01. |
| `DECISIONS.md` | v2 기획 우선권, 신규 분기, 유지할 안전 계약 및 대체 설계의 동등성/승인 조건을 전환 결정으로 기록. |
| 루트 `README.md`, `docs/00_INDEX.md`, 기존 기획 | v2 문서 목차와 착수 기준 링크. 기존 T03 및 개인용 MVP 초안은 역사적 기준으로 남긴다. |

| V2-01 첫 역할 | 수정 범위 제안 | 인수 조건 |
|---|---|---|
| Claude · 실행 | 제작 요청을 소비하는 고정 단계 실행, claim/lease·fencing, 상태·이벤트, 무효과 작업·취소·복구 및 자체 테스트. 공용 스키마/API는 Codex에 제안 | 중복 시작 방지, 한 worker 정상 동선과 두 worker 경합, 재시작·취소·고아 상태 차단 |
| Codex · 비용 및 공용 계약 | 제작 요청과 작업 소유권, 효과 키/예약/단일 dispatch/영수증/최소 영속 정산, Migration·OpenAPI·포트와 격리 통합 검증 | 제작 요청 한도와 시스템/채널 한도, 원장·증거 누락 차단, 지출 OFF, 호출 전/후 종료·응답 유실·영수증 재전달·정산 재생. 실제 호출은 승인 게이트 통과 이후 |

공유 소유권·트랜잭션 테스트 계약을 먼저 확정한다. 다음 경로는 V2-01 FAKE 및 비용 대역 → V2-02 자동 후보 → V2-03 실제 검색 근거·대본·장면 → V2-04 로컬 MP4 회귀 → V2-05 승인된 공급자/권리 확인 자산으로 실제 MP4·metadata·QC → V2-06 승인된 비공개 게시다.

B 통합 보고서상 단위 151, 격리 통합 153, PR #3 계약 통합 CI 7/7이다. 해당 CI 검증 커밋 `8cfe8a0`과 `dab7013` 사이의 제품·API·인프라·CI 경로는 Git diff가 비어 있다. C/D 실측은 [회사 PC 인수인계](../NEXT_SESSION.md)에 기재했다. V2-00에서는 제품 테스트·CI 재조회·DB 접근·백업을 수행하지 않았다. 조사 단계에서는 브랜치 생성, 제품·Migration 변경, 스테이징·커밋·푸시·병합을 수행하지 않았다. 승인된 문서 전환 작업은 별도로 기록한다.

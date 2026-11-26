# V2-03 제품 구현 보고 — RESEARCH·SCRIPT·SCENES (Claude Code)

2026-11-26 KST · 상태 IMPLEMENTED(결합 검증 전) · 브랜치 `v2/03-product`

- 공통 기준 `610b9f88fb57e762c939e0bdc03734d3d609913b`. Codex 안전성 구현 `4299c6927fbc3c60a3c64c16afdc8749db71129c`(`v2/03-safety`)는 읽기 전용으로 대조했고 제품 브랜치에 병합하지 않았다.
- 근거: [구현 계획 R01~R07](../v2/V2-03_IMPLEMENTATION_PLAN.md), [품질 정책](../v2/05_CONTENT_QUALITY_AND_MONETIZATION.md), 안전성 보고서(`v2/03-safety`의 `docs/reports/V2-03_COMMON_AND_SAFETY_REPORT.md`).
- 공통 파일(STATE.md, TASKS.md, DECISIONS.md, OpenAPI, migration, 빌드 설정, lockfile)은 변경하지 않았다. 이 보고서의 인수인계를 통합 담당자가 공통 파일에 반영한다.

## 1. 구현 범위

| 영역 | 경로 | 내용 |
|---|---|---|
| 로컬 검수 | `backend/.../production/stage/` | `PlanningReview`(단계별 진입점), `ResearchReview`, `ScriptReview`, `ScenesReview`, `SpeechTiming`(speech-estimate-1), `Statements`(문장 규칙 v1), `TrustedDocuments` |
| FAKE·fixture | `backend/.../production/stage/fake/`, `backend/src/main/resources/planning-fixtures/` | `FakePlanningProvider`, `FakeResearchEvidenceProvider`, `FixtureDocuments`, `PlanningFixtures`; 과학 소재 3개(중성자별·금성·완보동물) |
| 실행기 | `backend/.../production/execution/` | `PlanningFlow`, `ProductionExecutor`의 RESEARCH·SCRIPT·SCENES 분기, `ExecutionMapper` claim 조건, 실행 구성 |
| 제작 상세 UI | `frontend/src/pages/ProductionPlanningSection.tsx` 외 | 조사·출처·주장 근거·한계, 대본·예상 발화, 차단·권고, 장면별 시간·내레이션·자막 원문·이미지 프롬프트·연출 |
| 테스트 | `backend/src/test/.../production/stage/`, `.../execution/PlanningExecutionIT.java`, `ContractPlanningPort.java`, `frontend/src/pages/ProductionPlanning.test.tsx`, `frontend/e2e/` | 아래 §4 |

### RESEARCH (R01·R02)
- 선정 후보의 질문·관점·조사 계획을 그대로 사용한다. 질문·관점이 다르면 `RESEARCH_INPUT_MISMATCH`로 차단한다.
- 출처 조회 상태와 주장 지지 판정을 분리했다. 서버가 문서를 보유한 출처(`TrustedDocuments`)만 근거가 된다. `VERIFIED`는 서버가 조회한 RETRIEVED 문서에만 허용하고, FAKE·FIXTURE·모델 단서의 검증 표시는 `SOURCE_FALSE_VERIFICATION`으로 차단한다. 오프라인 FIXTURE 문서는 FAKE 공급자 실행(`providerVersion`이 `fake-`로 시작)에서만 근거로 인정하며 FIXTURE로 표시된다.
- 보유하지 않은 출처·주소 불일치(`SOURCE_NOT_FOUND`), 문서에 없는 인용(`QUOTE_NOT_FOUND`), 사설·로컬·자격증명 포함 주소(`SOURCE_URL_INVALID`)를 차단한다. 지지 판정은 인용이 문서에서 확인된 `SUPPORTS`만 인정하고, CONTRADICTS·INSUFFICIENT는 지지로 세지 않는다.
- 대본 사용 주장(`scriptClaimIds`)의 사실은 지지가 필요하다(`CORE_EVIDENCE_INSUFFICIENT`, `FACT_CONTRADICTED`). 가설·가상 시나리오는 불확실성·전제를 반드시 남긴다. 대본에 쓰지 않는 미확인 내용은 `OPTIONAL_CLAIM_EXCLUDED` 권고로 제외만 한다.
- 검색어·출처 개수는 강제하지 않는다. 단일 충분 출처를 허용한다.

### SCRIPT (R03·R04)
- 구조·참조·언어·Hook 위치, 연결 주장 없는 사실 단정(숫자 없는 비교·인과·최상급 포함, P2), 연결 주장과 다른 수치, 근거에 없는 인과, 가설·시나리오만 연결된 구간의 단정형 문장, 실질적 반복, 예상 길이 부적합을 차단한다.
- 예상 발화 시간은 서버가 구간별로 다시 계산한다(`SpeechTiming`, 숫자·약어 발음과 쉼 포함). 공통 계약 `ProductionPlanning.timing`을 그대로 쓴다: 40~60초 정상, 35초 이상 40초 미만 및 60초 초과 65초 이하 `DURATION_ADJUSTMENT` 권고, 그 밖 `DURATION_UNSUITABLE` 차단. 신고한 길이가 서버 추정과 10% 넘게 다르면 차단한다.
- Hook 길이·흡인력, 결말, 설명 의도 누락, 미사용 주장, 유사 대본은 권고이며 다음 단계로 진행한다. 별도 검수 대기 상태와 자동 재작성은 없다.
- 반복 판정은 같은 채널의 최근 채택 대본 비교 입력(최대 20개)을 쓴다. 질문(설명 의도+Hook) 개념 유사도와 본문 개념·수치 중복을 함께 본다. 채널 브랜딩·구독 문구는 비교 전에 제거한다.

### SCENES (R05)
- 대본 구간이 순서대로 정확히 한 번씩 포함되는지(누락·중복·미지 구간·순서), 장면 번호 1..n, 양의 시간과 합계(대본 추정 ±500ms), 내레이션·자막 원문이 고정 대본에서 도출한 값과 같은지 검사한다.
- 화면 구성·이미지 프롬프트·연출 누락, 내레이션에 없는 수치 추가(`SCENE_NEW_FACT`), 생성 이미지를 실제 사진·영상처럼 표현(`SCENE_PRESENTED_AS_EVIDENCE`)을 차단한다. 전환 미지정·채널 시각 스타일 미반영·9:16 미지정은 권고다.
- FAKE 장면 계획은 채널 시각 스타일 + 소재 스타일 + 9:16 프레이밍을 공통 스타일로 쓰고, 장면 시간을 구간 추정 합으로 맞춘다.

### 실행기 연결
- `v2-planning-1` 요청의 DISCOVER·SELECT는 V2-02 흐름을 그대로 쓰고, RESEARCH·SCRIPT·SCENES는 `PlanningExecutionPort`로만 실행한다. 실행기는 공급자를 호출하지 않는다.
- 채택 영수증: 포트에서 저장 응답을 읽고 고정 입력과 함께 로컬 검수한 뒤, 같은 fenced 트랜잭션에서 `port.apply`(영수증 적용·검수 기록)와 단계 성공(`planning:{operationId}`) 또는 실패(`PLANNING_VALIDATION_BLOCKED`)를 커밋한다. 차단된 단계 뒤의 단계는 실행하지 않고 요청은 FAILED로 끝난다.
- 응답 조회 실패·검수 예외·적용 실패는 영수증을 미적용으로 두고 RECOVERY_REQUIRED로 재확인한다. 복구 제시는 같은 고정 입력·같은 효과를 다시 제시하며 시도 수를 늘리지 않는다. 미실행·실행 실패 영수증은 기존 적용 경로를 쓴다.
- 신규 파이프라인은 기본 비활성이다. `ai-studio.production.planning-enabled=true`이고 discovery 흐름과 `PlanningExecutionPort` 구현이 모두 있을 때만 실행기가 `v2-planning-1`을 claim한다. 제품 브랜치에는 포트 구현이 없으므로 설정을 켜도 claim하지 않는다. 기존 `v2-fixed-1`·`v2-discovery-1` claim과 실행은 바뀌지 않았다.

### 제작 상세 UI
- `GET /api/v1/production-requests/{id}/planning`을 읽기 전용으로 표시한다. FAKE 실행과 FIXTURE 출처를 경고로 표시하고, 서버가 조회 확인한 출처만 링크로 연다(FIXTURE 주소는 글자만). 확인 시각이 없으면 "조회하지 않음"으로 표시한다.
- 사실·가설·가상 시나리오·미확인을 나누고 대본 사용/제외, 주장별 근거(지지·반박·근거 부족)와 한계를 보여준다. 검수 결과는 필수 차단과 개선 권고 건수를 구분하고 차단이 있으면 펼쳐 둔다. 차단된 단계는 이후 단계 미실행을 안내한다.
- 과거 요청(본문 없음)과 조사 전 요청을 구분한다. 로딩·오류(다시 시도)·권한·404 처리는 기존 공통 컴포넌트와 query client 규칙을 따른다. 대본·타임라인 편집기는 만들지 않았다.

## 2. 공통 계약과의 대조
- Codex `PlanningExecutionService`의 실제 동작(고정 입력 비교, `fake-planning-1` 공급자 버전, 장면 내레이션·자막 도출, `apply`의 MANDATORY 트랜잭션과 영수증 적용, `accepted()`의 `planning:{id}` 결과 참조 조건, 최근 대본 비교 입력)을 확인해 실행기와 검수를 맞췄다.
- 공통 계약 변경은 하지 않았다. 변경 제안은 없다. 참고 사항: 실제 어댑터는 FAKE 응답의 `VERIFIED` 출처를 형식 오류(EXECUTED_FAILED)로 처리하므로, 그 경우 검수 기록 없이 단계가 실패한다. 제품 검수의 `SOURCE_FALSE_VERIFICATION`은 실제 공급자 응답에 대한 이중 방어다.

## 3. 결합 검증으로 남기는 항목
제품 브랜치에는 실제 저장·비용 어댑터가 없어 다음은 검증하지 못했다. 실행기 통합 테스트는 계약 대역(`ContractPlanningPort`: 실제 V2-01 비용 포트 + V12 테이블)으로 수행했다.
- 실제 `PlanningExecutionService`와 실행기 결합: 응답 저장·정산 원자성, 정산 후 적용 전 종료 재생, 결과 불명 격리, 두 worker 경합, 예산 거절.
- 결합 환경의 실제 백엔드 연결 브라우저 E2E(제작 시작 → 선정 → 조사·대본·장면 열람), 최신 CI, 별도 세션 독립 리뷰.
- 실제 공급자·실제 문서 조회·의미 검증은 V2-05 범위다.

## 4. 테스트

| 요구 | 테스트 |
|---|---|
| 서로 다른 과학 소재 2개 이상, 다른 대본·장면 구조 | `FakePlanningFlowTest`(3개 소재: 구간 7/6/6, 장면 5/3/6, 장면 묶음 구조 상이), `PlanningExecutionIT.twoScienceTopics…`(중성자별·완보동물, 실제 DB·API) |
| 단일 충분 출처 허용, 권장 개수 미강제 | `ResearchReviewTest.oneSufficientSource…` |
| 허위 출처·인용·검증 플래그·URL, 핵심 근거 부족 차단 | `ResearchReviewTest`, `PlanningExecutionIT.fabricatedSource…`, `…insufficientCoreEvidence…`(SCRIPT 이후 미실행·영수증 적용 확인) |
| 구조화 주장 목록에서 누락된 중요 사실 단정(P2) | `ScriptReviewTest.importantFactMissingFromTheClaimList…`(최상급·숫자 없는 인과·비교), `causeAddedToALinkedFactSegment…`, `hypothesisStatedAsFact…`, `numberThatIsNotInTheLinkedClaims…` |
| 표현이 다른 실질 중복 / 독립 설명 가치의 유사 대본 | `ScriptReviewTest.rewordedCopy…`(차단), `similarScriptWithItsOwnExplanation…`(권고만), `sharedChannelBrandingAlone…` |
| 경미한 권고만 있는 대본의 진행 | `ScriptReviewTest.minorQualityGaps…`, `slightlyShortScript…`, `PlanningExecutionIT.recommendationsOnly…`(요청 SUCCEEDED) |
| 예상 발화 시간 경계 | `ScriptReviewTest.durationBoundaries…`(34 999/35 000/39 999/40 000/60 000/60 001/65 000/65 001 ms), `SpeechTimingTest` |
| 장면 누락·중복·순서·시간 합계, 내레이션·자막 원문 일치 | `ScenesReviewTest`, `PlanningExecutionIT.sceneThatDropsASegment…`, `twoScienceTopics…`(API 응답에서 원문 일치·합계 확인) |
| 차단 후 후속 단계 금지, fenced 적용·복구·stale lease·취소 | `PlanningExecutionIT`(hypothesis/scene 차단, `failedApplyRollsBack…`, `staleLeaseCannotApply…`, `cancelAfterResearch…`) |
| V2-01·V2-02 실행기 회귀, 기존 요청 호환 | 기존 `ProductionExecutorIT`, `DiscoveryExecutionIT`, `StepPlannerTest` 등 전체 통과, `PlanningExecutionIT.earlierPipelines…`, `executorWithoutThePlanningFlow…` |
| 제작 상세 UI·FAKE 표시 | `ProductionPlanning.test.tsx` 7건, `e2e/production.spec.ts` V2-03 시나리오(라이트·다크·모바일 가로 넘침 없음) |

## 5. 실제 실행 결과

| 명령 | 결과 |
|---|---|
| `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks` (격리 임시 MariaDB 11.8.2, 127.0.0.1:3398, binlog ON) | 단위 279건(기존 212 + 신규 67), 통합 234건(기존 223 + 신규 11), 실패·오류·건너뜀 0, bootJar 성공 |
| frontend `npm run lint`, `npm run typecheck`, `npm run build` | 통과 |
| frontend `npm test` (Vitest) | 96건 통과(기존 89 + 신규 7) |
| frontend `npx playwright test` (설치된 Edge, Mock API) | 14건 통과(기존 13 + 신규 1) |
| 비밀정보 패턴·`git diff --check` | 발견 없음(정규식 상수 오탐 1건만 확인) |

- OpenAPI·생성 타입·migration은 변경하지 않았으므로 OpenAPI lint와 타입 재생성은 다시 실행하지 않았다.
- 실제 백엔드에 연결한 브라우저 E2E는 실행하지 않았다(포트 구현이 없는 제품 브랜치에서는 planning 요청이 실행되지 않음). 결합 검증 항목이다.
- DB: 사용자 DB 미접속. 격리 인스턴스에 V1~V12만 적용, migration 변경 없음. 실제 유료 API·YouTube 미사용.

## 다음 작업
1. 통합 담당자: 이 보고서를 STATE.md·TASKS.md에 반영하고, `v2/03-product`와 `v2/03-safety`의 결합 브랜치를 만든다.
2. 결합 검증: 실제 `PlanningExecutionService`로 `PlanningExecutionIT`의 흐름(대역 없이)과 안전성 `ProductionPortsIT`를 함께 실행하고, 격리 DB·실제 백엔드 브라우저 E2E로 두 소재의 제작 시작 → 선정 → 조사·대본·장면 열람을 확인한다. 결합 시 `ContractPlanningPort`는 실제 어댑터와 함께 등록되지 않도록 테스트 구성을 정리한다.
3. 별도 세션 독립 리뷰와 최신 PR HEAD CI.

## 6. 한계
- 로컬 검수는 구조·참조·시간 규칙과 `Statements`의 문장 패턴, 개념·수치 기반 유사도다. fixture로 확인한 사례를 넘어 일반 문장의 사실 단정 탐지나 의미상 중복 판별이 완전하다는 뜻이 아니다.
- 반복 판정 임계값(질문 0.5, 본문 0.45 또는 수치 0.75)은 fixture로 보정했다. 측정값은 재작성 사본이 질문 0.59·본문 0.54·수치 1.00(차단), 같은 소재의 다른 설명이 질문 0.07·본문 0.38·수치 0.67(권고)이다.
- 오프라인 fixture는 3개 소재만 있다. FAKE 발굴이 다른 소재를 고르면 조사 단계에서 근거 부족으로 차단된다. 이는 의도된 FAKE 동작이다.
- 기존 관찰(범위 밖, 미수정): `FakeStageWork`는 항상 45초를 선정하므로 LONGFORM `v2-fixed-1` 요청은 SELECT에서 VALIDATION_FAILED가 된다.

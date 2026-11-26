# V2-03 제품·안전성 결합 검증

2026-11-26 KST · `v2/review-v2-03` · 구현 결합, 별도 독립 리뷰 대상

## 최종 승인 및 병합 (2026-11-27 KST)

**DONE.** 최종 집중 리뷰 APPROVE와 사용자 승인 후 [PR #6](https://github.com/Hjinsu/ai-studio/pull/6)을 `v2/integration`에 병합했다. 승인 SHA `42cbbcfd2762306e3cd6ec7dced8c6b864325cda`의 [CI](https://github.com/Hjinsu/ai-studio/actions/runs/45830743931)는 7/7이었다. 병합 커밋 `0aa11d0ea2eec7333cb901bc0ac140a8b414d82d`의 Git 트리는 승인 SHA와 일치한다. 종료 단계에서 전체 리뷰와 통합 테스트를 반복하지 않았다. V2-05의 실제 AI 의미 검증과 기존 OpenAPI 경고 2건은 비차단 후속이다.

## F05 화면비 수치 예외 제거 (2026-11-26 KST)

기준 `e0083695192c1a01253e1e1d138ba4ed0bc6864c`, `v2/review-v2-03`, [Draft PR #6 검사](https://github.com/Hjinsu/ai-studio/pull/6/checks). **HOLD**는 별도 독립 리뷰 APPROVE 및 사용자 승인까지 유지한다.

- 이전 HOLD는 사실 비율의 앞 문맥이 문장부호로 분리되면 화면비 지시로 판정되어 수치 검수를 우회하는 문제였다. `ScenesReview`에서 화면비 예외를 제거했다. 장면 설명·이미지 프롬프트·연출 지시에 기존 `Statements.numbers`를 적용한다. 이전 우회 사례와 콜론·괄호·긴 대시·쉼표 반례에서 `SCENE_NEW_FACT`에 9와 16을 남긴다. 숫자 없는 프레이밍, 정상 fixture, 대본에 근거가 있는 수치는 허용한다.
- `FakePlanningProvider` 공통 스타일·일반 프롬프트, 번들 SCENES fixture 세 개, 프론트 테스트 대역을 숫자 없는 세로 구도로 갱신했다. 공통 스타일 권고와 화면 레이블도 같은 표현을 사용한다. V2-04는 실제 화면비·해상도를 구조화된 미디어 프로필에서 관리한다. [구현 계획](../v2/V2-03_IMPLEMENTATION_PLAN.md)
- 신규 SCENES 입력은 `scenes-prompt-2` / `scenes-rules-2` / `fake-scenes-2`를 사용한다. 고정 입력·이미 채택된 결과의 읽기 경로는 기존 저장 내용을 사용한다. 구버전 미적용 영수증은 저장된 입력과 응답으로 재생한다. 근거 없는 수치가 있으면 영수증을 적용하고 SCENES에서 차단한다. 기존 입력·요청·응답 해시와 비용 증거를 재작성할 필요가 없다.
- `AI_STUDIO_REVIEW_EDGE=true`로 `gradlew.bat --no-build-cache :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks` 실행. 단위 293건(ScenesReviewTest 22건), 격리 MariaDB 11.8.2 통합 255건(실제 API Edge 1건 포함), 실패·오류·건너뜀 0건. bootJar 성공. 중성자별·완보동물 각 11단계 완료. 구버전 미적용 영수증 2건은 정상 완료와 근거 없는 수치 차단을 각각 검증했다. 원본 입력·요청·응답 해시와 비용 증거가 동일했고, SCENES 작업 및 발송은 기존 1건을 유지했다. F01~F04·F06, 비용 보호, lease·fencing, 취소, 영수증 재생, V11 데이터 보존 및 격리 DB V12 적용 회귀 통과. 임시 서버의 정상 종료 확인.
- `npm run lint`, `npm run typecheck`, `npm run build`, `npm test` 97건, `npm run e2e` Mock Edge 14건 통과. `frontend/tools/openapi-typescript`에서 `npm run lint:openapi`(기존 경고 2건)와 `npm run check` 통과. OpenAPI·생성 타입·기존 Migration 파일은 기준과 동일하다.
- Gitleaks 8.30.1 게시 대상 이력 검사와 `git diff --check` 통과.

## F05 최종 수정 결합 및 검증 (2026-11-26 KST)

**HOLD.** 기준 `eabf980780d06474767f6195895c3b0df84a926b`와 Claude 브랜치 `v2/03-f05-final-fix`의 원격 SHA `1d31f5046bce52cc70a6fb4236b7b05abc079b5c`를 확인했다. 별도 `ai-studio-v2-review-03` worktree의 `v2/review-v2-03`에서 fast-forward했다. 제품 변경 파일은 `ScenesReview.java`와 `ScenesReviewTest.java`다.

이전 독립 재리뷰는 주변 프레이밍 단어가 사실 비율을 수치 검수에서 제외하는 문제로 HOLD였다. 이번 변경은 주변 단어 예외를 제거하고, 문장부호로 나눈 절 전체가 허용된 화면비 지시 문법과 일치할 때만 9:16을 제외한다. 지정된 이전 사례 여섯 개 모두 Venus fixture의 실제 SCENES 검수에서 `SCENE_NEW_FACT`에 9와 16을 남겼다. 명확히 분리된 정상 화면비 지시, 정상 fixture와 대본 근거 수치도 통과했다.

### 신규 F05 반례

| 사실 설명의 장면 프롬프트 | 기대 `SCENE_NEW_FACT` 참조 | 실제 참조 |
|---|---|---|
| `The mass ratio: vertical 9:16` | 9, 16 | 없음 |
| `The mass ratio (9:16 vertical)` | 9, 16 | 없음 |
| `The mass ratio — 9:16 frame` | 9, 16 | 없음 |
| `The mass ratio, 9:16 composition` | 9, 16 | 없음 |

`ScenesReviewTest`의 Venus fixture 첫 장면 이미지 프롬프트만 교체하고 `SCENE_NEW_FACT`의 참조를 읽어 재현했다. `ScenesReview.CLAUSE_SEPARATOR`가 사실을 지칭하는 앞부분을 분리하면 `FRAMING_CLAUSE`가 나머지 절을 허용한다. `The mass ratio: 9:16 vertical` 및 ASCII 하이픈을 쓴 `The mass ratio - 9:16 frame`은 각각 9와 16을 보존했다. 정상 대조군은 `A Venus illustration. Aspect ratio: 9:16.`, `A Venus illustration (vertical 9:16)`, `A Venus illustration, 9:16 vertical frame`, `A Venus illustration — vertical 9:16`이다.

Claude 담당 수정에서 문장부호로 이어진 사실 비율을 수치 검수에 남기고 위 네 사례와 정상 대조군을 회귀로 추가한다. 일반 자연어 의미 검증은 V2-05 범위다. Draft PR #6은 별도 독립 리뷰 APPROVE 및 사용자 승인까지 HOLD다. [Draft PR #6 검사](https://github.com/Hjinsu/ai-studio/pull/6/checks)

### 로컬 검증

- `AI_STUDIO_REVIEW_EDGE=true`, `gradlew.bat --no-build-cache :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks`. Java 21·Gradle 9.8.2에서 단위 293건(ScenesReviewTest 22건), 격리 MariaDB 11.8.2 통합 253건(실제 API Edge 1건 포함), 실패·오류·건너뜀 0건, bootJar 성공. F01~F04 및 F06의 검수 크기·영수증 재생, 응답 형식과 타임아웃 분리, 비교 스냅샷, 공급자 준비 조건, 동결된 길이 범위를 재검증했다. 비용 보호·lease·fencing·사용자 취소 회귀와 V11 데이터 보존·V12 적용을 포함한다. 실제 API Edge에서 AUTO·MANUAL·차단·재조회·이력·다크·모바일·소유자 격리·미인증 경로를 검증했다.
- Node 24.19.0에서 `npm ci`, `npm run lint`, `npm run typecheck`, `npm test` 97건, `npm run build`, `npm run e2e` Mock Edge 14건 통과.
- `frontend/tools/openapi-typescript`에서 `npm ci`, `npm run lint:openapi`, `npm run check` 통과. 기존 경고 2건은 CSRF 4xx 응답 미정의와 미사용 PageMeta다.
- 사용자 PATH를 다시 읽어 FFmpeg 9.1과 Gitleaks 8.30.1을 확인했다. Gitleaks의 결합 증분 및 HEAD 도달 이력에 탐지 0건. 로컬 전체 ref 검사에서는 게시 대상 밖의 과거 `BudgetMapper.java` SQL 조인 오탐 2건이 재확인됐다. `git diff --check` 통과.
- 기존 migration, OpenAPI 및 생성 타입은 기준과 동일하다. 임시 MariaDB는 루프백의 별도 포트와 임시 데이터 디렉터리에서 사용했다.

## HOLD 수정 결합

독립 리뷰 기준 `54ffea3c09246b61a057c427e6210591af20cccb`에 Codex `ec10c07`, Claude F05 `5f8dfe3`, F06 `a6fa744`, F05 후속 `8fd969a051ec2a1d440508dc988bf5a7652662ff`를 결합했다. 장면 비율 추가 회귀를 반영한 코드 SHA는 `782b6d5a31fe150764418ada5551326e6022ee9d`다. 최종 리뷰 대상은 이 기록을 포함한 [Draft PR #6](https://github.com/Hjinsu/ai-studio/pull/6)의 HEAD다.

| ID | 통과한 회귀 |
|---|---|
| F01 | 128KiB를 넘는 검수 기록을 제한하고 차단을 보존한다. 정산된 영수증을 새 lease에서 한 번 적용하며 추가 SCRIPT 발송이 없다. |
| F02 | 잘못된 SCENES DTO는 `EXECUTED_FAILED`로 정산하고 실제 로컬 소켓 타임아웃은 결과 불명으로 격리한다. |
| F03 | 같은 채널의 채택 대본 21개 이상에서 최근 20개를 선택한다. 신규 채택과 경합해도 고정 입력·해시·정산 이벤트를 재사용한다. |
| F04 | 공급자 유무를 요청 생성과 실행기 claim에서 같은 조건으로 판정한다. 공급자 부재 시 기존 planning 요청은 대기한다. |
| F05 | orbital resonance ratio, mass ratio, gravitational ratio 및 `vertical 9:16 ratio of the tower`를 검수한다. 명확한 영어 화면비 지시에는 예외가 적용된다. |
| F06 | 고정된 채널 길이 범위를 기준으로 LONGFORM과 SHORTS의 FAKE SELECT를 검증한다. |

F05는 영어 표현에 대한 제한된 규칙이다. 괄호로 감싼 화면비는 검수 대상이 될 수 있고 비영어 화면비는 지원 범위 밖이다. 일반 자연어 의미 검증은 V2-05에서 다룬다. 독립 리뷰 APPROVE와 사용자 승인 전까지 HOLD를 유지한다.

- `AI_STUDIO_REVIEW_EDGE=true`로 `gradlew.bat --no-build-cache :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks` 실행. 단위 292건(ScenesReviewTest 21건), 격리 MariaDB 11.8.2 통합 253건(실제 API Edge 1건), 실패·오류·건너뜀 0건, bootJar 성공. 실제 저장·비용 포트, V11 데이터 보존과 V12 적용, 제한 운영 계정의 planning 실행, 실제 Edge에서 소유자 200·다른 인증 사용자 404·미인증 401을 검증했다.
- 프론트 lint·typecheck·build, Vitest 97건, Mock API Edge 14건 통과. 최초 Vitest에서 예산 정책 화면의 로딩 대기 한 건이 시간 초과했고, `npm test -- --maxWorkers=2` 재실행에서 전체 통과했다.
- OpenAPI lint 기존 경고 2건, 생성 타입 일치. 기존 migration, OpenAPI, 생성 타입 변경 없음. Gitleaks 및 `git diff --check` 통과. [최신 HEAD CI](https://github.com/Hjinsu/ai-studio/pull/6/checks)

## 최초 결합 기준

| 기준 | SHA |
|---|---|
| 통합 | `c4832b7eca2cbf89893bce92fa2b980263cf695e` |
| 공통 조상 | `610b9f88fb57e762c939e0bdc03734d3d609913b` |
| Claude 제품 | `56949413de1cd387109a3e7659c61e3fd5f4a1c7` |
| Codex 안전성 | `4299c6927fbc3c60a3c64c16afdc8749db71129c` |
| 두 이력을 보존한 병합 | `c1fdff14a1bbff7d44862ae7ac78334096258fb5` |

[구현 계획](../v2/V2-03_IMPLEMENTATION_PLAN.md) · [제품 보고서](V2-03_PRODUCT_REPORT.md) · [공통·안전성 보고서](V2-03_COMMON_AND_SAFETY_REPORT.md) · [품질 정책](../v2/05_CONTENT_QUALITY_AND_MONETIZATION.md)

## 결합 변경

- `PlanningExecutionIT`에서 `ContractPlanningPort` 대역을 제거했다. 실제 `PlanningExecutionService`, `ProductionCostService`, V2-02 발굴·선정 포트를 사용한다. 공급자 출력과 장애 지점만 spy 또는 테스트 전용 fixture로 제어한다. 회귀 테스트는 가짜 어댑터의 상태·영수증을 신뢰하지 않는다.
- AUTO fixture는 정상 후보 계약과 기존 점수 평가를 사용한다. 후보의 두 조사 계획은 검색할 과제이며 근거로 채택하지 않는다. RESEARCH의 오프라인 문서와 주장 지지 검수는 기존과 같이 적용된다.
- `ScriptReview.java`의 공통 반복성 검수에서 IFNOVA 이름 예외를 제거했다. 사용자의 채널 설정과 Revision을 그대로 사용한다.
- 실제 Edge에서 제작 상세 폴링이 종료를 먼저 관찰하면 결과 조회가 오래된 빈 응답에서 멈추는 경합을 확인했다. `ProductionPlanningSection.tsx`는 종료 전후의 쿼리 키를 구분해 최종 결과를 다시 조회한다. `ProductionPlanning.test.tsx`에서 이 순서를 검증한다. 제작 시작 화면의 과학 분야 전용 표현도 일반 채널 안내로 변경했다.
- `frontend/e2e/live-production.mjs`는 Edge에서 빌드된 React 앱과 테스트 HTTP 서버·격리 DB를 연결하는 선택 실행 검증이다. Mock API E2E와 별개다.
- 공통 DTO, OpenAPI, 생성 타입과 V1~V12는 공통 기준과 같은 내용이다. 별도 제안 문서 커밋 `9c07d0eace479ff745016638880cc36b42740da4`는 문서 브랜치에 보존했다.

## 실행기·콘텐츠 검증 범위

| 항목 | 검증 경로 |
|---|---|
| 정상 AUTO·MANUAL | AUTO 중성자별과 MANUAL 완보동물의 11단계 완료. 대본 7/6구간, 장면 5/6개, 첫 내레이션·구조 차이. 대본과 동일한 장면 내레이션 및 자막 원문, 장면 시간 합계 검증. |
| 입력 고정 | 실행 결과의 입력/본문 SHA-256과 비용 영수증 결과 해시, SCRIPT의 RESEARCH 참조와 SCENES의 SCRIPT 참조 일치. 계획 프롬프트 버전 보존. 요청 생성 뒤 채널 언어·전략·내레이션·시각 스타일 변경 후에도 기존 Revision/설정으로 처리. |
| 장애 및 재생 | 안전성 `ProductionPortsIT`의 응답/증거 동시 영속화와 정산 전 장애. `PlanningExecutionIT`의 정산 후 검수/단계 적용 롤백, 새 lease 재생, stale lease, 두 worker 경합, 중복 발송/정산 방지. |
| 실패 경계 | 결과 불명 격리와 미실행 증거 재조정, 예산 부족, 취소 뒤 다음 단계 발송 금지. 근거 부족 및 허위 URL, 가설의 사실화, 장면 누락에 대한 차단과 후속 미발송. 개선 권고만 있는 대본의 정상 진행. |
| 품질 단위 회귀 | 사실·가설·가상·미확인 분류, 누락된 중요 단정, 표현 변경 반복 차단과 독립 가치 허용, 길이 경계, 장면 순서/합계, 가상 이미지를 실제 사진·증거로 표현할 때 차단. |

## 채널·언어 및 기존 LONGFORM

- V2-03의 포트·실행기·저장 경로는 특정 채널명이나 What If 형식을 선택 조건으로 사용하지 않는다. 요청의 채널 설정과 언어·Revision을 고정한다. FAKE 자료는 중성자별·금성·완보동물 세 분야 표본으로 제한된다.
- 영어 발화량·문장 패턴과 fixture 기반 주제 검수가 현재 인수 범위다. FAKE 대본은 영어만 반환하며 비영어 스냅샷에 적용하면 언어 검수에서 차단된다. 비영어 발화량·중요 사실 탐지, 비과학 분야의 사실 검수와 실제 프롬프트 품질은 별도 검증 대상이다. 기존 V2-02 평가기의 영어 어휘 규칙과 `science` 전략 적합 판정은 보존했다.
- `v2-fixed-1` LONGFORM은 기준 SHA의 `FakeStageWork`도 45초를 반환한다. 결합에서 SELECT `VALIDATION_FAILED`, RESEARCH 미실행을 재현했다. 기본 480~720초 설정을 포함해 설정 가능한 LONGFORM 최소값(60초)을 충족할 수 없는 기존 FAKE 결함이다. LONGFORM을 인수하기 전에 해당 범위에서 별도로 수정한다.

## 검증 결과 및 인수 조건

- `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest`에서 단위 279건, bootJar, Edge 제외 격리 MariaDB 통합 241건이 통과했다. Edge는 권고 패널을 열고 확인하도록 스크립트를 수정한 뒤 별도 실행에서 통과했다. 격리 MariaDB 11.8.2 binlog ON에서 Flyway 신규 설치·업그레이드와 운영 계정 권한 회귀를 포함했다.
- 실제 Edge는 빌드된 프론트와 실제 HTTP API 및 저장·비용 어댑터를 연결했다. 로그인, AUTO 및 MANUAL 제작 완료, 조사·출처 및 대본 시간, 권고를 펼쳐 조회, 근거 부족 차단, 이미지 프롬프트·내레이션·자막, 제작 이력, 새로고침, 미인증 401, 라이트·다크·390px 가로 넘침을 검증했다. 실행 명령은 프론트 build 후 격리 DB 환경에서 `AI_STUDIO_REVIEW_EDGE=true`와 `gradlew.bat :backend:integrationTest --tests '*PlanningExecutionIT.liveEdgeConnectsTheFrontendToTheActualServer'`다.
- 프론트 lint·typecheck·build, Vitest 97건, Edge Mock API E2E 14건 통과. OpenAPI lint는 기존 경고 2건, 생성 타입 일치. 공백 검사와 gitleaks의 게시 대상 이력 검사를 통과했다. 로컬 에이전트 체크포인트 ref에서만 발견된 기존 SQL 조인 오탐은 배포 이력에 포함되지 않는다.

- P0/P1은 실행·비용·권한 및 CI 결과를 확인한 뒤 별도 독립 리뷰에서 판단한다.
- P2는 정규식 밖 중요 주장과 의미상 중복, 비영어·비과학 검수의 보정이다. V2-05 실제 공급자 활성화 전 사람이 판정한 표본으로 확인한다.
- P3는 실제 공급자 연결 시 기존 제작 이력까지 포함해 실행 방식별 라벨을 전환하는 일이다. FAKE/FIXTURE는 현재 명시적으로 표시한다.
- V1~V12는 격리 DB에만 적용한다. 사용자 DB, 유료 API, 게시, 통합 브랜치 병합은 수행하지 않았다.
- 최신 Draft PR HEAD의 CI와 동일 SHA를 확인한 뒤 별도 Codex 세션에서 독립 리뷰한다. 사용자 최종 승인 후 종료 절차를 진행한다.

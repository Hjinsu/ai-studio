# V2-03 제품·안전성 결합 검증

2026-11-26 KST · `v2/review-v2-03` · 구현 결합, 별도 독립 리뷰 대상

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

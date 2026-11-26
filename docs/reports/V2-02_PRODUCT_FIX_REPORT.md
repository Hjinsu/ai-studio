# V2-02 제품 결합 수정 보고 (Claude Code)

2026-11-26 KST · 브랜치 `v2/02-product-fix` · 기준 `463c3c0dd56911b69dfd6e1f475e92726391ff38` · 상태 **IMPLEMENTED**(독립 리뷰·CI·최종 결합 전)

[결합 수정 지시서](V2-02_COMBINED_CONTRACT_HANDOFF.md)의 Claude 수정 지시 1~5항과 사용자 착수 지시(제작 이력 화면)를 반영했다. Codex 담당인 저장·비용·공통 계약(`discovery/persistence`, `cost`, `contract`, `api`, OpenAPI, migration, 생성 타입)과 공통 파일(STATE·DECISIONS·TASKS)은 수정하지 않았다.

## 변경 내용

| 지시 | 파일 | 내용 |
|---|---|---|
| 1 지문 | `execution/ProductionExecutor` | 재구성한 `Response`는 재평가에만 쓰고 선정 지문은 `SelectionBatch.responseHash()`(영수증과 대조된 저장 본문 해시)를 사용. 값이 없으면 SELECT를 진행하지 않는다 |
| 1 재검사 저장 | 같은 파일 | 연결 성공 시 5인자 `recordSelection(..., fresh)`로 최신 재검사 저장. AUTO 적격 후보가 없으면 같은 트랜잭션에서 `recordExhaustedSelection` 후 `DISCOVERY_NO_ELIGIBLE_CANDIDATE` 실패 |
| 1 언어 | `discovery/DiscoverySelections` | `Selection`에 스냅샷 `input.language()` 전달(요청 후 채널 언어가 바뀌어도 Revision은 고정 언어) |
| 2 재대기 | `ProductionExecutor`, `execution/ExecutionMapper.awaitChoice` | MANUAL 대기로 돌아갈 때 SELECT가 `RETRY_WAIT`면 같은 fenced 트랜잭션에서 `PENDING`으로 복귀, `retry_at`·`last_error_code` 초기화, `attempts` 보존. claim·planner·API의 대기 조건(`SELECT PENDING`)과 일치해 lease를 반납하고 재시작 후에도 대기한다 |
| 3 선택 | `frontend/src/api/production.ts`, `pages/ProductionRequestDetailPage.tsx` | `chooseProductionCandidate(id, { candidateId, version, batchToken })`. 확인 대화상자를 열 때 세 값을 고정하고, 폴링으로 version·token이 바뀌거나 대기가 끝나면 확인 버튼을 막고 다시 선택하라고 안내 |
| 3 단계 오류 | 상세 화면, `ProductionRequestLabels.STEP_ERROR` | 진행 단계 표에 `lastErrorCode`(한국어 + 원 코드) 열 추가 |
| 4 버전·라벨 | `discovery/DiscoveryRules` | `PROMPT_VERSION = ProductionDiscovery.PROMPT_VERSION`. 형식 오류 영수증은 실행기 기존 경로대로 `EFFECT_EXECUTED_FAILED`로 끝나며 화면 라벨은 "외부 실행 실패(비용 확정) — 응답 형식 오류 포함" |
| 5 테스트 | `execution/DiscoveryExecutionIT`, `InjectingDiscoveryPort`, `ScriptedDiscoveryProvider`, `FakeDiscoveryPort` 삭제 | 아래 참조 |
| 제작 이력 | `pages/ProductionRequestsPage.tsx`, `routes.tsx`(`/production/requests`), `layout/nav.ts`, `layout/AppShell.tsx` | `GET /api/v1/production-requests`로 요청 ID·채널·선정 소재·상태·비용(확정·예약·한도)·생성 시각과 상세 링크만 표시. 기존 `QueryView`·`DataTable`·`Pagination` 재사용, 검색·통계 없음 |
| FAKE 표시 | `ProductionRequestLabels.productionStatusLabel`, `FAKE_RUN_NOTICE` | 완료를 "FAKE 완료"로 표시하고 목록·완료 상세에 "실제 AI 호출과 영상 파일 생성 없이 단계와 비용 정산만 검증" 안내 |

메뉴 활성 표시: `/production` 아래에 `/production/requests` 메뉴가 생겨 두 메뉴가 함께 활성으로 보이던 문제를 `hasNestedItem`으로 정확 일치 처리했다. 그 결과 상세(`/production/{id}`)에서는 사이드바 활성 메뉴가 없다(이동 경로 표시는 기존과 같다).

## 테스트 구성 (지시 5)

- `DiscoveryExecutionIT`는 실제 `ProductionDiscoveryService`(저장·비용 경계), 실제 V2-01 비용 포트, 실제 선정 포트를 쓴다. 공급자만 `@Primary ScriptedDiscoveryProvider`(FAKE 응답 스크립트, 발송 후 예외 = 결과 불명)로 바꾼다.
- 장애 주입은 `@Primary InjectingDiscoveryPort`가 실제 어댑터에 위임하면서 같은 트랜잭션·DB에서 부수 효과 직전(`applyEvaluation`, `lockForSelection`, `recordSelection`)에만 훅을 실행한다. 4인자 `recordSelection` 호출은 테스트 실패로 처리한다.

## 실제 테스트 결과

| 명령 | 결과 |
|---|---|
| `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks --no-build-cache` (격리 임시 MariaDB 11.8.2, `127.0.0.1:3321`, 스크래치 데이터 폴더) | 단위 **210/210**, 통합 **222/222**, bootJar 성공. V2-01 `ProductionExecutorIT`, `CombinedDiscoveryIT`, `ProductionRequestApiIT`, `ProductionPortsIT`, `ProductionCostIT` 포함 |
| `DiscoveryExecutionIT` | **16/16** |
| `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` | 통과, Vitest **88/88** |
| `npx playwright test` (Edge, Mock API) | **13/13**. 라이트·다크·모바일 390px 가로 넘침 없음, 스크린샷 육안 확인 |

`DiscoveryExecutionIT` 회귀 범위:
- 수동 재선택·재시작: `RETRY_WAIT → 무효화 → PENDING(재시도 시각·오류 초기화, 시도 1 보존) → lease 반납 → 재시작 실행기에서 미claim → 재선택 → 완료(시도 2)`, 수동 대기 lease 미점유·재시작 유지, 선택 후 카탈로그 변경 시 재대기(시도 미차감)
- 자동 차순위: SELECT 직전 최고 후보 중복 시 차순위 연결, 모든 후보 차단 시 `recordExhaustedSelection` 후 실패
- 표현이 다른 실질 중복의 동시 SELECT: 두 MANUAL 요청이 문구·제목이 다른 같은 질문·관점을 선택하고, 두 SELECT 트랜잭션이 요청 락을 잡은 뒤 동시에 채널 락으로 진입 → 하나만 연결, 다른 요청은 `DUPLICATE` 재검사와 함께 재대기, Topic 1개
- 최초 평가와 최신 재검사 보존: `evaluation_json`은 그대로, `recheck_json`에 최신 차단 사유(성공·무효화·소진 모두)
- 잘못된 토큰(409)·오래된 version과 token(409)·무효 후보(422)·다른 후보 재선택(409)
- 형식 오류: 응답 미저장·평가 없음, 비용 1원 확정·적용, 단계 `EFFECT_EXECUTED_FAILED`, 상세 API `lastErrorCode`
- 기타: 결과 불명 재제시 무재발송, 평가 커밋 실패 후 저장 응답 재생, SELECT 연결 후 실패 전체 롤백(재검사 포함), 같은 묶음 경쟁, 지출 차단, 금지 주제, 채널 언어 변경 후 스냅샷 언어 사용, 목록의 선정 소재

프론트: 선택 본문의 `batchToken`, 대화상자 중 묶음 변경 시 확인 차단, 단계 오류 라벨(선정 불가·형식 오류), FAKE 완료, 제작 이력 목록·빈 상태·403·상세 이동·메뉴 활성. E2E 대역도 필수 nullable `lastErrorCode`와 `batchToken` 검증을 사용한다.

## 미검증·남은 사항

- 실제 백엔드에 연결한 브라우저 확인은 하지 않았다(E2E는 Mock API). 통합 브랜치에서 수행 필요.
- CI, Codex 독립 리뷰, 최종 결합은 후속 단계. OpenAPI·생성 타입은 변경하지 않아 재검사하지 않았다.
- 목록 API에 실행 방식(FAKE/실제) 필드가 없어 FAKE 표시는 현재 단계 고정 문구다. 실제 공급자 연동 단계에서 API 필드로 바꾸도록 공통 계약 변경을 제안한다(Codex 조율).
- 사용자 DB 미접속, 실제 유료 호출 없음, migration 변경 없음(검증 DB에 V1~V11만 적용).

## 다음 작업

Codex: 이 브랜치 SHA를 검증 브랜치에 결합해 실제 백엔드 브라우저 확인·CI·독립 리뷰를 수행하고 STATE.md를 갱신한다.

재개 명령: 임시 MariaDB를 띄운 뒤 `AI_STUDIO_IT_DB_HOST/PORT/USER/PASSWORD`를 지정해 `gradlew.bat :backend:test :backend:integrationTest`, 프론트는 `frontend`에서 `npm ci; npm run lint; npm run typecheck; npm test; npm run build; npx playwright test`([로컬 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md)).

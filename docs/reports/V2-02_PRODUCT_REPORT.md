# V2-02 제품 구현 보고 — 소재 자동 발굴 및 선정 (Claude Code)

2026-11-26 KST · 브랜치 `v2/02-product` · 기준 `0dddd5f390a77b33a19f2091ff438a2fbff223d2` · 상태 **IMPLEMENTED**(결합 검증·독립 리뷰 전)

[구현 계획과 R01~R07](../v2/V2-02_IMPLEMENTATION_PLAN.md)의 Claude 담당 범위다. 공통 파일(STATE·DECISIONS·TASKS·OpenAPI·migration·생성 타입)은 수정하지 않았다. 아래 변경 제안과 인수인계는 통합 담당자(Codex)가 반영한다.

## 구현 내용

| 영역 | 파일 | 내용 |
|---|---|---|
| 규칙·프롬프트 | `production/discovery/DiscoveryRules`, `DiscoveryPrompt`, `DiscoveryInputs` | 규칙 v1 상수, 영어 과학 Shorts 고정 프롬프트(채널 설정은 JSON 데이터 블록, 지시로 취급 금지·출처 날조 금지), 정규 입력·해시 |
| FAKE 생성기 (R05) | `FakeDiscoveryProvider` | 요청 ID+정규 입력 SHA-256 시드. 같은 요청은 같은 후보·해시, 다른 요청은 과학 소재 24종 × 설명 관점 6종을 다르게 조합. 사실 주장 없이 질문·조사 계획만 생성 |
| 응답 검증 | `DiscoveryResponseValidator`, `DiscoveryResponseCodec`, `DiscoveryResponseException` | 버전·정확한 후보 수·필수값·공백/제어문자·길이·항목 수·점수 범위·정규 32KiB. 엄격 JSON 파서(미지·중복 키, 실수, 깊이 거부)와 정규 JSON/SHA-256 |
| 중복·유사성 (R02) | `TextSimilarity`, `RuleBasedDiscoveryEvaluator` | Topic 정규화 → 불용어·접미사·동의어 표 → 개념 Jaccard. 정확한 제목 해시, 질문 ≥0.80+관점 겹침 또는 ≥0.60+관점 겹침 ≥0.50은 `DUPLICATE`, 관점이 다른 동일 질문·불명확한 경계는 `SIMILARITY_UNCERTAIN`. 묶음 내 반복은 뒤 후보 차단. 기존 brief의 `Question:`/`Angle:` 줄을 읽고 없으면 제목+brief로 비교 |
| 차단 | 같은 평가기 | `CHANNEL_PROHIBITED`(기본 금지 + 스냅샷 금지 주제, 단어 경계·복수형), `UNSUPPORTED_ASSERTION`(승인 근거 ID 없는 주장 — V2-02는 승인 근거 없음, 제목·관점·구성안의 단정·수치 표현, 조사 계획의 URL·인용 표시) |
| 점수 (R01) | 같은 평가기, `DiscoverySelections` | 6개 항목 각 4개 규칙 신호 → 0/25/50/75/100% × 최대점 내림. 모델 점수는 신호 하나. 순위: 총점 → 근거 → 독창성 → 후보 ID |
| 선정 값 | `DiscoverySelections` | 라벨형 brief, 길이=스냅샷 최소·최대 중간값, 지문=요청·후보·응답 해시·규칙 버전·정규 제목·brief·길이. SELECT 재검사 병합(이전 차단 유지) |
| 실행기 (R03·R04·R07) | `execution/ProductionExecutor`, `StepPlanner`, `ExecutionMapper`, `DiscoveryFlow`, `ProductionExecutionConfiguration` | 아래 참조 |
| UI | `frontend/src/api/production.ts`, `pages/ProductionRequest{StartPage,DetailPage,Labels}`, `routes.tsx`, `layout/nav.ts` | 제작 시작(채널·유형·자동/직접·후보 수, 지출 차단 안내, 위험 확인 대화상자, 작업 단위 멱등 키), 상세(비용·11단계·후보 카드·항목 점수·차단 사유·비교 대상·평가 근거·선정 근거, 직접 선택·취소 확인, 진행 중 5초 재조회) |

### 실행기 흐름

- `v2-discovery-1` 요청은 `DiscoveryExecutionPort` 빈이 있을 때만 claim한다. 없으면 기존처럼 `v2-fixed-1`만 처리한다.
- DISCOVER: 스냅샷 입력 → `port.discover`(비용 포트 경유, 실행기는 공급자를 직접 호출하지 않음). 처분 처리는 기존 효과 경로와 공유(불명·대기·복구는 예약 유지 park, 거부는 실패). 채택 영수증이면 `port.response` → 검증 → `port.catalog` → 평가 → **같은 fenced 트랜잭션**에서 `applyEvaluation`(영수증 적용 포함) + 단계 완료. 적격 0개면 `DISCOVERY_NO_ELIGIBLE_CANDIDATE`로 실패, 추가 발굴·재생성 없음. 저장 응답이 계약 위반이면 영수증만 적용하고 `DISCOVERY_RESPONSE_INVALID`로 실패. 평가 커밋 전 장애는 park 후 같은 키로 재제시해 저장 본문을 재생한다.
- 수동 대기: claim 쿼리와 planner가 `MANUAL·평가 완료·선택 의도 없음·DISCOVER 성공·SELECT PENDING`을 제외/Release한다. lease를 갖지 않으며 재시작 후에도 DB 상태로 재구성한다. 취소·복구는 대기보다 우선한다.
- SELECT: 한 fenced 트랜잭션에서 요청 락 → `lockForSelection`(채널 락) → 최신 카탈로그 재평가·병합 → AUTO는 최고 순위, MANUAL은 선택 후보 확인. 수동 후보가 탈락하면 `invalidateManualChoice` 후 남은 후보가 있으면 다시 대기(**시도 미차감**), 없으면 실패. 연결 시 시도 차감 → `ProductionSelectionPort.link` → `recordSelection` → Run 시작 → 단계 완료. 예외는 전체 롤백 후 로컬 시도 1회로 기록(재시도 대기). 예산 락·공급자 호출 없음.

## 테스트 (실제 실행 결과)

| 명령 | 결과 |
|---|---|
| `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks --no-build-cache` (격리 임시 MariaDB 11.8.2 `127.0.0.1:3307`) | 단위 **210/210**(기준 165 + 45), 통합 **215/215**(기준 202 + 13), bootJar 성공 |
| `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` | 통과, Vitest **83/83**(기준 73 + 10) |
| `npx playwright test` (Edge, Mock API) | **12/12**(기준 10 + 2). 라이트·다크·모바일 390px 가로 넘침 없음 확인, 스크린샷 육안 확인 |

신규 테스트:
- 단위: `FakeDiscoveryProviderTest`(동일 요청 동일 해시, 20개 요청 서로 다른 묶음, 5·8·10개, 코덱 왕복, 프롬프트 데이터 격리), `DiscoveryResponseValidatorTest`(버전·개수·공백·제어문자·길이·항목 수·점수 범위·미지/중복 키·깊이·크기), `RuleBasedDiscoveryEvaluatorTest`(정확 제목, 동의어 표현 변경, 관점만 다른 동일 질문, 경계, 같은 분야 차별 소재 통과, 공통 어휘의 별개 질문 통과, 라벨 없는 기존 brief, 묶음 내 반복, 금지 소재, 근거 없는 주장·단정·허위 출처, 5단계 점수, 모델 점수 영향 제한, 독창성 감점, 동점 순서, 결정성), `DiscoverySelectionsTest`, `StepPlannerTest` 수동 대기 2건.
- 통합 `DiscoveryExecutionIT`(API 생성, **실제 V2-01 비용 포트·FAKE 공급자**, 실제 선정 포트, V10 테이블 위의 제품 브랜치 대역 `FakeDiscoveryPort`): AUTO 11단계 완료·최고 후보 연결·비용 8건 정산, SELECT 직전 중복 생긴 후보 건너뛰기, 전부 차단 시 DISCOVER 실패·후속 효과 0, 응답 형식 오류 시 비용 확정·평가 없음, 지출 차단 시 dispatch 0, 결과 불명 시 예약 유지·재시작 재제시 무재발송·시도 미차감, 평가 커밋 실패 후 저장 응답 재생(재생성 없음), SELECT 연결 후 실패 전체 롤백·재시도, 두 요청 동시 실행에서 같은 Topic 중복 생성 없음, 수동 대기 lease 미점유·다른 요청 처리·재시작 후 대기 유지·동일 선택 재전달·다른 후보 409, 대기 중 취소, 선택 후 카탈로그 변경 시 재대기·무효 후보 422·다른 후보로 완료, 채널 금지 주제 스냅샷 적용.
- 프론트: `ProductionRequest.test.tsx` 10건(채널 필수·확인 후 생성·기본값 본문·멱등 키, 직접/롱폼 본문과 재시도 시 같은 키, 지출 차단 안내, 후보·점수·차단 사유·비교 대상, 선택 확인과 본문, 409 충돌 표시, 자동 선정 결과, 전부 차단, 취소 확인, 403·404), E2E `production.spec.ts` 2건.

미실행: 실제 Codex 저장·비용 어댑터(`discovery/persistence`)와의 결합, OpenAPI lint/생성 타입 재검사(계약 파일 변경 없음), 실제 백엔드 연결 브라우저 E2E, CI.

## 공통 계약 변경 제안 · Codex 인수인계

1. **`SelectionBatch`에 `responseHash` 추가 제안.** 현재 실행기는 저장된 평가 묶음의 후보로 응답을 재구성해 `DiscoveryResponseCodec.sha256`으로 해시를 계산한다. 어댑터가 응답을 같은 코덱(`canonicalJson`/`sha256`)으로 저장해야 일치한다.
2. **호출 시점 명확화.** `lockForSelection`과 `invalidateManualChoice`는 SELECT가 아직 PENDING/RETRY_WAIT인 상태(시도 차감 전)에서 호출된다. `ProductionSelectionPort.link`와 `recordSelection`은 같은 트랜잭션에서 단계를 RUNNING으로 바꾼 뒤 호출된다. 실제 어댑터가 RUNNING을 요구하지 않아야 한다.
3. 어댑터는 공급자 응답을 저장하기 전에 `DiscoveryResponseValidator.validate`를 적용하고 계약 위반이면 `EXECUTED_FAILED`로 정산할 것을 제안한다. 실행기는 저장된 응답도 다시 검증하며 위반 시 영수증 적용 후 실패한다. `DiscoveryProvider` 구현은 `FakeDiscoveryProvider`(@Component)다.
4. AUTO SELECT의 재검사 결과는 저장 포트가 없어 `selection_reason`에만 남는다. 필요하면 AUTO에도 재평가 저장 메서드를 추가한다.
5. 금지 주제는 대역이 `providerOptions.discovery.prohibitedTopics`(문자열 배열)로 읽는다. 실제 `input()`의 구조를 이 형태로 확정하거나 다르면 알려 달라.
6. `ProductionSelectionPort.Selection`에 언어 추가(계획 R07 5항)는 Codex 범위로 남겨 두었다. 현재 링크는 채널 언어를 사용한다.
7. API 제안: 단계 응답에 `lastErrorCode` 노출(UI가 `DISCOVERY_NO_ELIGIBLE_CANDIDATE` 등 실패 사유를 표시할 수 있도록), 제작 요청 목록 조회(현재 UI는 생성 직후 상세 URL로만 접근).
8. `ExecutionMapper.nextClaimable(discovery)`·`awaitingManualChoice`는 `production_discovery`를 읽는다(운영 계정 SELECT 권한은 V10 권한에 포함).

**결합 검증 필요**: 실제 어댑터로 R06(영수증 `discovery:{id}`·본문 해시 재생, 형식 오류 비용 확정), R07(실제 채널 락 순서와 동시 SELECT), 카탈로그 상한(최근 100 + 관련 100), 위 1·2항 호환을 확인한 뒤 통합 브랜치에서 전체 회귀를 실행한다. 제품 브랜치의 `FakeDiscoveryPort`는 이 결합 테스트를 대신하지 않는다.

## 알려진 한계

- 규칙 v1의 어휘 비교는 표에 없는 동의어·은유를 놓치거나 공통 어휘가 많은 별개 질문을 보류할 수 있다(계획 R02와 동일). 실제 AI 연동 시 승인된 의미 기반 평가로 보강한다.
- 제목 상한은 YouTube 기준 100자로 Topic 제한(255자)보다 엄격하다.
- DB·migration·OpenAPI 변경 없음. 사용자 DB 미접속, 실제 유료 호출 없음.

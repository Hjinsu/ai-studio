# T03-B-INT 제품 계약 통합 보고서

제품 기준 `b5ffc73d61e100dbf4d9a847360aea404efd8e7d`, 설계 기준선 `d495296a85c0c312807a06c6fd24aa19b1f4872f`. [Draft PR #3](https://github.com/Hjinsu/ai-studio/pull/3)은 `t03/b-domain → t03/integration`이다.

## A1~A8

| ID | 통합 결과 |
|---|---|
| A1 | Topic 두 경로, Content·Revision 다섯 경로에 총 10개 작업을 추가했다. ADMIN 세션·쓰기 CSRF, 문자열 BIGINT ID, UTC 및 기존 페이지 형식을 유지했다. |
| A2 | `Content.budgetSummary`는 optional이다. B 응답에서는 생략한다. D가 모든 콘텐츠 조회를 검증한 뒤 required로 전환한다. |
| A3 | 채널 보관의 활성 포인터 또는 실제 비종료 Run 보유 조건에 409 INVALID_STATE_TRANSITION을 명시했다. |
| A4 | Topic 중복의 409 DUPLICATE_RESOURCE에 `field: title`, `reason: DUPLICATE_TOPIC:<기존 ID>`를 명시했다. Revision 변경 없음은 409 DUPLICATE_RESOURCE, `field: null`, `reason: NO_CHANGES`다. |
| A5 | 콘텐츠 생성의 채널 기본 언어·최신 설정 revision, Revision 생성의 최신 설정, 유형별 영상 길이, 객체 settings의 정수 규칙과 canonical UTF-8 16 KiB 한도를 명시했다. |
| A6 | 콘텐츠 보관·Revision 생성 상태 오류, 교차 채널 Topic 및 소유 Revision 미존재 404를 명시했다. |
| A7 | Run·Job·Queue 작업은 C 통합 대상으로 남겼다. |
| A8 | 요청값 지문, 본문 문자열의 Unicode 코드포인트 한도와 짝 없는 surrogate의 400 VALIDATION_FAILED를 명시했다. |

## 검증

- `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks --no-build-cache` 최종 재실행 성공. 단위 151건, 통합 153건, 실패·오류·스킵 0건. `bootJar` 생성 확인. 통합 테스트는 loopback 임시 MariaDB 11.8.2의 별도 포트(binlog OFF)에서 실행했다.
- OpenAPI 도구 디렉터리에서 `npm ci`, `npm run lint:openapi`, `npm run generate`, `npm run check`를 실행했다. Redocly 성공, 기존 CSRF 응답 규칙과 미사용 PageMeta 경고 2건, 생성 타입 drift 0.
- 프론트엔드에서 `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` 통과. Vitest 73건.
- 테스트의 draft overlay를 제거하고 `ApiTestSupport`의 제품 명세 검증기를 공유한다. 기존 음성 계약 테스트와 ContentApiIT의 실제 요청·응답 검증을 유지한다. 첫 실행에서 초안의 nullable `anyOf`가 기존 평탄 스키마 회귀 조건을 위반해 명세에서 평탄화했다. 공통 페이지 메타의 최대 크기 100을 맞췄다. 두 번째 실행에서는 강화된 언어 패턴으로 잘못된 `EN` 요청이 제품 검증기에서 거부됐다. 테스트는 요청의 명세 거부와 서버의 명세 일치 400 응답을 각각 확인하도록 보강했고 최종 재실행에서 모두 통과했다.
- `git diff --check`, staged diff 검사 및 gitleaks 8.30.1의 staged 변경·대상 HEAD 이력 검사 통과. 로컬의 모든 refs 검사에서는 대상 HEAD의 조상이 아닌 에이전트 체크포인트 2건에서 `BudgetMapper.java` SQL 비교에 대한 `generic-api-key` 탐지가 있었다. 이 refs는 푸시 대상에서 제외했다.
- V1~V5는 `47ef697` 대비, V6 및 백엔드 제품 코드·프론트엔드 제품 로직·CI 설정은 `b5ffc73` 대비 diff가 없다. V6 LF 정규화 SHA-256 `304900fda20821c83a9f3f3a56c9568f4215544158020569bd6f36e9d2412588`. 미출시 V6 일회성 예외 기록을 보존한다.

## 변경 파일

- `docs/api/openapi.yaml`, `frontend/src/api/generated/schema.d.ts`
- `backend/src/test/java/com/aistudio/content/`의 `ContentApiIT.java`, `ContentOpenApiContractTest.java`. 기존 `T03ContractOverlayTest.java`를 제품 테스트로 이전하고 합성 전용 `T03ContractOverlay.java`를 제거했다.
- `STATE.md`, `TASKS.md`, `docs/00_INDEX.md`, `docs/plans/t03-final/T03_FINAL_API.md`, `docs/plans/t03-final/T03_IMPLEMENTATION_HANDOFF.md`
- `docs/reviews/T03_B_CODEX_REVIEW.md`, `docs/reviews/T03_B_FIX_CODEX_REVIEW.md`, 본 보고서.

## Git 및 CI

이 커밋을 푸시한 후 [PR #3 검사](https://github.com/Hjinsu/ai-studio/pull/3/checks)에서 동일 SHA의 실행을 확인한다. 이전 CI `45634311996`은 수정본 근거이며 이번 통합 검증에 사용하지 않는다.

## 후속 게이트

- [인수 조건 C-G01~10](../plans/t03-final/T03_IMPLEMENTATION_HANDOFF.md#t03-c-필수-인수-조건)에 Q0/R10 잠금·원자성·영향 행 수·실제 서비스 장애 주입 및 고아 Run fail-closed를 기록했다. V7은 C, V8부터 D다.
- 운영 binlog ON 환경의 migration 계정 및 DBA 트리거 배포 절차와 실제 운영 최소 권한은 배포 검증이 필요하다. 운영 DB·개발 DB, 실제 C 서비스 장애 주입, 유료 Provider 호출 및 외부 업로드는 이번 검증 범위 밖이다.
- T03-B는 IMPLEMENTED 상태다. 최신 PR HEAD의 7개 CI 검사가 완료되면 사용자 종료 및 병합 승인을 요청한다. C~F는 BLOCKED다.

# T02-FINAL-RV 최종 표적 독립 검토

- 검토일: 2026-11-25 (Asia/Seoul)
- 검토 담당: Codex (구현 담당 세션과 분리된 검토 세션; 동일 모델 계열 사용)
- 프로젝트/브랜치: `C:\Users\PC-94\Desktop\ai-studio`, `wip/t02-implementation-checkpoint-2026-11-25`
- Draft PR: #1
- 제품 검토 범위: `7256fe1..53b305b`의 `frontend/` 및 직접 영향 범위
- 최신 HEAD: `55e0720` (문서 기록 커밋; 제품 판정은 `53b305b` 기준)
- 사용자 지정 최신 CI: `45573471061`, 7/7 성공으로 확인함

## 결론

RVX-01과 RVX-03은 지정 시나리오를 코드·테스트·직접 실행으로 확인하여 통과 판정한다. RVX-02의 지연된 401/200 자체는 차단되지만, 일부 성공 콜백이 콜백 진입 후 인증 세대 변경을 다시 확인하지 않고 캐시 무효화·UI 효과를 수행한다. 따라서 T02는 **조건부 종료 승인 권고**이며, RVX-02의 콜백 경계 보강 또는 설계 담당자의 명시적 위험 수용이 필요하다.

확인된 결함은 제품 코드에 수정하지 않았다.

## RVX-01 — 인증 세대 경계 및 이전 POST 재전송

판정: **통과**.

`frontend/src/api/http.ts`의 `AbortSignal` 세대가 요청 시작, CSRF 취득 전·후, 전송 직전, 응답 본문 처리 전·후에 검증된다. 세대가 바뀌면 `SessionChangedError`로 종료되어 이전 401·403·200이 새 세션의 캐시·토큰·화면 상태에 반영되지 않는다. 403 복구는 같은 세대에서만 한 번 수행되고, 호출자가 준 멱등 키와 본문을 유지한다.

`frontend/src/api/admin.ts`의 로그인 성공·로그아웃 시작/완료 및 `frontend/src/queryClient.ts`의 현재 세션 401 만료 처리에서 세대가 전환되고 CSRF가 폐기된다. 로그인 자격 증명 401/429는 `AUTH_FLOW_META`로 만료 처리와 분리된다.

독립 실행한 `frontend/src/api/http.test.ts` 및 `frontend/src/App.test.tsx`에서 지연 POST의 403·401·200, CSRF 취득 중 세대 전환, 새 세션의 신규 토큰 유지, 동일 세대 403 1회 복구, 로그인·로그아웃 회귀를 확인했다. 관련 테스트는 모두 통과했다.

## RVX-02 — 지연 응답, 정상 만료, CSRF 복구 및 콜백 경계

판정: **조건부 통과 / MEDIUM 잔여 위험**.

다음은 통과한다.

- 이전 세션의 지연 401은 `ApiError.generation`과 `isCurrentUnauthorized` 검사에서 탈락한다.
- 이전 세션의 지연 200/403은 `request()`의 세대 검증에서 `SessionChangedError`가 되어 새 세션의 성공·CSRF·캐시 갱신으로 이어지지 않는다.
- 현재 세션의 401만 `createQueryClient()`가 만료 처리하며, 병렬 401은 `expiring`으로 한 번만 처리한다.
- 동일 세션의 403 CSRF 복구는 요청당 최대 한 번이며, 로그인·로그아웃 및 재로그인 후 자동 mutation 재실행을 막는다.

잔여 위험 RVX-02-CB (MEDIUM, 직접적인 비용 발생은 확인하지 못함):

- 근거: `frontend/src/api/useSessionMutation.ts:15`의 세대 검사는 `onSuccess`/`onError` 콜백에 진입할 때 한 번만 수행한다.
- `frontend/src/pages/ChannelDetailPage.tsx:90`, `frontend/src/pages/PolicyDetailPage.tsx:71`, `frontend/src/pages/PolicyDetailPage.tsx:119`, `frontend/src/pages/SpendingControlPage.tsx:42`, `frontend/src/pages/SpendingControlPage.tsx:51`의 일부 콜백은 세대 인자를 받지 않거나, 캐시 무효화 전·후 세대를 확인하지 않는다.
- 따라서 mutation 응답이 현재 세대였던 순간 콜백에 진입한 뒤 세대가 바뀌면, 이전 콜백이 새 세션의 캐시를 무효화하거나 토스트/로컬 상태를 변경할 수 있다. HTTP 응답 자체의 차단은 확인했으나 이 콜백-내부 경합은 독립 테스트로 입증되지 않았다.
- 권고: 모든 보호 mutation 콜백에 세대를 전달하고, 콜백의 각 비동기 경계 전후에서 현재 세대를 확인한다. 캐시 무효화도 동일 세대에서만 수행하도록 공통화한다.

이 항목은 현재 테스트의 지연 401·403·200 응답 차단을 부정하지 않지만, 사용자 요구사항의 “이전 요청의 성공·실패 콜백에 의한 신규 세션 간섭 방지”를 완전 충족했다고 보기 어렵게 한다.

## RVX-03 — 원장 확인 및 정산 재시도 안전성

판정: **통과**.

`frontend/src/pages/ReservationDetailPage.tsx:120`의 `useSettlement`는 예약 상세 쿼리와 별도의 `reservation-check/{id}/{key}` 확인 쿼리를 사용한다. 사용자 확인 시 예약 ID·멱등 키·확정 본문을 캡처하며, 미확정 상태에서는 새 요청 준비를 무시한다.

502/네트워크 오류 뒤 원장 확인이 반복 실패해도 확인 창과 요청 스냅샷을 유지한다. Escape/취소는 잠기고, 수동 “원장 다시 확인”은 GET만 수행한다. 확인되지 않은 상태에서 자동 POST는 발생하지 않으며, 수동 기록은 같은 키·같은 본문을 사용한다. 기존 키가 원장에서 발견되면 성공 처리하고 POST를 추가로 보내지 않는다.

확정, 해제, 조정, 환불의 정상 경로와 재시도 키 보존을 `frontend/src/pages/pages.test.tsx`에서 확인했다. 실제 비용·외부 provider 호출은 수행하지 않았다.

## 직접 실행한 검증

| 검증 | 결과 |
|---|---|
| `frontend`: `npm.cmd test` | 샌드박스의 Node worker `spawn EPERM`으로 실패(환경 제약) |
| `frontend`: `npm.cmd test -- --pool=threads --maxWorkers=1` | **70/70 통과**, 6개 파일 |
| `frontend`: `npm.cmd run lint` | 통과 |
| `frontend`: `npm.cmd run typecheck` | 통과 |
| `frontend`: `npm.cmd run build` | 통과 |
| OpenAPI: `npm.cmd run lint:openapi` | 유효, 기존 경고 2개 |
| 생성 타입: `npm.cmd run check` | 저장소 생성 타입과 일치 |
| `frontend`: `E2E_PORT=5196 npm.cmd run e2e` (Edge Mock API) | **10/10 통과** |
| `git diff --check 7256fe1..53b305b` | 통과 |

E2E는 실제 백엔드가 아닌 브라우저 네트워크 Mock API이며, 실제 지출·유료 API 호출은 없었다.

## 기존 테스트 및 CI 유지 여부

- 구현 보고서 기준 기존 Vitest 60건과 신규 10건을 합친 70건이 유지되었고, 직접 실행에서도 70/70을 확인했다.
- Edge Mock API E2E 10건은 직접 실행에서 10/10을 확인했다.
- 최신 CI `45573471061`은 사용자 제공 기준 7/7 성공으로 기록했다. CI 전체 job의 재실행은 하지 않았다.
- OpenAPI 계약 및 생성 타입 검사는 직접 통과했다.
- 제품 커밋 범위에는 백엔드·OpenAPI·DB migration 변경이 없음을 diff로 확인했다.

## 신규 확인 결함 및 심각도

1. **RVX-02-CB / MEDIUM** — 현재 세션에서 시작한 mutation 성공 콜백이 세션 변경 후에도 캐시 무효화·UI 효과를 수행할 수 있는 경합. 지연 HTTP 응답 차단과 별개의 콜백 경계 문제이며, 직접 재현은 미완료다.

그 외 RVX-01·03 범위에서 재현되는 CRITICAL/HIGH 결함은 확인하지 못했다.

## 미검증 항목

- 실제 백엔드에 연결된 격리 브라우저 E2E(이번 검토에서는 Edge Mock API만 사용).
- 실제 브라우저에서의 로그인·세션 만료·CSRF 핵심 시나리오와 운영 프록시/다중 탭·다중 기기 경합.
- 백엔드 단위·통합 테스트의 로컬 재실행. 최신 CI 결과를 근거로 대체했으며 사용자 개발 DB 3306은 사용하지 않았다.
- RVX-02-CB 콜백 내부 경합의 별도 재현 테스트.
- 운영 DB, 실제 provider, 실제 비용·게시 경로.

## 잔여 위험

- P-T02-05의 새로고침·화면 이탈·다른 탭/기기에서 새 요청 키로 수동 재입력할 위험은 승인된 조건부 수용을 유지한다.
- 운영 프록시 뒤 로그인 제한(CR-01), 정합성 오류 복구 절차(CR-02), T03 선행 설계 제약은 기존 승인 조건을 유지한다.
- RVX-02-CB를 해소하기 전에는 세션 경계 요구사항을 무조건 통과로 선언하지 않는다.

## T02 종료 승인 권고

**조건부 승인 권고.** RVX-01과 RVX-03은 종료 승인 가능 수준이다. T02 최종 DONE 전에는 RVX-02-CB를 보강·재검증하거나, 설계 담당자와 사용자가 해당 MEDIUM 위험을 명시적으로 수용해야 한다. 이 보고서는 main 병합, T02 DONE 전환, T03 착수 또는 비용 발생을 승인하지 않는다.


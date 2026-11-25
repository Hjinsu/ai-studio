# T02-UI-FIX-EXT — 세션 경계 및 정산 안전성

- 2026-11-25 · 구현 및 자체 검증 Codex · T02 IMPLEMENTED
- 통합 작업 트리 `C:/Users/PC-94/Desktop/ai-studio`, 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`, [Draft PR #1](https://github.com/Hjinsu/ai-studio/pull/1)
- 인수 당시 로컬과 원격 HEAD `7256fe10e439761ff236d7ae818429da48ca447c`, 작업 트리 clean. 이전 제품 커밋 `646179a`, 인수 당시 CI [45567685701](https://github.com/Hjinsu/ai-studio/actions/runs/45567685701) 성공 7/7.
- 이번 제품 커밋 `53b305bbf08d2ff8f5f0f4e6195c309203ffcec6`. 이 문서부터는 보고서와 협업 문서만 갱신한다. 최종 PR HEAD와 해당 실행 결과는 [Draft PR #1 Checks](https://github.com/Hjinsu/ai-studio/pull/1/checks)에서 확인할 수 있다.
- 표적 [교차 리뷰 원문](../reviews/T02_CODEX_UI_FIX_REVIEW.md)은 `refs/reviews/t02-ui-fix-rv`의 blob `9590772c8f44b4f8226486b32c40bca74954fe7c`와 일치한다.

## 결함과 회귀 검증

| ID | 수정 전 재현 및 원인 | 수정 후 검증 |
|---|---|---|
| RVX-01 HIGH | 이전 POST의 지연 403을 새 로그인 뒤 반환하면 세션 공용 CSRF 토큰을 새로 취득하고 두 번째 POST를 보냈다. | 세대 교체 후 원래 POST만 관측했다. 새 토큰과 지출 제어 캐시는 유지됐다. CSRF 조회 중 세대 전환 시 이전 토큰 게시와 이전 POST 전송을 차단했다. 기존 동일 세대의 403 복구 1회는 유지됐다. |
| RVX-02 MEDIUM | 이전 POST의 지연 401이 새 로그인 이후 `/auth/me` 재조회를 유발했다. 이전 POST의 지연 200도 정상 성공 경로에 진입했다. | 같은 세대의 병렬 401에서 만료 재조회 1회. 이전 401·200은 새 인증, 토큰, 캐시 및 화면 효과에 반영되지 않았다. 로그인 자격 증명 401·429, 정상 로그아웃 및 재로그인 후 자동 mutation 재실행 방지 회귀를 확인했다. |
| RVX-03 HIGH | 502 뒤 원장 확인 GET에 503을 주면 예약 상세와 동일한 쿼리 상태를 오류로 바꿔 확인 창과 키를 잃었다. | 확인 조회 503 반복·Escape 이후에도 창과 스냅샷을 유지했다. 원장이 복구되어도 GET 확인만 했고, 수동 재시도에서 동일 예약·키·본문으로 POST했다. 이미 기록된 키는 완료 안내로 처리했다. 과금 확정·해제·조정·환불 회귀를 확인했다. |

수정 전에는 `git archive 7256fe1 frontend`로 임시 디렉터리에 기준 소스를 복원하고 새 테스트의 당시 버전을 복사해 `npm.cmd ci`와 `npm.cmd test -- src/App.test.tsx src/pages/pages.test.tsx -t 'isolates a held|RVX-03'`를 실행했다. 4건 모두 기대한 이유로 실패했다. 보류된 403·401·200의 완료 순서는 Promise 제어로 고정했다. 기존 Edge 시나리오 10건은 그대로 유지했다.

## 구현 및 변경 파일

- `frontend/src/api/{http.ts,client.ts,admin.ts,useSessionMutation.ts}`, `frontend/src/queryClient.ts`, `frontend/src/layout/AppShell.tsx`. 초기 세대는 브라우저 앱이 시작할 때 열린다. 성공 로그인, 명시적 로그아웃 시작·완료, 현재 세대 401에 의한 만료에서 이전 세대를 무효화하고 CSRF를 지운다. 보호 요청은 시작 세대를 유지하며 CSRF 획득, 전송, 응답 본문 읽기, 403 복구 및 401 처리에서 세대를 확인한다. 이전 세대 전송 취소는 서버 처리의 롤백을 보장하지 않는다.
- `frontend/src/pages/{ChannelDetailPage,ChannelsPage,ExchangeRatesPage,PoliciesPage,PolicyDetailPage,ReservationDetailPage,SpendingControlPage}.tsx`. 보호 mutation 실행과 결과 처리에 세대 검사를 적용했다. 후속 캐시 갱신은 같은 시점에 시작하고 기다린 뒤 화면을 바꾸는 경로는 세대를 재확인한다.
- `frontend/src/pages/ReservationDetailPage.tsx`, `frontend/src/ui/Dialog.tsx`. 정산 확인을 열 때 예약 ID·멱등 키·확정 본문을 캡처한다. 원장 확인은 독립 쿼리에서 기록 확인·현재 미확인·조회 실패를 구별한다. 미확정 동안 창 닫기와 새 요청 전환을 잠그고, GET 확인과 같은 스냅샷의 수동 POST를 별도 버튼으로 제공한다. 조회에서 기록을 찾지 못한 사실은 최초 요청의 처리 실패를 뜻하지 않는다.
- `frontend/src/{App.test.tsx,api/http.test.ts,pages/pages.test.tsx}`. 기존 검증을 유지하며 10건을 추가했다. 원장 미확인 안내를 변경한 두 검증의 기대 문구는 새 안내에 맞췄고 재시도·키 검증은 유지했다.
- `STATE.md`, `TASKS.md`, `DECISIONS.md`, `docs/00_INDEX.md`, `docs/plans/T03_PLAN.md`, `docs/reports/{T02_REPORT,T02_UI_EXT_FIX_REPORT}.md`, `docs/reviews/T02_CODEX_UI_FIX_REVIEW.md`. 결정·진행 상태·후속 제약·리뷰 원문을 기록했다.

## 실행 결과

| 명령 | 결과 |
|---|---|
| `frontend`에서 `npm.cmd run lint`, `npm.cmd run typecheck`, `npm.cmd run build` | 성공 |
| `frontend`에서 `npm.cmd test` | Vitest 6개 파일 70/70, 기존 60건 유지 |
| `frontend`에서 `E2E_PORT=5196`으로 `npm.cmd run e2e` | Edge Mock API 10/10 |
| `frontend/tools/openapi-typescript`에서 `npm.cmd ci`, `npm.cmd run lint:openapi`, `npm.cmd run check` | 성공. 기존 OpenAPI 경고 2건. 임시 경로에 재생성한 타입과 저장소 타입 일치 |
| 저장소에서 gitleaks 8.30.1 `git --staged --redact .`, `git --redact .` | 탐지 없음 |
| 제품 커밋 GitHub Actions | [45573014769](https://github.com/Hjinsu/ai-studio/actions/runs/45573014769) 7/7 성공. Linux 단위·격리 MariaDB 통합·Boot JAR·Compose·이미지, Windows 백엔드 단위·Boot JAR, 프론트엔드 Linux·Windows, Edge E2E, OpenAPI·생성 타입, gitleaks |

## P-T02-05 및 종료 조건

전체 새로고침, 화면 이탈, 다른 탭과 기기에서 미확정 조정·환불을 새로 입력하면 별개 원장 이벤트로 처리될 수 있다. 이 위험은 T02 종료 판정에 한해 조건부 수용했다. 실제 비용 발생 또는 유료 API 활성화 전까지 서버 중심의 영속 요청 식별 및 결과 조회·복구를 완료해야 한다. 정상적으로 반복하는 동일 내용의 별개 조정은 계속 허용한다. [DECISIONS](../../DECISIONS.md) · [T03 선행 제약](../plans/T03_PLAN.md)

실제 백엔드 연결 브라우저 E2E, 실DB 및 유료 API 호출은 미실행이다. 로컬 백엔드 테스트는 이번 범위에서 재실행하지 않았다. API·생성 타입·백엔드·DB·migration 변경이 없으며, CI의 백엔드 통합은 격리된 Testcontainers 환경을 사용한다. 이 문서의 결과는 구현자 자체 검증으로서 독립 교차 리뷰와 사용자 재확인 및 최종 승인이 남아 있다.

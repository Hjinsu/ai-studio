# T02-FINAL-PATCH — RVX-02-CB

- 2026-11-25 · 구현 및 자체 검증 Codex · T02 IMPLEMENTED
- [Draft PR #1](https://github.com/Hjinsu/ai-studio/pull/1) · `wip/t02-implementation-checkpoint-2026-11-25`
- 인수 시 로컬·원격 HEAD `55e0720f40a7de5a180361d9836431d88fb9fa96`, 제품 기준 `53b305b`, [기준 CI 45573471061](https://github.com/Hjinsu/ai-studio/actions/runs/45573471061) 성공 7/7. 작업 트리에 있던 [최종 리뷰 원문](../reviews/T02_FINAL_REVIEW.md)을 내용 변경 없이 보존했다.
- 제품 커밋 `55ea8522bdaad49b06a3f3c1bfeaf9b4e40e5665`. 이후 보고서와 인수인계 문서만 반영했다. 최종 문서 포함 HEAD 및 해당 CI 기록은 [Draft PR Checks](https://github.com/Hjinsu/ai-studio/pull/1/checks)에서 확인할 수 있다.

## 재현과 회귀

`git archive 53b305b frontend`로 임시 기준 소스를 준비했다. 새 테스트를 복사하고 `npm.cmd ci` 후 `npm.cmd test -- src/pages/pages.test.tsx -t 'RVX-02-CB'`를 실행했다. 동일한 세 테스트를 수정본의 전체 Vitest 실행에서도 확인했다. Promise로 성공·실패 콜백 진입, 보류된 조회 또는 무효화 반환, 로그아웃·재로그인, 늦은 완료의 순서를 제어했다.

| 경로 | 기준 제품 | 수정 제품 |
|---|---|---|
| 정합성 검사 성공 뒤 보류된 캐시 갱신 | 이전 쿼리의 취소·정리 단언 실패. 이전 응답이 새 캐시 값을 덮어쓴 현상은 재현하지 않았다. | 이전 조회 취소. 재로그인 뒤 지연 응답을 풀어도 새 캐시의 값과 무효화 상태 유지, 이전 토스트 없음, POST 1회 |
| 채널 생성 성공 뒤 무효화 완료 보류 | 새 세션에서도 이전 성공 토스트가 남아서 실패. 이전 콜백의 상세 이동은 원래 세대 검사로 차단됐다. | 이전 토스트 제거. 지연 완료 후에도 목록 유지, 이전 상세 요청 없음 |
| 502 정산 실패 콜백의 보류된 원장 조회 | 통과. 기존 후속 세대 검사로 완료 안내 및 UI 오염이 발생하지 않았다. | 통과. 늦은 기록 확인이 새 캐시·안내·대화상자를 변경하지 않는다. POST 1회 |

기준 총 2건 실패·1건 통과. 수정 제품에서 3건 통과. 같은 세대의 정상 화면 이동, 알림, 무효화, 정산 원장 확인과 동일 키 재시도는 기존 테스트를 그대로 실행했다.

## 변경 파일과 세대 보호

- `frontend/src/api/useSessionMutation.ts` — 호출 시점의 인증 세대를 성공·실패 콜백에 전달한다. 캐시 작업 직전과 작업 종료 시 세대를 확인한다. 해당 쿼리 객체와 실행 Promise를 기억하여 세대 전환 시 실행 중이거나 일시 정지된 조회를 취소한다. 같은 키로 새로 만들어지거나 이미 교체된 조회에는 취소를 전달하지 않는다.
- `frontend/src/pages/{ChannelDetailPage,ChannelsPage,PolicyDetailPage,PoliciesPage,SpendingControlPage,ExchangeRatesPage,ReservationDetailPage}.tsx` — 요청 세대를 캐시 갱신과 보호 토스트에 전달한다. 비동기 응답 이후 상태 변경 또는 이동 전에 세대를 확인한다. 정산의 확인 요청과 멱등 키는 기존 흐름을 유지한다.
- `frontend/src/ui/{Toast.tsx,toast-context.ts}` — 보호 알림은 지난 세대가 무효화되면 닫힌다. 같은 세대의 표시 시간과 인증 흐름의 기존 알림은 유지한다.
- `frontend/src/pages/pages.test.tsx` — 실제 페이지를 통해 실행하는 표적 회귀 3건. 별도 검토 시 `git diff 53b305b..55ea852 -- frontend/src`와 위 표적 테스트 명령을 사용한다.

## 검증 및 사용자 확인

| 명령 및 환경 | 결과 |
|---|---|
| `frontend` · `npm.cmd run lint`, `npm.cmd run typecheck`, `npm.cmd run build` | 성공 |
| `frontend` · `npm.cmd test` | Vitest 6개 파일 73/73, 기존 70건 유지 |
| `frontend` · `$env:E2E_PORT='5196'; npm.cmd run e2e` | Edge Mock API 10/10, 기존 10건 유지 |
| `frontend/tools/openapi-typescript` · `npm.cmd ci`, `npm.cmd run lint:openapi`, `npm.cmd run check` | 성공. 기존 OpenAPI 경고 2건, 생성 타입 일치 |
| gitleaks 8.30.1 · `git --staged --redact .`, `git --log-opts=HEAD --redact .` | 탐지 없음. 로컬 전 ref 검사에서는 전용 체크포인트 `6e4b3de`의 기존 BudgetMapper SQL 비교문이 별도 fingerprint로 1건 검출됐다. 동일 SQL 줄을 확인했으며 체크포인트는 PR 이력에 속하지 않는다. 예외와 ref는 유지했다. |
| 제품 커밋 GitHub Actions | [45577814594](https://github.com/Hjinsu/ai-studio/actions/runs/45577814594) 7/7 성공 |

사용자 직접 검증은 전체 화면 14/14, 최종 변경 화면 6/6 통과로 보고됐다. 채널 한글화·탭 스크롤, 정책 잔여 한도, 지출 차단·정합성 검사, 입력 가독성·테마, 모바일 메뉴, 로그아웃·재로그인 결과를 승계한다. 이번 보강은 정상 화면 흐름과 표시 문구를 유지하며 세션 전환 시 이전 효과를 정리한다.

## 미검증과 종료 판정

실제 백엔드 연결 브라우저 E2E, 실제 외부 호출·비용·게시, 운영 프록시, 다중 탭·기기 경합, 이미 시작한 lazy route 전환 중의 세대 교체는 이번 표적 검증에서 직접 실행하지 않았다. API·백엔드·DB·migration·의존성 변경은 없다. 개발 DB를 사용하지 않았으며 CI 백엔드 통합은 격리된 MariaDB Testcontainers를 사용한다.

CR-01·CR-02, P-T02-03·P-T02-05와 T03 선행 설계 제약을 유지한다. 실제 비용 발생 및 유료 API 활성화 전에 서버 중심 정산 영속 멱등성과 결과 복구를 해결해야 한다.

자체 검증 기준 RVX-02-CB의 재현 경로가 해소됐다. 변경 콜백 경계의 별도 세션 확인과 사용자 종료 승인을 요청한다. T02는 IMPLEMENTED, PR #1은 Draft로 유지한다.

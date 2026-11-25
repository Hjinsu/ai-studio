# T02-UI-FIX — Codex 프론트엔드 리뷰 결함 및 사용자 확인 항목 수정 보고서

- 담당: Claude Code · 2026-11-25 · 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(Draft PR #1, base main)
- 기준: 수정 전 HEAD `6f4589f`(제품 코드 기준 `8319672`). 리뷰 보고서: [T02_CODEX_FRONTEND_REVIEW](../reviews/T02_CODEX_FRONTEND_REVIEW.md)(Git 보고서 참조 `refs/reviews/t02-ui-rv`의 내용을 그대로 저장, 참조는 삭제하지 않음).
- 승인 범위: UI-RV-01~06 전체, UI-F01·F02·F03·F04·F05·F07·F11·F12·F13, 안전 검토 UI-RV-A01·A02·A03. 보류: UI-F06·F08·F09·F10.
- 변경 범위: `frontend/` 화면·공통 컴포넌트·테스트와 문서. **API·OpenAPI·생성 타입·백엔드·DB·Flyway V1~V5 변경 없음.** 새 의존성 없음(기존 Radix Dialog 재사용).
- 상태: **IMPLEMENTED**(T02 DONE 아님). main 병합·T03 착수 없음.

## 1. 결함별 결과

| ID | 원인 | 수정 | 회귀 테스트 |
|---|---|---|---|
| UI-RV-01 정합성 ERROR인데 허용 표시 | 화면이 관리자 플래그(`spendingEnabled`)만으로 허용/차단을 표시 | `lib/budget.ts` `spendingView()`: 관리자 설정과 서버의 신규 예약 차단(관리자 차단 **또는** 정합성 ERROR)을 분리. 지출 제어 제목은 차단이면 "신규 지출 차단됨", 아니면 "관리자 설정: 신규 지출 허용"(모든 예약 조건 충족으로 단정하지 않음). 상태 줄 "관리자 설정 허용/차단 · 정합성 …", 차단 시 "이미 잡힌 예약의 정산과 조회는 계속할 수 있습니다", 미검사 시 안내. 대시보드 "신규 지출" 카드도 같은 규칙(ERROR면 "정합성 오류로 차단(관리자 설정은 허용)"). 관리자 설정 자동 변경 없음, Fail Closed 동작 변경 없음 | 5개 조합(차단+OK, 차단+ERROR, 허용+OK, 허용+ERROR, 허용+UNKNOWN) 화면 테스트, 각 조합에서 POST 0건(설정 자동 변경 없음), `spendingView` 단위 5건, 대시보드 허용+ERROR 차단 표시. 새로고침은 서버 응답만으로 계산하므로 같은 결과(로컬 검사 결과 상태에 의존하지 않음) |
| UI-RV-02 변경 요청 401이 재인증으로 이어지지 않음 | 전역 401 처리가 QueryCache(조회)에만 있었음 | `queryClient.ts`: 조회·변경 요청 모두 401이면 한 번만 세션 만료 처리 — 만료 안내 표시, 메모리 CSRF 폐기, 보호 조회 취소·제거, 변경 요청 캐시 비움, 로그인 상태 쿼리 초기화(보호 화면 즉시 내림) 후 재확인. 이미 로그아웃 상태(로그인 상태 조회가 401)이면 늦게 온 401로 반복하지 않음. 로그인·로그아웃 요청은 `AUTH_FLOW_META`로 제외(자격 증명 401은 폼 오류 유지). 변경 요청 자동 재시도 없음(기존 `retry: false` 유지), 403 CSRF 1회 재취득·429 정책 그대로 | 보호 변경 요청 401 → 로그인 화면·만료 안내, 보호 데이터·변경 요청 캐시 비움, 재로그인 시 새 CSRF(`tok-2`) 사용, 실패한 검사 요청 재전송 0건, 로그인 상태 조회 횟수 제한. 로그인 자격 증명 401은 만료 안내 없이 폼 오류. 기존 조회 401 테스트 유지 |
| UI-RV-03 모바일 메뉴 포커스가 배경으로 이동 | 직접 만든 드로어(초기 포커스·포커스 가두기·트리거 복귀 없음) | 기존 Radix Dialog로 교체(`layout/AppShell.tsx`): 헤더 "메뉴 열기"를 `Dialog.Trigger`로 연결 → 초기 포커스, Tab/Shift+Tab 순환, 배경 포인터 차단·보조기술 숨김, Escape 닫기, 닫은 뒤 트리거로 포커스 복귀, 메뉴 선택 시 이동 후 닫힘. 열린 채 데스크톱 폭이 되면 닫음 | E2E(Edge, 390×844, 키보드만): Tab으로 트리거 → Enter, 포커스가 대화상자 안, 배경 차단, Tab 25회·Shift+Tab 25회 모두 대화상자 안, Escape 후 트리거 포커스, 키보드로 "비용 원장" 선택 → 이동·닫힘, 1280 폭 전환 시 닫힘 |
| UI-RV-04 소수초 표기로 최신 환율 판정 역전 | 화면이 ISO 문자열로 다시 정렬·비교 | `ExchangeRatesPage.tsx`: 서버 정렬(발효 시각 내림차순, id 내림차순)을 그대로 쓰고 통화별 "현재 시각 이전 발효 중 첫 행"만 최신으로 판정. 시각 비교는 `Date.parse`(정밀 비교), 문자열 정렬 없음. 72시간 유효 정책·만료 시 이전 환율로 대체하지 않는 규칙 유지 | 정수 초 `…:00Z`와 같은 초 `…:00.5Z` 혼재, 같은 통화 여러 건, 적용 예정, 적용 중, 이전 환율, 최신이 만료(더 오래된 유효 환율로 대체하지 않음) |
| UI-RV-05 원장 타임라인 순서 역전·BIGINT 비교 손실 | `recordedAt` 문자열 정렬 + `Number(id)` 보조 비교 | `ReservationDetailPage.tsx`: 서버 순서(원장 id 오름차순) 그대로 표시, 재정렬·Number 변환 제거 | RESERVE `…:00Z` → PROVISIONAL `…:00.001Z` → CONFIRM(같은 시각) 순서 유지, id `9007199254740992`/`9007199254740993`/`9223372036854775807`(signed BIGINT 최대) 3건 모두 표시 |
| UI-RV-06 입력 경계 대비 부족 | 입력 테두리가 구분선 토큰(`line-strong`) 사용, 1.58:1 / 1.70:1 | 입력 경계 전용 토큰 `--c-input-border` 추가(라이트 `#7d8696`, 다크 `#6b7690`), `Form.tsx` 입력 공통 클래스만 교체. 레이블·포커스·오류 색 유지 | `styles.css`의 토큰을 읽어 WCAG 대비 계산: 라이트 surface 3.67 · bg 3.42 · subtle 3.27, 다크 surface 3.87 · bg 4.16 · subtle 3.56 — 모두 ≥ 3:1 |

## 2. 사용자 확인 항목(UI-F) 결과 — 표시 문구만 변경, enum·API 값 불변

| ID | 변경 | 위치 |
|---|---|---|
| UI-F01 | 공통 탭 목록의 불필요한 세로 스크롤(위·아래 버튼) 제거: `overflow-y-hidden`, 가로 스크롤 유지, 포커스 윤곽선을 안쪽으로 그려 잘리지 않게 함 | `ui/Overlay.tsx` · E2E 1280/390: 세로 스크롤바 폭 0, `overflow-x: auto`, 화살표 키 탭 이동 |
| UI-F02 | revision → "설정 버전"(채널: 새 설정 버전·설정 버전 저장·설정 버전 비교 등), 정책 화면 "새 정책 버전", 이력 "이전 버전과 같은 설정", 정합성 문구 "정책 버전" | ChannelDetailPage, ChannelsPage, PolicyDetailPage, RevisionHistory, labels |
| UI-F03·F04 | 스타일 값 `DOCUMENTARY` → 다큐멘터리, `CINEMATIC` → 시네마틱(저장값 그대로, 모르는 값은 원문) | `lib/labels.ts` `STYLE`, `channels/settings.ts` |
| UI-F05 | provider 옵션 → "AI 서비스 설정" (설명: 이미지·음성을 만드는 AI 서비스에 넘길 설정) | ChannelSettingsFields, settings |
| UI-F07 | 언어 `en` → "영어(en)", `en-US` → "영어(en-US)", 모르는 코드는 코드 그대로 | `languageLabel()`, 채널 목록·상세 |
| UI-F11 | "지금 쓸 수 있는 금액" → "예산 정책 기준 잔여 한도" | UsagePage |
| UI-F12 | 설명을 지시 문구 그대로: "시스템 및 채널 예산 중 가장 낮은 잔여 한도입니다. 실제 지출 가능 여부는 콘텐츠 한도, 지출 제어 상태, 정합성, 채널 상태 및 환율 유효성 등에 따라 결정됩니다." | UsagePage(테스트로 전문 확인) |
| UI-F13 | 예약·정산의 provider → "AI 서비스 등" | ReservationDetailPage, ReservationsPage, SpendingControlPage |

보류(이번 범위 아님): UI-F06, UI-F08, UI-F09, UI-F10.

## 3. 안전 검토 결과

### UI-RV-A01 — 정산 응답 유실(서버 커밋 후 응답 손실)과 새로고침
- **분석**: 백엔드는 `Idempotency-Key`를 원장 `event_key`로 그대로 쓰고 같은 키 재요청은 기존 결과를 돌려준다(경합 시 재조회). 같은 확인 창 안 재시도는 같은 키라 중복이 생기지 않는다. 확정·해제는 상태 기계가 보호한다(SETTLED는 다시 확정할 수 없음, RELEASED는 지연 과금 1회 후 SETTLED). **SETTLED의 조정·환불은 새로고침·화면 이탈 뒤 새 키로 다시 보내면 서로 다른 이벤트로 정상 기록되므로 중복 기록이 가능하다**(서버 입장에서는 별개의 정상 요청이라 구분 불가) — 리뷰 잠재 위험을 확인함.
- **이번 수정(범위 내, API·DB 변경 없음)**: 응답을 받지 못한 오류(네트워크 끊김·5xx)이면 자동 재전송하지 않고 예약을 다시 조회해 이번 요청 키가 원장에 있는지 확인한다.
  - 있음 → 확인 창을 닫고 "응답은 받지 못했지만 원장에 이미 기록된 것을 확인했습니다. 다시 기록하지 마세요." 안내, 다음 작업용 새 키.
  - 없음 → "원장에는 아직 이 요청이 없습니다. 이 확인 창에서 다시 기록하면 같은 요청 키로 보내므로 두 번 기록되지 않습니다."
  - 조회 실패 → 원장 타임라인을 먼저 새로고침해 확인하라고 안내.
  - 확인 창에 항상: "응답을 받지 못한 채 새로고침했거나 화면을 떠났다면, 다시 기록하기 전에 원장 타임라인에서 이미 기록됐는지 먼저 확인하세요(새 화면에서는 새 요청 키가 쓰입니다)."
- **재현 테스트(테스트 대역)**: (1) 조정 POST가 서버에 기록된 뒤 502 → 재조회로 기록 확인, POST 1건, 타임라인에 조정 표시. (2) 네트워크 끊김 → 미기록 안내, 자동 재전송 0건, 같은 창 재시도는 같은 키. (3) 응답 유실 후 화면 재생성(새로고침 모사) → **새 키가 생성됨**(잔여 위험을 테스트로 고정).
- **잔여 위험과 근본 해결 제안(별도 설계 승인 필요)**: 새로고침·다른 탭에서의 수동 재입력 중복은 UI 안내로만 줄였다. 근본 해결 후보: (a) 예약 id+요청 내용 기준으로 키를 브라우저 저장소(sessionStorage)에 보존해 새로고침 후에도 같은 키 사용, (b) 서버가 SETTLED 조정·환불에 요청 내용 지문 기반 중복 경고/확인 단계를 두는 API 보완. DECISIONS.md에 **P-T02-05**로 기록. **실제 비용 발생(유료 API 연동) 전 해결 또는 명시적 수용 필요.**

### UI-RV-A02 — 정확히 100%
- `budgetState`가 (지출+예약)과 한도를 문자열 고정소수점으로 정확히 비교: 같으면 **한도 소진**(`exhausted`), 크거나 잔여가 음수면 **한도 초과**, 80% 이상 소진 임박. 예산 미터·정책 상세 배지에 "한도 소진" 추가(위험 색, 텍스트로 구분).
- 테스트: `10000.000000` 한도에 `6000.5`+`3999.5` → 한도 소진, `10000.000001` → 초과, `9999.999999` → 소진 임박, 80% 경계, 0원 전면 차단.

### UI-RV-A03 — HydrateFallback 콘솔 경고
- 원인: 데이터 라우터의 첫 진입에서 lazy 자식 라우트를 기다리는 동안 보여줄 `HydrateFallback`이 없어 React Router가 경고.
- 수정(작은 설정): 루트 라우트에 `hydrateFallbackElement`(기존 LoadingSkeleton) 지정(`routes.tsx`).
- 전후 확인: fallback을 잠시 제거한 상태에서 E2E가 경고 `No HydrateFallback element provided…`를 수집해 실패 → 복원 후 통과. E2E가 이 경고를 계속 검사한다.

## 4. 수정 전 재현(전후 비교)
수정 전 코드(`6f4589f`)를 임시 worktree(scratch, 검증 후 제거)에 두고 새 테스트만 복사해 실행했다.

| 테스트 | 수정 전 | 수정 후 |
|---|---|---|
| vitest(App·pages) | 51건 중 **16건 실패**(UI-RV-01 조합 5·대시보드 2, UI-RV-02 변경 요청 401 1, UI-RV-04 1, UI-RV-05 1, A01 3, UI-F02·F05·F07·F11·F12 문구 3) | 전부 통과 |
| vitest(`lib/budget.test.ts`) | `spendingView`·`languageLabel`·`exhausted`·입력 경계 토큰 없음 | 통과 |
| E2E UI-RV-03 모바일 키보드 | 실패(포커스가 대화상자 밖) | 통과 |
| E2E UI-F01 탭 | 실패(`overflow-y: auto`) | 통과 |
| E2E 콘솔 HydrateFallback 경고(A03) | 실패(경고 9건) | 통과 |

## 5. 전체 검증(최종 작업 트리, 실제 결과)

| 검증 | 결과 |
|---|---|
| frontend `npm run lint` | 통과(exit 0) |
| frontend `npm run typecheck`(앱 + E2E·도구 설정) | 통과 |
| frontend `npm test`(vitest) | **60/60**(기존 39건 유지 + 신규·강화 21건) |
| frontend `npm run build` | 성공 |
| 브라우저 E2E(Playwright, 설치된 Edge, **Mock API**) | **10/10**(기존 8 + 모바일 키보드, 탭). 3회 연속 10/10. 실제 백엔드 연동 검증이 아님 |
| OpenAPI `lint:openapi` / 생성 타입 `check` | 유효(기존 경고 2건) / 일치 |
| 백엔드 단위·통합·Boot JAR (`--no-build-cache --rerun-tasks`, 격리 임시 MariaDB 11.8.2 `127.0.0.1:3307`) | 단위 **133/133**, 통합 **97/97**, bootJar 성공. 임시 DB 종료 |
| gitleaks | 로컬 미설치 → CI job으로 확인 |
| GitHub Actions 7개 job | 커밋 `646179a`, run 45567226915 **7/7 success** |

브라우저 확인(스크린샷, Mock API): 로그인, 대시보드(라이트), 채널 목록·등록 대화상자(입력 경계), 지출 제어(다크), 예산 사용량, 모바일 390px 드로어·원장, 빈 예산 상태. 사용자 개발 DB(3306)는 사용·변경하지 않았다.

## 6. 변경 파일
- 제품: `frontend/src/queryClient.ts`, `layout/AppShell.tsx`, `routes.tsx`, `styles.css`, `lib/budget.ts`, `lib/labels.ts`, `ui/Form.tsx`, `ui/Meter.tsx`, `ui/Overlay.tsx`, `ui/RevisionHistory.tsx`, `pages/{DashboardPage,SpendingControlPage,ExchangeRatesPage,ReservationDetailPage,ReservationsPage,UsagePage,PolicyDetailPage,ChannelDetailPage,ChannelsPage,LoginPage}.tsx`, `pages/channels/{settings.ts,ChannelSettingsFields.tsx}`.
- 테스트: `frontend/src/App.test.tsx`, `pages/pages.test.tsx`, `lib/budget.test.ts`(신규), `test/mockApi.tsx`(unmount 반환), `e2e/admin.spec.ts`.
- 문서: 이 보고서, `docs/reviews/T02_CODEX_FRONTEND_REVIEW.md`(Codex 보고서 원문 저장), `T02_REPORT.md` §12, `STATE.md`, `TASKS.md`, `DECISIONS.md`(ADR-066, P-T02-05), `docs/design/UI_DESIGN_SYSTEM.md`, `docs/00_INDEX.md`.
- ADR: **ADR-066**(T02-UI-FIX 표시 규칙: 신규 지출 상태 분리, 세션 만료 처리 범위, 서버 순서 보존, 입력 경계 토큰, 한도 소진) — 기존 ADR-063 범위 안의 세부 규칙. **P-T02-05**(정산 멱등 키 보존 근본 해결) 제안.

## 7. 사용자 검증 기록(사용자 보고, 2026-11-25)
사용자가 통합 UI 주요 화면을 직접 확인했다. 기존 관리자 계정, IFNOVA 채널, 예산 정책 6개, 테스트 환율, 지출 차단 상태가 보존됐고, 사용자가 정합성 검사를 실행해 **정상**이었다. 예약·원장은 비어 있는 것이 정상이며 서버 상태 정상. 라이트·다크·반응형·로그아웃 등은 사용자 확인 결과대로 기록한다. 실제 유료 예약·정산은 실행하지 않았다. (UI-F 항목은 이 검증에서 사용자가 발견한 표시 개선 사항이다.)

## 8. 미검증·보류·잔여 위험
- 미검증: 실제 백엔드 대상 브라우저 E2E(현재 Mock API), 이번 수정 화면의 사용자 재확인, 모든 보조기술·브라우저 조합, 로컬 gitleaks(CI 대체).
- 잔여 위험: A01 새로고침 후 수동 재입력 중복(P-T02-05, 실제 비용 발생 전 해결 또는 명시적 수용), 기존 CR-01·CR-02(ADR-065), P-T02-03 보류.
- 보류 항목: UI-F06·F08·F09·F10.
- Radix 모달의 보조기술 숨김은 `aria-live` 알림 영역과 그 조상을 남긴다(알림 낭독 유지 목적의 라이브러리 동작).

## 9. 다음 작업
Codex 표적 교차 리뷰(TASKS `T02-UI-FIX-RV`, 기준 = 이 수정 커밋) → 사용자 재확인 → 최종 종료 승인 여부 결정. main 병합·T02 DONE·T03 착수는 별도 승인.

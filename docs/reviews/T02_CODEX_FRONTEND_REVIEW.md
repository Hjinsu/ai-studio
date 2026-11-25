# T02-UI-RV 프론트엔드 독립 코드 리뷰

- 담당 Codex · 2026-11-25 · 제품 기준 `83196729b39914deb7d48c0efaaf95ef1a2cbc62`.
- 범위 `fa32535..8319672`의 `frontend/`. 필요한 API 계약, 백엔드 응답·차단 경로, CI 및 인수인계를 함께 확인했다.
- 통합 검증 커밋 `ab68286`, 문서 및 당시 Draft PR #1 HEAD `6f4589f`. `8319672..6f4589f`는 문서 변경만 포함한다. `6f4589f`의 GitHub 체크 7/7 성공 확인. [Draft PR #1](https://github.com/Hjinsu/ai-studio/pull/1) · [통합 CI](https://github.com/Hjinsu/ai-studio/actions/runs/45557933535)
- **종료 승인 보류 권고.** CRITICAL·HIGH는 발견하지 않았다. 운영 화면에서 새 비용 예약이 차단된 상황을 허용 상태로 표시하고, 세션 만료 후 변경 요청에 대한 복구와 모바일 메뉴의 키보드 조작도 확인된 문제가 있다. 설계 담당자의 수정 범위 및 잔여 항목 수용 여부 판정이 필요하다.

## 확인된 결함

### UI-RV-01 · MEDIUM · 정합성 ERROR 동안 지출 허용으로 표시

ADR-054·061 · `frontend/src/pages/SpendingControlPage.tsx:58`, `frontend/src/pages/DashboardPage.tsx:43`, `backend/src/main/java/com/aistudio/budget/BudgetReservationService.java:151`.

Edge 대역에서 제어 응답을 `spendingEnabled=true`, `consistency.status=ERROR`로 반환하고 지출 제어 페이지를 새로 열었다. 제목은 **신규 지출 허용 중**이었다. 서버는 이 조합에서 신규 비용 예약을 거절한다. 현재 탭의 검사 결과 상세는 별도 로컬 상태라 새로 열면 표시되지 않고, 정합성 체크리스트의 오류와 대표 상태가 모순된다. 대시보드도 플래그만으로 허용을 표시한다(코드 확인). 관리자 토글과 실제 차단 상태를 분리해 두 화면에서 ERROR 차단을 우선 표시하고 기존 정산은 가능하다고 안내한다. 이 결함은 CR-02의 복구 절차와 별개다.

### UI-RV-02 · MEDIUM · 변경 요청에서 받은 세션 만료 401이 재인증으로 이어지지 않음

ADR-037·060 · `frontend/src/queryClient.ts:13`, `frontend/src/App.tsx:12`, `frontend/src/pages/SpendingControlPage.tsx:37`.

Edge 대역에서 로그인된 화면을 연 뒤 지출 제어 변경 요청이 401을 반환하도록 했다. 그 시점부터 `/auth/me`도 401로 응답할 상태였으나 추가 조회는 0건이었고, 오류 모달이 남아 로그인 화면으로 전환하지 않았다. 현재 전역 401 처리는 QueryCache 조회 오류에서만 동작한다. 기준선의 동작도 같으며 최종 UI의 세션 만료 흐름에 남아 있다. 보호된 변경 요청에도 세션 전환을 적용하고 로그인 자격 증명 오류는 현재의 폼 오류로 남긴다. 세션 전환 시 CSRF 토큰과 보호 데이터 정리까지 회귀 검증한다.

### UI-RV-03 · MEDIUM · 모바일 메뉴의 키보드 포커스가 배경으로 이동

ADR-063 · `frontend/src/layout/AppShell.tsx:64`.

Edge 390×844에서 키보드로 메뉴 버튼을 열었다. 열린 `aria-modal` 메뉴 밖의 **메뉴 열기**에 포커스가 남았고 첫 Tab은 배경의 **서버 상태** 링크로 이동했다. 메뉴 자체에만 Escape 핸들러가 있으므로 이 상태의 키보드 사용자는 닫기 동작에도 접근하기 어렵다. 모달 열기 시 초기 포커스와 순환, Escape, 닫기 이후 트리거 복귀를 제공하는 기존 대화상자 기반 구현을 재사용한다. [W3C 모달 대화상자 패턴](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)

### UI-RV-04 · MEDIUM · 서로 다른 소수초 표기로 최신 환율 판정이 뒤집힘

ADR-027·046 · `frontend/src/pages/ExchangeRatesPage.tsx:34`, `backend/src/main/java/com/aistudio/budget/BudgetMapper.java:105`.

Edge 대역에서 동일 USD에 대해 먼저 발효한 `…:00Z`의 1,400원과 1ms 뒤 `…:00.001Z`의 1,500원을 서버의 최신순으로 반환했다. 두 건이 모두 유효한 상태에서 화면은 1,400원을 **적용 중**, 1,500원을 **이전 환율**로 표시했다. 문자열 정렬에서 초와 소수초를 동일 정밀도로 비교하지 않기 때문이다. 같은 응답에서 서버가 보장하는 발효 내림차순을 보존하거나 정밀한 시각 비교를 적용한다. P-T02-03의 현재 환율 일괄 조회 제안과 별개로 같은 페이지에서 재현한 표시 오류다.

### UI-RV-05 · MEDIUM · 원장 타임라인 이벤트 순서 역전과 BIGINT 비교 손실

ADR-019·032 · `frontend/src/pages/ReservationDetailPage.tsx:71`, `backend/src/main/java/com/aistudio/budget/BudgetMapper.java:403`.

Edge 대역에서 원장 응답에 ID 순으로 RESERVE `…:00Z`, PROVISIONAL `…:00.001Z`를 넣었다. 타임라인 첫 행은 **잠정 비용**이었다. 타임스탬프 문자열로 재정렬해 실제 기록 순서가 뒤집힌다. 동일 시각의 보조 비교도 Node에서 `Number('9007199254740992') - Number('9007199254740993') === 0`으로 확인했다. 전체 API 식별자의 전달은 문자열이지만 여기서는 비교 정확도를 잃는다. 서버가 제공하는 이벤트 순서를 유지하거나 정확한 UTC 시각과 BigInt로 정렬한다.

### UI-RV-06 · LOW · 빈 입력 컨트롤의 경계 대비 부족

ADR-063 · `frontend/src/ui/Form.tsx:15`, `frontend/src/styles.css:17`, `frontend/src/styles.css:43`.

Edge 로그인 화면에서 아이디 입력 내부와 폼 배경이 같은 색임을 확인했다. 빈 칸의 경계를 나타내는 테두리 대비를 계산하면 라이트 1.58:1, 다크 1.70:1이다. 필드 식별에 쓰이는 경계에 필요한 3:1보다 낮다. 공통 레이블과 포커스 스타일을 유지하면서 입력 경계 전용 토큰을 조정하고 양 테마를 검증한다. 주요 muted 텍스트/표면의 표본은 라이트 5.97:1, 다크 6.77:1이었다. [W3C 비텍스트 대비 기준](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)

## 범위별 검토와 잔여 사항

| 범위 | 코드·테스트 근거 |
|---|---|
| 인증·요청 | 동일 출처 세션 쿠키, 메모리 CSRF, 성공 로그인과 로그아웃의 토큰 초기화, 403 재취득 1회, 429의 정수 Retry-After 및 1초/장기 제한 안내를 확인했다. 테마·메뉴 상태만 로컬 저장소에 저장하며 비밀번호·CSRF 저장이나 제품 로그 출력은 검토 경로에서 발견하지 않았다. 로그인 및 확인 버튼의 진행 중 상태, React Query 변경 요청 자동 재시도 비활성화를 확인했다. |
| 비용·안전 | 금액은 문자열, 연산은 최대 소수 12자리 고정소수점 BigInt이며 Number는 표시 비율과 UI-RV-05 등의 별도 맥락이다. 시스템과 채널 bucket은 분리해 표현하며 DAY/MONTH 합산도 확인되지 않았다. 실제 지출·예약·잔여, 0원 차단, 위험 작업 확인과 공백 사유 방지, 새 정합성 코드의 한국어 제목·상세를 확인했다. 정산 확정·해제 및 조정·환불은 토큰과 멱등 키를 보내며 KRW 소수 6자리, 원본 소수 12자리, 각각 정수부 12자리 검증이 있다. |
| 디자인 및 화면 | 공통 FormField·DataTable·Dialog·상태 컴포넌트, 라이트·다크 테마, 접근 가능한 레이블, 로딩·빈 상태·오류 처리 및 보관 채널 선택 제외 경로를 확인했다. 모바일 390px E2E와 768/1280px 표적 점검을 했다. 테이블은 자체 스크롤 영역이고 예산 미터 금액은 줄바꿈을 지원한다. 모든 필드·기기·보조기술 조합의 전수 검증은 남아 있다. |
| API와 테스트 | 생성 타입과 원본 OpenAPI 일치 통과. 현재 응답에는 신규 enum과 한국어 표시가 있고, 단위 테스트에서 이를 확인한다. Playwright 대역의 CSRF 헤더 거부, 화면 렌더링, 모달 사유 입력·취소, 보관 채널 제외를 확인했다. 지출 E2E는 취소 시 POST 0건을 확인하고, 성공 요청 본문은 컴포넌트 테스트가 확인한다. E2E 대역은 서버의 인증·정산·페이지 동작 전체를 검증할 수 없다. |
| 의존성·CI | Node 24.21.0과 고정 lockfile로 설치·빌드했다. Tailwind·Radix·clsx·tailwind-merge는 MIT, Lucide ISC, Pretendard OFL-1.1, Playwright Apache-2.0을 설치된 메타데이터로 확인했다. 도입 목적은 스타일·접근성 컴포넌트·아이콘·폰트·브라우저 검증이다. npm audit에 알려진 취약점 0건. Windows 2025 Edge E2E는 격리 포트에서 API 대역만 사용하며 통합 CI에도 독립 job으로 포함된다. |

- **잠재 위험** · 정산 멱등 키는 마운트된 폼 상태에 있다. 서버 커밋 뒤 응답 손실과 페이지 새로고침이 겹치면 특히 SETTLED 조정에서 새 키가 생길 수 있다. 단위 테스트는 같은 폼 내 재시도를 검증한다. 서버 접근이 필요한 이 경합은 실행하지 않았고, 현재 화면에 확정 결과를 확인하는 절차와 재시도 원칙을 추가하는 방안을 검토할 수 있다.
- **개선 후보** · `frontend/src/lib/budget.ts:10`에서 정확히 100%이고 잔여 0인 상태를 ‘한도 초과’와 다른 문구로 표현하면 충족 경계가 명확해진다. UI-RV-02·03·04·05의 표적 대역 사례를 후속 회귀 테스트에 추가한다.
- 기존 CR-01, CR-02와 P-T02-03은 승인된 조건 및 보류 사항으로 유지한다. 추가 결함으로 집계하지 않았다.

## 직접 실행 및 미검증

- `frontend`에서 `npm.cmd ci`, `npm.cmd run lint`, `typecheck`, `test`, `build` 성공. Vitest 5파일 39건 통과.
- `E2E_PORT=5196` 설정으로 `npm.cmd run e2e` 실행. 설치된 Edge와 브라우저 API 대역으로 8건 통과. 잘못 사용한 초기 스크립트 이름 `test:e2e`는 프로젝트에 등록되어 있지 않아 정식 이름 `e2e`로 실행했다. 일부 경로 분할에서 `HydrateFallback` 콘솔 경고가 관찰됐다.
- `frontend/tools/openapi-typescript`에서 `npm.cmd ci`, `npm.cmd run lint:openapi`, `npm.cmd run check` 성공. 기존 Redocly 경고 두 건은 CSRF의 4xx 정의와 PageMeta 직접 미참조다.
- 별도 포트 5198에서 Edge와 네트워크 대역으로 위 재현을 수행했다. 768/1280px에서 지출 제어, 예약 상세, 환율 화면의 본문 가로 넘침 0px를 확인했다. 모든 브라우저·스크린리더·사용자 실제 화면의 검증은 미실행이다.
- 실제 백엔드 대상 브라우저 연동, 운영 프록시·부하 및 사용자 DB 검증은 실행하지 않았다. 개발 DB 3306은 사용하지 않았다. 제품 소스와 다른 작업 트리는 변경하지 않았다.

## 인수인계

보고서는 detached 리뷰 worktree의 `docs/reviews/T02_CODEX_FRONTEND_REVIEW.md`와 로컬 Git 보고서 참조 `refs/reviews/t02-ui-rv`에서 읽을 수 있다. 통합 담당자는 통합 저장소에서 `git show refs/reviews/t02-ui-rv`로 보고서를 조회할 수 있다. 제품 비교 기준은 `fa32535..8319672`다. T02는 IMPLEMENTED이며 종료 판정은 설계 담당자에게 이관한다.

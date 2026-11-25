# AI Studio UI 디자인 시스템
작성 2026-11-25 (T02 UI·UX 개선) · 근거: ADR-035, ADR-063 · 대상: T03~T10 프론트엔드 개발자

관리자 콘솔의 공통 규칙이다. 새 화면은 아래 토큰과 컴포넌트를 조합해 만들고, 임의 색·임의 간격·새 UI 라이브러리를 추가하지 않는다. 새 공통 컴포넌트가 필요하면 `frontend/src/ui/`에 만들고 이 문서에 추가한다.

## 1. 원칙
- **돈과 상태가 먼저 보인다.** 이 콘솔의 핵심은 비용이 안전하게 쓰이는지 확인하고 안전하게 조작하는 것이다. 장식보다 정보 위계를 우선한다.
- **단 하나의 강조 요소: 예산 미터.** 실제 지출(채움) | 예약(빗금) | 잔여(빈칸)를 한 막대에 보여주는 `BudgetMeter`를 예산이 나오는 모든 화면에서 같은 방식으로 쓴다. 다른 요소는 조용하게 유지한다.
- **그림자 없이 테두리.** 패널은 1px 테두리(`border-line`)로 구분하고, 그림자는 떠 있는 레이어(대화상자·토스트·툴팁)만 쓴다(`shadow-overlay`). 그라데이션·장식 애니메이션 금지.
- **색만으로 의미를 전달하지 않는다.** 상태는 텍스트 배지, 증감은 부호(+/−)와 화살표 아이콘, 예약은 빗금 무늬를 함께 쓴다.
- **문구는 사용자 관점의 한국어.** 버튼은 결과 동사("정책 만들기", "원장에 기록"), 오류는 무엇이 잘못됐고 어떻게 고치는지. API enum은 화면에 그대로 노출하지 않고 `lib/labels.ts`로 바꾼다(필요하면 원 코드를 보조로 표시).

## 2. 토큰 (`frontend/src/styles.css`)
라이트는 `:root`, 다크는 `.dark`의 CSS 변수이며 Tailwind `@theme inline`으로 연결된다. 컴포넌트는 **토큰 클래스만** 쓴다.

| 역할 | 클래스 | 라이트 | 다크 | 용도 |
|---|---|---|---|---|
| Background | `bg-bg` | #F6F7F9 | #0E1116 | 페이지 바탕 |
| Surface | `bg-surface` | #FFFFFF | #151922 | 패널·표·입력 |
| Subtle | `bg-subtle` | #F0F2F5 | #1B2030 | 표 머리, 진행 막대 바탕, hover |
| Border | `border-line` / `border-line-strong` | #E1E4EA / #C9CED8 | #262C3A / #364056 | 구분선 / 입력 테두리 |
| Text | `text-text` / `text-muted` | #171B24 / #5B6474 | #E7EAF0 / #98A1B3 | 본문 / 보조 |
| Primary | `bg-primary` `text-primary` `bg-primary-soft` | #3451D1 | #7B92F5 | 주 작업 1개, 선택 상태, 실제 지출 |
| Secondary | `bg-secondary` | #E9ECF2 | #222838 | 보조 표면 |
| Success | `text-success` `bg-success-soft` | #157A47 | #4CC38A | 정상·완료·감소한 비용 |
| Warning | `text-warning` `bg-warning-soft` | #9A5304 | #E9A23B | 지출 차단·소진 임박·위험 허용(risk) |
| Danger | `text-danger` `bg-danger` `bg-danger-soft` | #C0362C | #F0766B | 오류·초과·파괴 작업·정산 확인 필요 |

- **타이포그래피**: Pretendard Variable(OFL 1.1, npm `pretendard` 1.3.9 동적 서브셋을 Vite가 로컬 번들 — 외부 CDN 없음). 스케일 `text-xs` 12 · `text-sm` 13 · `text-base` 14(본문) · `text-md` 16 · `text-lg` 20 · `text-xl` 24(h1). 숫자·금액·시각은 `.num`(tabular-nums).
- **간격**: Tailwind 4px 스케일. 페이지 여백 `px-4 sm:px-6 lg:px-8 py-6`, 패널 안 `p-5`, 폼 필드 간 `gap-4`, 섹션 간 `gap-5~6`.
- **반경**: `rounded-md`(6px) 입력·버튼, `rounded-lg`(10px) 패널·표, `rounded-full` 배지. 위계에 따라 다르게 쓴다.
- **그림자**: `shadow-overlay`만.
- **브레이크포인트**: Tailwind 기본 `sm` 640 · `md` 768(사이드바 표시) · `lg` 1024 · `xl` 1280. 콘텐츠 최대 너비 1280px(`max-w-[80rem]`). 카드 안 레이아웃은 컨테이너 쿼리(`@container`, `@xl:`)로 카드 폭에 맞춘다.
- **테마**: `lib/theme.ts`(`useTheme`, 키 `ai-studio.theme`, 저장값 없으면 OS 설정). `index.html`의 초기 스크립트가 첫 렌더 전에 적용한다. 다크 전용 색을 따로 쓰지 말고 토큰을 쓰면 자동으로 따라간다.

## 3. 공통 컴포넌트 (`frontend/src/ui/`, `frontend/src/layout/`)
| 컴포넌트 | 파일 | 규칙 |
|---|---|---|
| AppShell · Sidebar · Header | `layout/AppShell.tsx`, `layout/nav.ts` | 메뉴는 `NAV` 그룹에 추가(경로는 `routes.tsx`와 일치). 사이드바 접기·테마·로그아웃, 모바일은 드로어. 헤더는 경로·서버 상태·계정 |
| PageHeader | `PageHeader.tsx` | 화면마다 하나(h1 + 한 줄 설명 + 주 작업) |
| Button · IconButton · LinkButton | `Button.tsx`, `buttonStyles.ts` | `primary`(화면당 주 작업 1개) · `secondary` · `ghost` · `danger`(파괴·차단) · `risk`(비용 발생 허용, 예: 지출 허용). IconButton은 `label` 필수. 이동은 LinkButton(링크 안에 버튼 금지) |
| Input · Select · Textarea · TimeZoneInput · DateTimePicker · FormField · FormSection | `Form.tsx` | 레이블은 이름만, 도움말·오류는 `aria-describedby`. 서버 검증 오류는 `lib/errors.ts`의 `fieldErrors`로 해당 필드에 표시. 관련 설정은 FormSection으로 묶고 의미를 설명 |
| KrwInput | `MoneyInput.tsx` | 원화 입력. 쉼표 제거, `원` 단위, `₩` 미리보기. 값은 문자열 그대로 |
| Card · StatCard | `Card.tsx` | 패널(테두리), 요약 지표. 금액은 `Money`로 |
| DataTable · Pagination | `DataTable.tsx` | 표 영역만 가로 스크롤(페이지는 넘치지 않음). 숫자 열 오른쪽 정렬. 보조 열은 `hideBelow`. `rowTone`으로 주의 행 강조 |
| StatusBadge · LabelBadge | `StatusBadge.tsx` | 항상 텍스트 포함. 레이블은 `lib/labels.ts` |
| ProgressBar · BudgetMeter | `Meter.tsx` | 예산 표시는 BudgetMeter(실제/예약/잔여 구분, 0원=전면 차단, 80%↑ 소진 임박, 초과). 비율은 표시용이며 예산 판단은 서버 |
| Dialog · ConfirmDialog | `Dialog.tsx` | Radix(포커스 가두기, Esc). 위험 작업은 ConfirmDialog(`tone` danger/risk/primary, 처리 중 닫기·중복 실행 방지, 사유 입력은 children) |
| Tooltip · Tabs | `Overlay.tsx` | 툴팁은 보조 설명만(필수 정보 금지). 탭은 화면 안 영역 전환, 상태는 URL `?tab=` 권장 |
| EmptyState · LoadingSkeleton · ErrorState · QueryView · InlineNotice | `States.tsx` | 모든 조회는 QueryView(로딩→스켈레톤, 실패→ErrorState+다시 시도). 빈 상태는 무엇이 없는지+다음 행동. **가상 데이터 금지** |
| Toast | `Toast.tsx`, `toast-context.ts` | 저장 결과 요약만(`useToast().success/error`). 오류 상세는 폼 안에 |
| Money(CurrencyDisplay) · Rate · Decimal · DateTime(DateTimeDisplay) | `Display.tsx` | 금액 `₩300,000`, 환율 `1 USD = ₩1,500`, 시각 `YYYY-MM-DD HH:mm` + 시간대 |
| ChannelSelect | `ChannelSelect.tsx`, `api/useChannels.ts` | 채널은 이름으로 고르고 값은 channelId. 신규 대상 선택은 `excludeArchived`. ID 직접 입력 금지. 이름 표시는 `useChannelName` |
| RevisionHistory | `RevisionHistory.tsx` | 불변 revision 타임라인 + 직전 대비 변경(이전 → 이후). 편집 기능 없음 |

만들지 않은 것: SearchInput(현재 API에 검색 대상이 없음 — 필요해지면 추가), 차트 라이브러리(시계열 API 없음, ADR-063).

## 4. 데이터 표시 규칙
- **금액·환율은 문자열로만 다룬다**(`lib/decimal.ts`). JavaScript Number 변환 금지. 표시: 천 단위 구분 + 후행 0 제거(`300000.000000` → `₩300,000`, `1400.123457` 유지). 비교·합계·비율은 BigInt 고정소수점 함수(`compareDecimal`, `addDecimal`, `minDecimal`, `percentOf`).
- 입력은 쉼표를 제거한 문자열을 API 패턴(`matchesAmount`)으로 검증해 그대로 보낸다.
- 시각은 UTC 저장값을 채널·bucket 시간대(없으면 브라우저 시간대)로 표시하고 시간대 이름을 함께 둔다. 원래 UTC 값은 `title`.
- ID 대신 이름(채널명 등)을 보여준다. 식별이 필요하면 `#id`를 보조로.
- 비용 증감: 증가 `+₩`, 감소 `−₩` + 화살표 + 스크린리더 "증가/감소".

## 5. 상태·오류·안전
- 모든 데이터 화면: 로딩, 정상, 데이터 없음, 조회 실패, 권한 오류(403), 세션 만료(401 → 로그인 화면 + 만료 안내)를 처리한다(`QueryView`, `ErrorState`, `queryClient.ts`).
- 저장 중 버튼 `loading`(중복 제출 방지). 비용 관련 쓰기는 멱등 키를 한 작업 동안 유지(`newIdempotencyKey`).
- 비용이 발생할 수 있는 작업(지출 허용 등)은 `risk` 버튼 + ConfirmDialog + 사유 입력. 원장은 수정·삭제 UI를 만들지 않는다.
- 접근성: 키보드로 모든 작업 가능, `:focus-visible` 윤곽선, 모든 입력에 레이블, 아이콘 버튼에 이름, 본문 건너뛰기 링크, `prefers-reduced-motion` 존중.

## 6. 새 화면 추가 절차 (T03~T10)
1. `routes.tsx`에 `lazy` 라우트 추가, `layout/nav.ts`의 그룹에 메뉴 추가(새 영역이면 새 그룹).
2. 화면은 `PageHeader` → 필터 → `QueryView` → `DataTable`/`Card` 순으로 구성.
3. API 타입은 생성 타입(`src/api/generated`, 직접 수정 금지)만 쓰고 호출은 `src/api/admin.ts` 패턴.
4. 새 enum 표시는 `lib/labels.ts`에 추가.
5. 테스트: 컴포넌트 테스트(vitest + Testing Library)와 필요 시 `frontend/e2e`(Playwright, API 대역) — 라이트/다크·모바일 가로 넘침 확인 포함.

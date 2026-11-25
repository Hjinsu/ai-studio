# T02 최종 사용자 검증 절차 (통합 UI + 백엔드 수정)

대상: 사용자 개발 PC의 기존 `ai_studio` 폴더와 개발 DB(관리자 계정, IFNOVA 채널, 예산 정책, 환율 데이터 보존).
기준: PR #1 통합 HEAD(`STATE.md`에 기록). 실행 방법은 [LOCAL_RUN_GUIDE](LOCAL_RUN_GUIDE.md).

## 0. 안전 원칙
- 이번 통합에는 **새 migration이 없다**(V1~V5 그대로). 백엔드를 실행해도 DB 스키마는 바뀌지 않는다(Flyway `up to date`).
- 데이터를 지우거나 초기화하지 않는다. 누락된 예산 연결을 자동으로 고치는 기능은 없다(의도된 설계).
- **지출 차단 상태를 유지한다.** "지출 허용…" 버튼은 누르지 않는다(누르면 취소).
- 실제 유료 API·YouTube 연동은 없다.

## 1. 코드 받기와 실행
```powershell
Set-Location 'C:\Users\PC-94\Desktop\ai-studio'
git status                  # 변경사항이 없는지 확인
git fetch origin
git switch wip/t02-implementation-checkpoint-2026-11-25
git pull --ff-only
.\gradlew.bat :backend:bootRun          # 터미널 1: "Schema `ai_studio` is up to date", "Started AiStudioApplication"
```
```powershell
Set-Location 'C:\Users\PC-94\Desktop\ai-studio\frontend'
npm.cmd ci                               # package-lock.json이 바뀌었으므로 1회 필요(새 UI 의존성)
npm.cmd run dev                          # 터미널 2, Vite Local 주소로 접속
```

## 2. 실행 전·후 읽기 전용 DB 점검 (선택)
MariaDB 클라이언트로 `ai_studio` DB에 접속해 **SELECT만** 실행한다(비밀번호 해시는 조회하지 않는다).
```sql
-- 스키마 버전: 1~5가 모두 success=1이어야 하며 6 이상은 없어야 한다
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- 관리자 계정(해시 제외)
SELECT id, login, enabled, credential_version, last_login_at FROM admin_user;
-- 채널·정책·환율
SELECT id, name, status, timezone FROM channel ORDER BY id;
SELECT id, scope, channel_id, period_type, status, current_revision_no FROM budget_policy ORDER BY id;
SELECT id, base_currency, rate, effective_from, valid_until FROM exchange_rate ORDER BY id DESC LIMIT 10;
-- 지출 차단·정합성 상태(spending_enabled는 0이어야 한다)
SELECT spending_enabled, consistency_status, consistency_issue_count, consistency_checked_at FROM budget_control;
-- 예약·원장 건수(UI로는 예약을 만들 수 없으므로 보통 0)
SELECT COUNT(*) AS reservations FROM budget_reservation;
SELECT COUNT(*) AS ledger_rows FROM cost_log;
```
예약이 1건 이상이면 정합성 검사 전에 아래 **읽기 전용** 미리보기로 링크 오류 후보를 확인할 수 있다(앱의 `RESERVATION_BUCKET_LINK_MISMATCH` 검사와 같은 조건, 아무것도 기록하지 않음).
```sql
WITH linked_buckets AS (
  SELECT rb.reservation_id, p.scope, p.period_type,
         CASE WHEN b.content_id IS NULL AND p.created_at <= r.created_at
                   AND b.period_start <= r.created_at AND r.created_at < b.period_end
                   AND p.period_type IN ('DAY','MONTH')
                   AND (p.scope = 'SYSTEM' OR (p.scope = 'CHANNEL' AND p.channel_id = r.channel_id))
              THEN 1 ELSE 0 END AS valid_link
    FROM budget_reservation_bucket rb
    JOIN budget_reservation r ON r.id = rb.reservation_id
    JOIN budget_bucket b ON b.id = rb.bucket_id
    JOIN budget_policy p ON p.id = b.policy_id
), link_counts AS (
  SELECT r.id,
         COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'SYSTEM' AND l.period_type = 'DAY' THEN 1 END) AS system_day,
         COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'SYSTEM' AND l.period_type = 'MONTH' THEN 1 END) AS system_month,
         COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'CHANNEL' AND l.period_type = 'DAY' THEN 1 END) AS channel_day,
         COUNT(CASE WHEN l.valid_link = 1 AND l.scope = 'CHANNEL' AND l.period_type = 'MONTH' THEN 1 END) AS channel_month,
         COUNT(CASE WHEN l.valid_link = 0 THEN 1 END) AS invalid_links
    FROM budget_reservation r LEFT JOIN linked_buckets l ON l.reservation_id = r.id
   GROUP BY r.id)
SELECT * FROM link_counts
 WHERE system_day <> 1 OR system_month <> 1 OR channel_day > 1 OR channel_month > 1 OR invalid_links <> 0;
```

## 3. 화면 검증 항목
| 화면 | 확인 |
|---|---|
| 로그인 | 브랜드·아이디·비밀번호, 비밀번호 표시 전환, 틀린 비밀번호 시 오류 안내, 로그인 진행 표시 |
| 공통 | 그룹 메뉴(개요·채널 관리·비용·예산·시스템), 현재 메뉴 강조, 사이드바 접기, 다크/라이트 전환(새로고침 후 유지), 헤더의 서버 상태·계정, 창을 좁혔을 때 메뉴 버튼과 표 가로 스크롤 |
| 대시보드 | 지출 차단 표시, 정합성 상태, 정산 확인 필요 건수, 오늘/이번 달 예산(₩ 천 단위), 실제 지출·예약 구분 미터 |
| 채널 관리 | IFNOVA가 "운영 중" 배지로 보임, 기본 콘텐츠 유형·최근 설정 변경, 상세의 개요/채널 설정/설정 이력 탭, revision 비교 |
| 예산 정책 | 범위 배지(시스템 전체/채널별/콘텐츠 유형), 한글 기간, 정책 만들기 대화상자에서 채널을 이름으로 선택(보관 채널 제외), 한도 ₩ 미리보기, 0원 경고. 기존 정책 데이터는 바꾸지 않아도 된다 |
| 예산 사용량 | 시스템/채널 탭, 채널 선택 드롭다운, 미터와 "참고" 잔여 안내 |
| 지출 제어 | "신규 지출 차단됨" 강조, 신규 지출 조건 체크리스트, 정합성 상태 한글 표시. **"지출 허용…"은 대화상자만 열어 보고 취소** |
| 예약·정산 / 비용 원장 | 데이터가 없으면 빈 상태 안내. 있으면 한글 상태 배지, 원장 증감 부호 |
| 환율 관리 | `1 USD = ₩…` 형식, 적용 중/만료/이전 상태, 등록 대화상자의 발효 시각·72시간 안내 |
| 서버 상태 | 정상/확인 중/연결 실패 |
| 로그아웃·재로그인 | 로그아웃 후 로그인 화면, 재로그인 |

## 4. 정합성 검사를 실행할 때 주의
- 지출 제어 화면의 **"지금 검사"는 `budget_control`의 정합성 상태·시각·건수와 감사 로그를 갱신한다**(원장·예약·bucket은 수정하지 않는다).
- 결과가 오류이면 신규 비용 예약이 차단된다(지금도 지출 차단 상태이므로 실제 동작 변화는 없다). 원인 조사 전에는 아무것도 고치지 말고 결과 표(오류 종류·대상·기대값·실제값)를 공유한다. 복구 절차는 실제 비용 발생 전에 별도로 정한다(P-T02-04 CR-02).

## 5. 결과 보고 양식
화면별 정상/이상, 이상 시 화면 캡처와 오류 안내의 추적 ID, 2절 SELECT 결과(비밀정보 제외), 정합성 검사 실행 여부와 결과.

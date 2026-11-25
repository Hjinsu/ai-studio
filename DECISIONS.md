# 결정 기록
| ID | 상태 | 결정 |
|---|---|---|
| ADR-001 | 기준선 | 모듈러 모놀리스, DB 큐, Quartz |
| ADR-002 | 기준선 | 승인 revision/hash 일치 시에만 게시 |
| ADR-003 | 기준선 | 이미지+TTS 우선, 비디오 provider 후순위 |
| ADR-004 | 제안 | 영어 과학 채널, 월 30만원 예산 |
| ADR-005 | 기준선 | Claude/Codex 공통 계약, 작업 상태 Git 관리 |
| ADR-006 | 승인(2026-11-25) | Flyway, Java21, Gradle Wrapper, Docker Compose 포함(로컬 Docker 필수 아님), 버전 T01 고정. Modular Monolith 유지, Redis/Kafka/Kubernetes 미도입 |
| ADR-007 | 승인(2026-11-25) | Java 루트 패키지 `com.aistudio`, 메인 클래스 `AiStudioApplication`, 기능 모듈 중심 구조. 빈 패키지 생성 금지 |
| ADR-008 | 승인(2026-11-25) | `/actuator/health`만 인증 없이 노출, show-details/show-components=never. 다른 Actuator 엔드포인트 외부 노출 금지. Actuator 표준 응답(health 본문, health 하위 경로 빈 본문 404)은 공통 오류 형식과 별도 계약(ADR-024) |
| ADR-009 | 승인(2026-11-25) | Flyway `V1__initial_baseline.sql`(도메인 테이블 없음). baselineOnMigrate 비활성. 적용된 migration 수정 금지. ADR-014에 따라 V2는 collation 수정, T02 도메인 테이블은 V3 이후 |
| ADR-010 | 승인(2026-11-25) | 로컬은 기존 설치 MariaDB 11.8 사용, AI Studio 전용 DB(`ai_studio`)만 사용·기존 DB 변경 금지. profile local/test/docker/prod. Docker Desktop/WSL2 설치 강제 금지. Compose/CI는 MariaDB 11.8 검증 패치 버전 고정. 비밀번호/API Key는 환경변수 |
| ADR-011 | 승인(2026-11-25) | GitHub Actions. Docker 필요 테스트는 Linux job. 단위/통합 테스트 분리, 통합 미실행 시 전체 통과로 기록 금지. Java/React 빌드, 타입 검사, ESLint, 테스트 포함. 응답 `{code,message,traceId,timestamp,details}` (docs/04) |
| ADR-012 | 승인(2026-11-25) | 오류 코드: 기존 11개 유지 + POLICY_VIOLATION(422), BUDGET_EXCEEDED(422), INVALID_STATE_TRANSITION(409), PREREQUISITE_NOT_MET(409), DUPLICATE_RESOURCE(409), NOT_ACCEPTABLE(406), PAYLOAD_TOO_LARGE(413), CLIENT_ERROR. 미분류 HTTP 오류는 실제 상태 유지(미분류 4xx→CLIENT_ERROR, 미분류 5xx→INTERNAL_SERVER_ERROR). details는 항상 배열. 내부 예외 메시지 비노출 |
| ADR-013 | 승인(2026-11-25) | 프로젝트 전용 환경변수는 `AI_STUDIO_` 접두사, 이후 추가 변수도 동일. 배경: 로컬 PC의 다른 프로젝트(stock_analysis) OS 변수 DB_URL/DB_PASSWORD가 .env보다 우선 적용될 위험. 기존 DB_* 변수 변경·삭제 금지, DB_* fallback 금지. 필수 변수 누락 시 기동 중단(변수 이름만 출력). 영향: .env.example, application-*.yml, Compose, CI, README, docs/02·10·13 |
| ADR-014 | 승인(2026-11-25) | DB collation `utf8mb4_unicode_ci` 확정. V1 수정 금지, `V2__fix_database_collation.sql` 추가. T02 도메인 테이블은 V3 이후. 이후 DDL은 CHARSET/COLLATE를 모두 생략하거나 `COLLATE=utf8mb4_unicode_ci`를 명시(MariaDB 11.2+는 CHARSET만 지정하면 uca1400 적용 — 2026-11-25 로컬 11.8.2에서 확인). 로컬 MariaDB 11.8.2, CI/Compose 11.8.8 유지. Flyway 12.4.0의 "MariaDB 11.8 미검증" 경고는 조건부 허용하며 숨기지 않음(Flyway 업그레이드 시 재확인) |
| ADR-015 | 승인(2026-11-25) | (I-01) 핵심 조회·검증 대상은 정규화, 원본 AI 응답과 비핵심 설정만 JSON. content_claim, claim_evidence, claim_verification 설계. 구현 T03~T04 |
| ADR-016 | 승인(2026-11-25) | (I-02) media_asset(파일)과 media_asset_usage(revision/scene 참조) 분리. 파일 SHA-256 기반 중복 저장 방지, 참조 중 파일 삭제 금지, 라이선스 관리 및 재사용 정책. 구현 T03~T05 |
| ADR-017 | 승인(2026-11-25) | (I-03) DB 시간 UTC. 채널 예산은 채널별 예산 시간대, 시스템 예산은 기본 Asia/Seoul. 기간 경계(UTC 시작/종료)를 bucket에 저장, 시간대 변경은 다음 기간부터. budget_policy, budget_bucket, budget_reservation, cost_log, exchange_rate. 시스템/채널/콘텐츠 예산 모두 검사, 비용 예약과 예산 검사는 원자적. 원본 통화·금액, 환율, KRW 환산 금액 기록, DECIMAL. 구현 T02 |
| ADR-018 | 승인(2026-11-25) | (I-04) publish_target, publish_job, publish_attempt 분리. 대상별 게시 중복 방지, 시도 이력 보존. 불확실한 업로드 결과 확인 전 재업로드 금지. 구현 T08 |
| ADR-019 | 승인(2026-11-25) | (I-05) 비용 원장 append-only. 유형 RESERVE, RELEASE, PROVISIONAL, CONFIRM, ADJUSTMENT, REFUND. CONFIRM은 잠정-확정 차액 기록. 예약액은 실제 비용에 중복 합산 금지. 기존 unique(request_key, entry_type) 제거, 이벤트별 고유 event_key. 다회 정산 조정, 환불, 중복 이벤트, 예상액 초과 정산 지원. 구현 T02 |
| ADR-020 | 승인(2026-11-25) | (I-06) content.current_revision_id nullable. 콘텐츠 생성·revision 생성·현재 revision 갱신은 한 트랜잭션. 현재 revision이 해당 콘텐츠에 속하도록 DB 제약. 승인된 revision 수정 금지. 구현 T03 |
| ADR-021 | 승인(2026-11-25) | (I-07) OpenAPI 3.1 계약 `docs/api/openapi.yaml` 관리, 프론트 타입 생성(openapi-typescript 우선 후보). Spring 측 라이브러리는 고정 버전 호환성 확인 후 결정. T02 API: 로그인, 로그아웃, CSRF, 사용자 조회, 채널 CRUD, 채널 설정, 예산 정책 CRUD, 예산 사용량, 비용 원장 조회 |
| ADR-022 | 승인(2026-11-25) | (I-08) 콘텐츠·작업·게시 상태 분리, 전체 허용 상태 전이표와 실패 처리 규칙 설계. Quartz는 일정 실행만, 제작 상태는 자체 DB 큐. Quartz JDBC JobStore 스키마는 Flyway로 관리, 운영 자동 스키마 초기화 금지. 구현 T03 |
| ADR-023 | 승인(2026-11-25) | (I-09) Spring Security 세션 기반 관리자 인증. BCrypt, CSRF 활성, HttpOnly, SameSite=Lax, 유휴 만료 30분. local HTTP Secure=false, 운영 HTTPS Secure=true. 개발은 Vite proxy, 운영은 Spring Boot가 React 정적 파일 제공(동일 출처). 로컬 서버 loopback 바인딩(T01 적용). 초기 관리자 비밀번호 하드코딩 금지. 구현 T02 |
| ADR-024 | 승인(2026-11-25) | 전용 DB 검증을 local/docker/prod 모두 적용(URL 사전 검사 + Flyway 직전 실제 연결 DB 검사, 불허 DB면 즉시 중단). 테스트는 격리 전용 DB(`ai_studio_test` 등 Testcontainers)만 허용. Security 등 필터 단계 오류도 공통 오류 형식(`/error` 컨트롤러, T01 적용). Actuator 표준 응답은 별도 계약 |
| ADR-025 | 승인(2026-11-25) | DB 계정: 로컬 전용 DB는 ALL PRIVILEGES 허용. 운영은 migration 계정(`AI_STUDIO_DB_MIGRATION_USER/PASSWORD`, Flyway 전용)과 앱 계정 분리. 변수 이름은 T01 구현 시 ADR-013 규칙으로 정함 |
| ADR-026 | 승인(2026-11-25, D-01) | 비용 데이터: budget_policy, budget_policy_revision, budget_bucket, budget_reservation, budget_reservation_bucket, cost_log, exchange_rate. reservation=현재 상태, cost_log=불변 이벤트, bucket=잠금·집계. 정책 변경 이력 revision 보존. 예약 검사·생성·원장·bucket 갱신 단일 트랜잭션, 일관 잠금 순서, bucket 동시 생성·중복 요청 경쟁 처리, 외부 호출은 트랜잭션 밖. 같은 key+같은 내용은 멱등, 다른 내용은 409. 원장 UPDATE/DELETE 금지, 운영 앱 계정은 원장 SELECT/INSERT만. 정합성 검사 기능 제공 |
| ADR-027 | 승인(2026-11-25, D-02) | 환율: 관리자 수동 등록(유료 API 금지). 예약 시점 유효 환율 고정, 정산도 동일 환율. 유효기간 기본 72시간(설정 가능), KRW=1. 누락·만료 시 신규 외화 예약 PREREQUISITE_NOT_MET. 실제 결제 환율 차이는 별도 정산 이벤트. 환율 등록·조회 API/UI |
| ADR-028 | 승인(2026-11-25, D-03) | 정산: PROVISIONAL 없는 CONFIRM 허용(기존 반영액 0 기준). PROVISIONAL은 비용 인식과 예약액 이동 원자 처리(계산식은 ADR-056으로 대체). CONFIRM은 최종액-기존 반영액 차액 기록 + 남은 예약 해제. ADJUSTMENT/REFUND는 증감 이력. 예약 상태 ACTIVE, RECONCILIATION_REQUIRED, SETTLED, RELEASED. 과금 불명은 자동 해제 금지. 예상 초과 실비용도 기록, 예산 초과 시 신규 제작 차단. 상태·이벤트 허용 조합과 계산식 문서화 |
| ADR-029 | 승인(2026-11-25, D-04) | 콘텐츠 예산 LIFETIME, revision 변경·재생성 시 초기화 금지. T02는 SYSTEM/CHANNEL 실구현 + 콘텐츠 유형별 기본 한도 설정. 존재하지 않는 content_id 정책·예약 불허. 콘텐츠 FK와 콘텐츠 단위 예약은 T03 |
| ADR-030 | 승인(2026-11-25, D-05) | 정밀도: 원본 금액 DECIMAL(24,12), 환율 DECIMAL(24,10), KRW 금액·집계 DECIMAL(24,6), Java BigDecimal. 예약 CEILING(6), 정산 HALF_UP(6). 저장된 금액 기준 집계(반복 환산 금지). REST 금액·환율은 문자열 |
| ADR-031 | 승인(2026-11-25, D-06) | 초기 관리자: 일회성 CLI, 웹 가입 API·기본 비밀번호 금지. 터미널 숨김 입력, 인자·로그 비노출. 최소 12자, BCrypt cost 12(성능 검증), 72바이트 초과 거부(조용한 잘림 금지). 관리자 존재 시 거부, 동시 생성은 DB 제약+트랜잭션. 비밀번호 재설정 CLI |
| ADR-032 | 승인(2026-11-25, D-07) | OpenAPI 3.1 계약 우선, `docs/api/openapi.yaml` 기준, Spring 호환 계약 검증 라이브러리 조사·고정 후 실제 요청/응답 검증. 금액·환율·BIGINT ID 문자열. 목록 기본 20, 최대 100. 오류 19개·공통 규격 유지. 환율·정책 이력·채널 설정 이력 API 포함 |
| ADR-033 | 승인(2026-11-25, D-08) | openapi-typescript로 타입 생성, 생성물 Git 커밋·직접 수정 금지, CI에서 재생성 diff 검사 |
| ADR-034 | 승인(2026-11-25, D-09) | 채널 물리 삭제 금지, 상태 ACTIVE/PAUSED/ARCHIVED, 보관 채널 비용·성과 보존. channel_config는 불변 버전 이력. 채널 optimistic lock 버전과 설정 revision 번호 구분. 핵심 설정 정규화, Provider 옵션만 JSON |
| ADR-035 | 승인(2026-11-25, D-10) | T02 관리자 UI: 로그인/로그아웃, 기본 대시보드, 채널 목록·등록·수정·보관, 채널 설정·이력, 예산 정책, 예산 사용량, 비용 원장, 환율 등록·조회. 제작·장면·검수 화면은 후속 |
| ADR-036 | 승인(2026-11-25, D-11) | 로그인 제한: login+IP 5회/15분, login 10회/15분, IP 30회/15분, 도달 시 15분 차단·429. 단일 인스턴스 메모리(만료·최대 메모리 관리), 재기동 시 초기화는 알려진 한계. 신뢰할 수 없는 전달 헤더를 IP 판정에 쓰지 않음 |
| ADR-037 | 승인(2026-11-25, D-12) | ADMIN 단일 역할. 세션 인증, CSRF, HttpOnly, SameSite=Lax, 유휴 30분. 로그인 시 세션 ID 교체, 로그아웃 시 세션 폐기. 로그인 화면·정적 파일·CSRF 발급·로그인 API·health만 비인증(로그인 API도 CSRF 검증). 로그인/로그아웃 후 CSRF 재취득. 401/403 공통 형식, 계정 존재 여부 비노출 |
| ADR-038 | 승인(2026-11-25, D-13) | 예산 시간대: UTC 저장, 현재 bucket 경계와 기존 예약 연결 불변, 시간대 변경은 다음 기간부터, 전환 시 기간 중복·누락 금지 및 예산 초기화 우회 방지, 정책·시간대 변경 이력 보존 |
| ADR-039 | 승인(2026-11-25, T2-Q1) | 채널 설정에 content_strategy, target_audience, narration_style, visual_style 추가. default_content_type은 기본 선택값. 합성 고지 기본값은 UI 초기값일 뿐 영상별 판단을 대체하지 않음 |
| ADR-040 | 승인(2026-11-25, T2-Q2) | `frontend/tools/openapi-typescript` 격리 도구(TS 5.9.3, openapi-typescript 7.13.0 고정, 자체 lockfile, 상대 경로, npm ci). 프로젝트 TS 6.0.3 유지. --force/--legacy-peer-deps 금지 |
| ADR-041 | 승인(2026-11-25, T2-Q3) | openapi-request-validator-mockmvc 3.0.0(테스트 classpath 전용), Redocly CLI 2.83.0 |
| ADR-042 | 승인(2026-11-25, T2-Q4) | PAUSED/ARCHIVED 신규 예약 차단, 기존 정산 허용. ARCHIVED 종료 상태(복원·수정 금지). 채널 상태 검사는 예약 트랜잭션 안에서 잠금으로 보호 |
| ADR-043 | 승인(2026-11-25, T2-Q5) | 예산 트랜잭션 READ COMMITTED. 데드락·잠금 대기 초과 시 전체 롤백 후 최초 실행 외 최대 3회 재시도(백오프+jitter). 비즈니스 오류 재시도 금지 |
| ADR-044 | 승인(2026-11-25, T2-Q6) | 세션 쿠키 `AI_STUDIO_SESSION`, HttpOnly, SameSite=Lax, Path=/, 유휴 30분, local/docker Secure=false, prod Secure=true |
| ADR-045 | 승인(2026-11-25, T2-Q7) | T02 IP 판정은 remoteAddr만. 신뢰 프록시는 운영 배포 단계. Docker 개발 포트는 127.0.0.1로 제한 |
| ADR-046 | 승인(2026-11-25, T2-Q8) | 환율 effectiveFrom 과거 최대 5분·미래 최대 7일, 기본 유효 72시간. 가장 최근 발효 환율이 만료되면 이전 환율로 fallback 금지 |
| ADR-047 | 승인(2026-11-25, T2-Q9) | T02는 Vite proxy까지. 운영 JAR 정적 파일 패키징은 배포 단계(ADR-023 최종 구조 유지) |
| ADR-048 | 승인(2026-11-25, T2-Q10) | RELEASED→SETTLED(지연 과금) 허용. 근거·사유·event_key·감사 이력 필수, 기존 이벤트 수정 금지 |
| ADR-049 | 승인(2026-11-25, T2-Q11) | 시간대 변경 자투리를 다음 완전한 기간에 합침. 시간대 효력 시각을 revision에 명시 저장해 bucket 생성 여부와 무관하게 유지 |
| ADR-050 | 승인(2026-11-25, T2-Q12) | 운영 binlog ON 트리거 생성 검증은 배포 전 필수. 앱 계정 SUPER 금지 |
| ADR-051 | 승인(2026-11-25, T2-Q13) | 실패 로그인 ID·IP 기록 허용(비밀번호·토큰·세션 ID 금지). 대량 실패 로그 폭증 방지. 기본 90일 보존 정책 설계, 실제 삭제는 별도 관리 정책 |
| ADR-052 | 승인(2026-11-25, T2-Q14) | 대시보드: 채널 수, 시스템 예산(한도·사용·예약·잔여), 정산 확인 필요 건수, 정합성 검사 상태, 지출 허용 여부. SYSTEM·CHANNEL 중복 합산 금지 |
| ADR-053 | 승인(2026-11-25, F-01) | request_key, event_key, fingerprint, password_hash 등 정확 비교 컬럼은 `utf8mb4_bin`. MigrationConventionTest에 컬럼별 의도적 collation 예외 명시, 대소문자 구분·중복 테스트 |
| ADR-054 | 승인(2026-11-25, F-02) | Fail Closed: SYSTEM DAY·MONTH 활성 정책이 없거나 비활성이면 신규 유료 예약 차단. 미등록·비활성·0원을 무제한으로 해석 금지. 정합성 오류 확인 시 신규 예약 차단 |
| ADR-055 | 승인(2026-11-25, F-03) | budget_control 단일 제어 행. 전역 잠금 순서 budget_control → channel → budget_policy → budget_bucket → budget_reservation → 이벤트 INSERT. 필요한 행만, 역순 금지, 외부 호출은 트랜잭션 밖 |
| ADR-056 | 승인(2026-11-25, F-04) | PROVISIONAL: newCost=K(P), newReserved=max(0, E−newCost), costDelta=newCost−oldCost, reserveDelta=newReserved−oldReserved(양수 허용). ADR-028의 PROVISIONAL 식 대체 |
| ADR-057 | 승인(2026-11-25, F-05) | 원본 통화 조정과 KRW 전용 조정 구분. KRW 전용(FX_DIFFERENCE 등)은 recognized_cost_original 불변. 통화별 환불·조정 규칙과 제약 |
| ADR-058 | 승인(2026-11-25, F-06) | admin_bootstrap 운영 최소 권한(SELECT/INSERT), UPDATE/DELETE 금지 |
| ADR-059 | 승인(2026-11-25, F-07) | channel_config revision 생성 시 channel 행 잠금 + baseRevisionNo 검증, 기존 revision 불변 |
| ADR-060 | 승인(2026-11-25, F-08) | JSON 로그인의 세션 인증 전략·SecurityContext 저장·세션 ID 변경·CSRF 무효화 검증. credential_version으로 비밀번호 변경 후 세션 무효화(시각 비교 단독 금지) |
| ADR-061 | 승인(2026-11-25, F-09) | budget_control.spending_enabled 기본 false, 관리자 긴급 지출 차단. 차단 중에도 기존 정산·조회 허용. 실제 provider 호출은 T07 승인 전 금지 |
| ADR-062 | 승인(2026-11-25, P-T02-01) | OpenAPI는 allOf/anyOf 조합 대신 평탄화된 최종 스키마 사용(검증 라이브러리의 unevaluatedProperties 지원 불완전 실측). 공통 필드 일치·미정의 필드 거부를 OpenApiContractTest로 강제. JSON 구조 불변 |
| ADR-063 | 승인(2026-11-25, T02 통합 승인) | 관리자 UI 디자인 시스템과 기술 선택(Tailwind CSS 4, Lucide, Pretendard 로컬 번들, Radix Dialog·Tooltip·Tabs, clsx, tailwind-merge, Playwright E2E). 전문은 아래 "ADR-063 전문" |
| ADR-064 | 승인(2026-11-25, T02 통합 승인) | Codex 백엔드 수정 RV-01~RV-09(`fix/t02-backend` `6a32c64`)와 Claude 교차 리뷰(`docs/reviews/T02_CLAUDE_BACKEND_REVIEW.md`) 승인, PR #1 통합 승인. RV-07 정합성 코드 `RESERVATION_BUCKET_LINK_MISMATCH` 추가 포함. T02 최종 종료·main 병합 승인은 아님 |
| ADR-066 | 승인(2026-11-25, T02-UI-FIX 수정 지시 범위) | 관리자 UI 표시 규칙 보완(Codex UI 리뷰 UI-RV-01~06, 안전 검토 A02·A03): ① 신규 지출 표시는 관리자 설정과 서버 차단(관리자 차단 또는 정합성 ERROR)을 분리하고 허용을 "예약 가능"으로 단정하지 않는다 ② 보호 조회·변경 요청의 401은 한 번만 세션 만료 처리(CSRF·보호 캐시·변경 요청 정리, 자동 재시도 없음), 로그인 자격 증명 401은 폼 오류 ③ 환율·원장 이벤트는 서버 정렬을 보존(문자열 시각 정렬·Number id 비교 금지) ④ 입력 경계 전용 토큰(대비 3:1 이상) ⑤ 정확히 100%는 "한도 소진" ⑥ lazy 라우트 첫 진입 fallback. API·DB 변경 없음. 상세 `docs/reports/T02_UI_FIX_REPORT.md` |
| ADR-065 | 승인(2026-11-25, P-T02-04 조건부 수용) | CR-01(프록시 뒤 로그인 제한)은 **운영 배포 전 해결 조건**, CR-02(정합성 오류 복구 절차 부재)는 **실제 유료 API 연동 전 복구 절차 마련 조건**으로 수용. T03 선행 제약은 `docs/plans/T03_PLAN.md` |

## 미결 설계 이슈 상태
| ID | 결정 | 상태 | 구현 예정 |
|---|---|---|---|
| I-01 | ADR-015 | 결정됨, 테이블 상세 설계 필요 | T03~T04 |
| I-02 | ADR-016 | 결정됨, 테이블 상세 설계 필요 | T03~T05 |
| I-03 | ADR-017 | 결정됨, T02 계획에 상세안(docs/plans/T02_PLAN.md) | T02 |
| I-04 | ADR-018 | 결정됨, 테이블 상세 설계 필요 | T08 |
| I-05 | ADR-019 | 결정됨, T02 계획에 상세안 | T02 |
| I-06 | ADR-020 | 결정됨 | T03 |
| I-07 | ADR-021 | 결정됨, OpenAPI 파일 T02 작성 | T02 |
| I-08 | ADR-022 | 결정됨, 상태 전이표 작성 필요(T03 착수 전) | T03 |
| I-09 | ADR-023 | 결정됨(loopback은 T01 적용) | T02 |

변경 제안 양식: ID / 배경 / 대안 / 영향 문서·DB·API / 마이그레이션 / 승인 여부. 사용자 승인 전 기준선 변경 금지.

## 변경 제안
### P-T02-01 — OpenAPI 조합 스키마와 추가 필드 검증 책임 분리 (2026-11-25 제안 → **승인(2026-11-25, 대안 A 방향)**, 평탄화로 구현)
- 배경: `ChannelConfig = allOf[ChannelConfigSettings, 메타]`인데 `ChannelConfigSettings`가 `additionalProperties: false`를 명시 → 유효한 ChannelConfig가 존재할 수 없음(ChannelIT 실패). 또한 계약 검증 라이브러리(openapi-request-validator 3.0.0)는 additionalProperties가 없는 스키마에 false를 주입하는데 allOf 하위 스키마마다 주입해 `PageMeta + items` 페이지 응답도 거부.
- 승인 조건: unevaluatedProperties 우선 검토·실측, 지원 불완전 시 평탄화 허용, JSON 구조 불변, 백엔드·DB 불필요 변경 금지, 미정의 필드 검증 유지, skip·검증 비활성화 금지, 테스트 헬퍼 재검토.
- 실측 결과(`OpenApiContractTest`): allOf에 `unevaluatedProperties: false`를 두면 조합 최상위 미정의 필드는 거부되지만, 검증기가 조합 하위로 주입을 중단해 **중첩 스키마(페이지 items[]의 Channel 등)의 미정의 필드를 놓친다** → 지원 불완전. 1차 세션의 테스트 헬퍼(`strictAllOf`, 로딩 시 unevaluatedProperties 자동 부착)도 같은 이유로 중첩 위반을 숨기고 있었음 → **제거**.
- 채택: **평탄화**. `allOf` 10곳(*Page 8개, ChannelConfig, ReservationDetail)을 필드를 명시한 단일 object로 변환. `ChannelConfigSettings`는 원래대로 `additionalProperties: false`(요청 전용 사용). 요청·응답 JSON 구조, 백엔드 코드, DB 변경 없음.
- 중복 정의 보호: `OpenApiContractTest.schemasAreFlatAndConsistentWithTheirBases` — allOf/anyOf 금지, *Page ⊇ PageMeta, ChannelConfig ⊇ ChannelConfigSettings, ReservationDetail ⊇ Reservation(속성 정의 동일 + required 포함). `PageMeta`는 기준 스키마로 유지(Redocly no-unused-components 경고 1건 추가, 의도).
- 검증기 동작 테스트: 정상 허용, 최상위·설정·페이지 항목 내부 미정의 필드 거부, 필수 누락(메타·설정·페이지) 거부, 자료형 오류 거부. 변이 확인(ChannelConfig에 additionalProperties: true + required 제거 → 실패 검출).
- 부수: Gradle 테스트 태스크에 `docs/api/openapi.yaml` 입력 선언(명세만 바뀌면 up-to-date로 계약 테스트가 건너뛰어지던 문제).

### P-T02-02 — gitleaks 오탐 처리 방식 (2026-11-25 제안 → **최종 승인(2026-11-25), 기존 fingerprint 두 건에 한함**)
- 배경: CI gitleaks가 커밋 4b29e7b(이미 원격에 푸시된 WIP 커밋)의 정합성 검사 SQL(두 bucket의 subject 컬럼 비교)을 generic-api-key로 탐지. 비밀값 아님. 푸시된 히스토리 재작성은 파괴적이라 배제.
- 적용(PR #1 CI 통과를 위해 작업 브랜치에 반영, 되돌릴 수 있음): 저장소 루트 `.gitleaksignore`에 **fingerprint(커밋:파일:규칙:줄) 단위**로만 예외 등록, 규칙·워크플로 변경 없음. 항목마다 근거 주석, 주석에 탐지 문자열 인용 금지.
- 대안: `.gitleaks.toml` 경로·정규식 allowlist(범위가 넓어 비권장), 코드 줄에 `gitleaks:allow` 주석(히스토리 탐지는 해결 못 함).
- 승인 여부: **승인** — `.gitleaksignore`의 fingerprint 2건(`4b29e7b…:BudgetMapper.java:generic-api-key:485`, `e0ae07e…:.gitleaksignore:generic-api-key:2`)에 한한다. 새 예외는 별도 승인 필요. 범위 검증: Codex·Claude 리뷰(CI run 45525957400·45526405063·45526843601의 탐지 건수 변화)

### P-T02-03 — UI 개선 중 확인한 API 보완 후보 (2026-11-25 제안 → **보류(후속 작업)**, 2026-11-25 결정)
- 채널 목록에 현재 설정 요약(기본 콘텐츠 유형, 설정 revision 시각) 필드 추가 — 현재 UI는 목록 행마다 `GET /channels/{id}/config`를 호출(N+1).
- 예약 상태별 건수 조회 — 상태 필터 배지용. 현재는 대시보드의 정산 확인 필요 건수만 사용.
- 통화별 현재 환율을 한 번에 조회 — 현재 UI는 목록으로 상태를 계산(서버 판단과 같은 규칙, 표시용).
- 일·월 예산 사용 시계열 — 차트(Recharts 등) 도입의 전제.
- 영향: OpenAPI·백엔드 추가(기존 계약 변경 없음). DB 변경 없음.
- 2026-11-25 T02-UI-FIX 지시에서 사용자 확인 항목 UI-F06·F08·F09·F10도 보류(후속 작업)로 결정. 승인된 UI-F01~F05·F07·F11~F13은 표시 문구로 반영(ADR-066).

### P-T02-04 — 백엔드 교차 리뷰 잠재 위험 (2026-11-25 제안 → **조건부 수용, ADR-065**)
- CR-01: 로그인 동시 인증 상한(전체 4, IP 2)과 실패 차단이 `remoteAddr` 기준 — 역방향 프록시 뒤 운영 배포 시 관리자 전체가 한 IP로 묶인다. 운영 배포 설계(신뢰 프록시, ADR-045 후속)와 함께 결정.
- CR-02: `RESERVATION_BUCKET_LINK_MISMATCH` 발생 시 진단·수동 복구 절차가 없다(자동 수정 금지는 유지). 운영 전 복구 절차 ADR 필요.
- T03 제약: 콘텐츠(LIFETIME)·content bucket 연결 시 링크 검사 확장 필수, 재시도 경계는 상위 트랜잭션 안에서 호출 불가(작업 큐와 예산의 원자성은 멱등 키·아웃박스 등으로 별도 설계), `reserve:`·`reserve-v2:` 이벤트 키 접두사 예약.

### P-T02-05 — 정산 응답 유실과 화면 경계 (2026-11-25 조건부 수용)
- T02-UI-FIX-EXT는 같은 화면에서 예약 ID·멱등 키·확정 본문·확인 상태를 유지한다. 원장 조회는 POST와 분리하고, 관리자가 재시도할 때 같은 키와 본문을 사용한다. 원장에 이미 기록된 키를 확인하면 완료 안내를 표시한다.
- 전체 새로고침·화면 이탈·다른 탭 또는 기기에서 응답을 받지 못한 정산을 수동 재입력하면 별개 이벤트가 될 수 있다. 이 잔여 위험은 T02 종료 판정에 한해 조건부 수용한다.
- T03 설계에서는 서버 중심의 영속 요청 식별·처리 결과 조회 및 복구를 검토한다. **실제 비용 발생 및 유료 API 활성화 전 근본 해결을 완료해야 한다.** 정상적으로 반복하는 별개 조정은 각 요청으로 구별한다.

### T02-FINAL-PATCH — RVX-02-CB 보강 승인 (2026-11-25)

- 최종 리뷰에서 RVX-01·03 및 RVX-02 지연 HTTP 응답 차단을 통과로 확정했다. 승인 범위는 RVX-02-CB 보호 mutation 콜백의 인증 세대 경계에 한정하며 나머지 T02 개발 범위를 동결한다.
- CR-01·CR-02, P-T02-03·P-T02-05 및 T03 선행 설계 제약을 유지한다. 실제 비용 발생 및 유료 API 활성화 전 정산 영속 멱등성과 결과 복구가 필요하다.
- T02는 IMPLEMENTED를 유지한다. [최종 리뷰](docs/reviews/T02_FINAL_REVIEW.md) · [보강 보고서](docs/reports/T02_FINAL_PATCH_REPORT.md)

### ADR-063 전문 — 관리자 UI 디자인 시스템과 기술 선택 (승인 2026-11-25)
- **배경**: T02 관리자 화면을 상용 관리자 콘솔 수준으로 개선하고, T03~T10 화면이 같은 규칙을 쓰도록 확장 가능한 디자인 시스템이 필요했다. React 19.3.1·TypeScript 6.0.3·Vite 8.3.8은 유지하고 다운그레이드·의존성 충돌 무시 옵션(--force, --legacy-peer-deps)은 쓰지 않는다.
- **결정**:
  1. 스타일은 Tailwind CSS 4(CSS 우선 설정). 색·반경·그림자·타입 스케일을 라이트(`:root`)/다크(`.dark`) CSS 변수 토큰으로 정의하고 `@theme`에 연결한다. 컴포넌트는 토큰 클래스만 사용한다.
  2. 아이콘은 Lucide React, 접근성 프리미티브는 Radix(Dialog·Tooltip·Tabs)만 사용한다. shadcn/ui는 CLI 생성 대신 같은 패턴(Radix + Tailwind 토큰)으로 필요한 컴포넌트만 `frontend/src/ui`에 직접 작성한다.
  3. 서체는 Pretendard(SIL OFL 1.1)를 npm 패키지로 고정해 Vite가 로컬 번들로 제공한다(외부 CDN 없음, 폰트 바이너리 Git 커밋 없음).
  4. 차트 라이브러리(Recharts)는 채택하지 않는다 — 시계열 API가 없고 예산 미터로 충분하다(P-T02-03 이후 재검토).
  5. 금액·환율은 문자열로만 다루며(`frontend/src/lib/decimal.ts`, BigInt 고정소수점) Number 변환을 금지한다.
  6. 브라우저 E2E는 Playwright로 설치된 Edge 채널을 사용하고 API는 브라우저 네트워크 계층 대역으로 대체한다(실제 백엔드 연동 검증과 구분). CI는 windows-2025 러너의 Edge로 실행한다.
- **고정 의존성(버전과 선택 이유)**:
  | 패키지 | 버전 | 범위 | 이유 |
  |---|---|---|---|
  | tailwindcss / @tailwindcss/vite | 4.3.5 / 4.3.5 | dependencies | 토큰 기반 스타일, Vite 8 peer 지원(`^5.2.0 \|\| ^6 \|\| ^7 \|\| ^8`) |
  | lucide-react | 1.79.0 | dependencies | 일관된 선형 아이콘, React 19 peer |
  | pretendard | 1.3.9 | dependencies | 한국어 가독성, OFL-1.1, 동적 서브셋 로컬 번들 |
  | @radix-ui/react-dialog / -tooltip / -tabs | 1.3.1 / 1.4.1 / 1.2.1 | dependencies | 포커스 가두기·키보드·aria 처리(직접 구현 위험 회피), React 19 peer |
  | clsx / tailwind-merge | 2.1.1 / 3.7.1 | dependencies | 조건부 클래스 결합·충돌 정리 |
  | @playwright/test | 1.65.1 | devDependencies | 실제 브라우저 렌더링 E2E(Node ≥20) |
  | react-router(기존 T02) | 8.5.3 | dependencies | 화면 라우팅·route.lazy 코드 분할 |
  | @testing-library/user-event(기존 T02) | 14.6.13 | devDependencies | 사용자 입력 시뮬레이션 |
- **결과**: 규칙 문서 `docs/design/UI_DESIGN_SYSTEM.md`, AGENTS.md UI 규칙 1줄. 새 UI 라이브러리 추가·임의 색 사용은 ADR 제안이 필요하다.


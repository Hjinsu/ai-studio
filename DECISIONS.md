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

### P-T02-02 — gitleaks 오탐 처리 방식 (2026-11-25, 제안, **승인 대기**)
- 배경: CI gitleaks가 커밋 4b29e7b(이미 원격에 푸시된 WIP 커밋)의 정합성 검사 SQL(두 bucket의 subject 컬럼 비교)을 generic-api-key로 탐지. 비밀값 아님. 푸시된 히스토리 재작성은 파괴적이라 배제.
- 적용(PR #1 CI 통과를 위해 작업 브랜치에 반영, 되돌릴 수 있음): 저장소 루트 `.gitleaksignore`에 **fingerprint(커밋:파일:규칙:줄) 단위**로만 예외 등록, 규칙·워크플로 변경 없음. 항목마다 근거 주석, 주석에 탐지 문자열 인용 금지.
- 대안: `.gitleaks.toml` 경로·정규식 allowlist(범위가 넓어 비권장), 코드 줄에 `gitleaks:allow` 주석(히스토리 탐지는 해결 못 함).
- 승인 여부: 대기(설계 담당 검토)


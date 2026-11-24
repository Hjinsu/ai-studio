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

# T01 완료 보고서 (ChatGPT 설계 검토용)
작성 2026-11-25(3차 갱신: 설계 확정 ADR-012~025 반영) · 구현 Claude Code · 상태: **IMPLEMENTED, CI 통합 검증 대기** (DONE 아님) · 커밋 없음

## 1. 이번 지시 반영 결과
| 지시 | 결과 |
|---|---|
| 1. 결정 충돌·누락 검토 | 2절. 해석으로 처리한 4건, T02 전 결정 필요 13건(T02_PLAN D-01~D-13) |
| 2. DECISIONS·설계 문서 반영 | ADR-012 승인, ADR-014~025 기록. docs/02,03,04,06,07,08,09,10,12,13,14, README, .env.example 갱신 |
| 3. I-01~I-09 상태 기록 | DECISIONS.md "미결 설계 이슈 상태" 표(결정 ADR, 남은 상세 설계, 구현 단계) |
| 4. 오류 처리·DB 보호 수정 | 오류 코드 19개, 미분류 상태 유지, 406도 JSON 본문, `/error` 공통 형식(필터 오류), 전 profile Flyway 직전 DB 검증, 테스트 DB 격리, prod migration 계정 분리 |
| 5. V2 작성·로컬 검증 | `V2__fix_database_collation.sql` 로컬 ai_studio(V1 상태)에 적용 성공, collation `utf8mb4_unicode_ci` 확인 |
| 6. 신규/업그레이드 테스트 | `DatabaseBootstrapIT`(신규), `DatabaseUpgradeIT`(V1→최신, 불허 DB 차단) 작성. **Docker 없어 로컬 미실행** |
| 7. 로컬 테스트 | 단위 100/100, 프론트 전체, actionlint, gitleaks 통과. 로컬 실DB 업그레이드·재기동·loopback·Vite 프록시 확인 |
| 8. CI·gradlew 권한 | Windows job 실행 방식 수정(`gradlew.bat`), actionlint 통과, `gradlew` 100755 staged. **CI 미실행** |
| 9. 보고서·STATE 갱신 | 본 문서, STATE.md |
| 10. T02 계획 | docs/plans/T02_PLAN.md |

## 2. 결정 검토 결과 (충돌·누락)
**구현에서 해석으로 처리 (검토 요청)**
1. **CLIENT_ERROR의 HTTP 상태**: 결정에 상태가 없음 → 미분류 4xx의 실제 상태로 응답(enum 기본값 400). 미분류 5xx는 INTERNAL_SERVER_ERROR + 실제 상태(예: 502, 504).
2. **상태만 아는 409/422 오류의 코드**: 프레임워크·필터 오류처럼 원인을 모르는 경우 409→RESOURCE_CONFLICT, 422→POLICY_VIOLATION. 세분 코드는 애플리케이션이 명시적으로 발생시킨다.
3. **prod 허용 DB 이름**: 결정에 없음 → `ai_studio`로 고정. 운영 DB 이름이 다르면 결정 필요.
4. **운영 migration 계정 변수 이름**: `AI_STUDIO_DB_MIGRATION_USER/PASSWORD`(ADR-013 규칙), Flyway는 같은 URL에 이 계정으로 접속. ADR-025로 기록.

**결정 보강이 필요한 누락 (T02 전)** — 상세는 T02_PLAN 5절
- ADR-017과 019의 관계: budget_reservation(상태)와 원장 RESERVE(이력)의 기준 데이터(D-01)
- 환율 출처·적용 시점(D-02), PROVISIONAL 없는 CONFIRM 처리(D-03), CONTENT 예산의 기간과 T03 이전 FK 부재(D-04), 반올림(D-05)
- 초기 관리자 생성 방식(D-06, 하드코딩 금지), 계약 테스트 방식·생성 타입 커밋(D-07, D-08)
- ADR-022 전체 상태 전이표는 T03 착수 전 작성 필요(미작성)
- ADR-016 asset 저장 경로 규칙 재정의(기존 경로는 revision 단위)

**실측으로 발견해 결정에 반영한 사항**
- MariaDB 11.2+는 `DEFAULT CHARSET=utf8mb4`만 쓰면 DB 기본값이 아닌 `utf8mb4_uca1400_ai_ci`를 적용(로컬 11.8.2 임시 테이블로 확인). → DDL 규칙(docs/03) + `MigrationConventionTest`로 강제.
- 로컬 서버를 127.0.0.1에만 바인딩하면 Node의 `localhost`가 IPv6(::1)로 해석될 수 있어 Vite 프록시 기본값을 `127.0.0.1:8080`으로 변경(E2E 확인).
- GitHub Windows runner 기본 shell(pwsh)에서 POSIX `gradlew` 실행 문제 → `gradlew.bat` 명시.

## 3. 구현 상세
| 항목 | 내용 | 검증 |
|---|---|---|
| 오류 코드 | 19개 고정(ADR-012), `ErrorCode.fromStatus` | ErrorCodeTest 18 |
| MVC 오류 | 404/405/406/413/415/400/검증/미분류(418, 451, 502, 504)/내부 예외 비노출 | GlobalExceptionHandlerTest 35 |
| 필터 오류 | `ApiErrorController`(`/error`): sendError(401/403/418)·필터 예외 → 공통 형식, X-Trace-Id와 body 일치 | ErrorDispatchTest 5(실제 Tomcat) |
| 필수 변수 | local/docker/prod 필수 3개, prod는 migration 2개 추가, 값 비노출 | RequiredDatabaseEnvironmentPostProcessorTest 9 |
| 허용 DB | URL 사전 검사(모든 DB profile) + Flyway 직전 `SELECT DATABASE()`(모든 profile, test=`ai_studio_test`). 운영처럼 migration 계정이 분리되면 애플리케이션 계정 연결도 Flyway 전에 확인 | DatabaseGuardConfigurationTest 6, DatabaseTargetTest 11, 로컬 실DB, DatabaseUpgradeIT(미실행) |
| Migration 규칙 | 파일명, 적용된 migration SHA-256 불변, CHARSET+unicode_ci 규칙 | MigrationConventionTest 4 + 변이 확인 |
| V2 | `ALTER DATABASE CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci` | 로컬 업그레이드 성공, IT 미실행 |
| loopback | local `server.address=127.0.0.1` | LISTEN 127.0.0.1:8080, LAN 접속 불가 확인 |

## 4. 테스트 결과
| 테스트 | 결과 |
|---|---|
| 백엔드 단위 | **103/103 통과** |
| 로컬 MariaDB 11.8.2: V1→V2 업그레이드, 재기동, health UP | **통과** |
| Vite 프록시 E2E, loopback 바인딩 | **통과** |
| 프론트 lint/typecheck/test/build | **통과** (4/4) |
| actionlint, gitleaks(dir, git) | **통과** |
| 통합 테스트(DatabaseBootstrapIT, DatabaseUpgradeIT) | **실패(환경: Docker 없음) — 미검증** |
| Docker build/Compose, GitHub Actions(Linux/Windows) | **미실행** |
| prod profile 실제 운영 DB | **미검증** |

**결론: 로컬에서 실행 가능한 범위는 모두 통과. 신규 DB 초기화·업그레이드 통합 테스트와 CI는 미검증이므로 T01은 DONE이 아니다.**

## 5. T01 DONE 전환 조건
1. 사용자 지시로 커밋/푸시(gradlew 100755 staged 상태)
2. GitHub Actions 전 job 통과: backend-linux(단위+통합 2종+bootJar+compose config+image build), backend-windows, frontend(Linux/Windows), secret-scan

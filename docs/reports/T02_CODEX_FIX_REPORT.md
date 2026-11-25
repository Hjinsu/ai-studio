# T02-FIX 백엔드 결함 수정 보고서

- 담당 Codex · 2026-11-25 · T02 상태 IMPLEMENTED, 종료 승인 보류
- 리뷰 및 구현 시작 기준 `fa32535f7090a027da4c63964e5ba728bd704f5b`. 시작 시 fetch 후 Draft PR #1 HEAD가 일치했고 수정에 반영할 최신 원격 변경은 없었다.
- 구현 커밋 `25bcf759b693da22b3b24e1e6ceecf4c1f679479`에 RV-01~06 및 회귀 테스트가 포함됐다.
- 작업 브랜치 `fix/t02-backend`, 원본 UI 작업 트리와 별도인 `../ai-studio-t02-fix` worktree에서 작업했다. OpenAPI·생성 타입·원장 설계 문서의 원본 worktree 변경이 없음을 확인하고 승인된 계약 부분만 수정했다. 통합 담당자가 UI 변경과 합칠 때 공식 생성 명령 및 최신 HEAD 계약 검사를 다시 실행한다.

## 결함별 결과

| ID 및 심각도 | 수정 전 재현과 원인 | 수정 및 파일 | 회귀 검증 · 호환성 |
|---|---|---|---|
| RV-01 HIGH · ADR-026·055 | `fa32535`에서 동시 재요청이 한도 소진 시 BUDGET_EXCEEDED, 종료 이벤트에서는 INVALID_STATE_TRANSITION. 잠금 이전에 key를 읽은 뒤 후행 호출이 UNIQUE 제약에 도달하기 전에 실패했다. | `BudgetReservationService`, `BudgetMapper`, `ChannelService`. 제어·채널·정책 잠금 후 요청 키를, bucket·예약 잠금 후 이벤트 키를 재확인한다. 전역 UNIQUE와 중복 INSERT 이후 재조회는 유지한다. | `BudgetConcurrencyIT`에서 한도 전액 예약, CONFIRM/RELEASE, fingerprint 불일치, 서로 다른 예약의 전역 이벤트 키 경쟁 및 단일 원장·집계 효과를 검증한다. 기존 재요청 응답 및 충돌 코드는 동일하다. |
| RV-02 HIGH · ADR-017·027·038·046·049 | 기준 커밋에서 정책 또는 bucket 잠금 대기 중 새 예산 기간이 시작해도 이전 시각을 사용했고, 최신 환율 만료 후에도 이전 판정 시각으로 신규 예약이 통과했다. | `BudgetReservationService`. 정책 잠금 아래 후보 bucket을 찾고 bucket 잠금도 확보한 후 한 판정 시각을 읽는다. 후보가 그 시각을 포함하지 않으면 해당 트랜잭션을 롤백하고 다시 계획한다. 최대 세 번 경계를 넘으면 기존 SERVICE_UNAVAILABLE을 반환한다. | `BudgetConcurrencyIT`의 테스트 시계·DB 쿼리 관측으로 일·월 경계, 정책 전환, 기존 정산이 보유한 bucket 잠금, 환율 만료·fallback 금지와 성공 후 재요청을 검증한다. 기존 정산의 bucket 및 환율은 그대로 사용한다. |
| RV-05 HIGH · ADR-037·051·060 | 업데이트·감사 INSERT 각각의 실패를 트리거로 주입했을 때 500 이후 세션에 인증이 남았다. 세션 저장이 필수 DB 쓰기보다 먼저 실행됐다. | `AuthController`, `AdminAccountService`. 마지막 로그인 시각과 감사 기록을 같은 트랜잭션에 쓰고 커밋 뒤 세션 회전·CSRF 갱신과 세션 저장을 한다. 후속 예외에서 컨텍스트를 지우고 세션을 폐기한다. | `AuthIT`에서 두 DB 실패 후 보호 API 401, 감사 실패 시 로그인 시각 롤백, 기존 로그인·로그아웃·재설정 흐름을 검증한다. `AuthControllerTest`는 저장소가 세션에 쓴 뒤 실패하는 경우를 검증한다. DB 커밋 이후 세션 쓰기가 실패하면 DB 감사와 로그인 시각은 유지될 수 있다. |
| RV-03 MEDIUM · ADR-026·053 | 서로 다른 긴 요청 키와 동일한 요청 내용으로 원장 UNIQUE 오류를 재현했다. 기존 `reserve:` 접두사가 8자이므로 충돌 가능 경계는 121자부터다. | `BudgetReservationService`. 새 RESERVE 키는 전용 접두사와 실제 request key의 해시를 사용한다. 기존 내부 접두사도 새 정산 이벤트에서 차단하고 기존 일반 이벤트 재요청은 계속 허용한다. | `BudgetLedgerIT`의 120~128자 경계, 동일 내용의 다른 키, 전용 namespace, 기존 원장 키 fixture 재요청 및 정산으로 확인한다. 기존 원장 키의 수정과 재작성은 없다. |
| RV-04 MEDIUM · ADR-030·057·062 | 기준 계약 검증기에서 KRW 기준 소수 7자리가 통과했다. 기존 HTTP 구현의 KRW 예약 비용 변동과 외화 예약 원본 비용 유지 동작은 승인된 설계와 일치했다. | `BudgetControllers`, `docs/api/openapi.yaml`, 공식 도구로 재생성한 `schema.d.ts`. 기준별 소수 자릿수와 KRW 예약 예외를 명시했다. 두 기준 모두 `cost_log.original_amount DECIMAL(24,12)` 때문에 정수부 12자리가 한계다. | `OpenApiContractTest`, `BudgetLedgerIT`에서 기준별 조정·환불 및 원본 비용, 실제 HTTP 응답, 13자리와 소수 7자리 거부, 최대 KRW 이벤트 금액 `999999999999.999999` 저장·환불을 검증한다. 엔드포인트와 필드명은 동일하다. |
| RV-06 MEDIUM · ADR-012·032 | signed BIGINT 최댓값을 1 초과하는 19자리 ID로 기준 커밋에서 HTTP 500 재현. 경로·쿼리에서 정규식 통과 후 Long 파싱 예외가 발생했다. | 공통 `ApiId`를 채널·예산 API에 적용한다. 범위/형식 오류는 기존 VALIDATION_FAILED와 400으로 반환한다. OpenAPI ID 설명에 정확한 범위를 기록했다. | `BudgetLedgerIT`의 1, 0, 음수, 비숫자, 최댓값, 최댓값 초과, 경로·쿼리와 미존재 리소스 404를 검증한다. ID 전송 형식은 문자열이다. |

## 추가 검증 및 승인 요청

- **정상 확인** · 로그인 제한의 login+IP 5회, login 10회, IP 30회와 15분 만료 직전·경계는 기존 및 강화한 `LoginRateLimiterTest`로 검증한다.
- **잠재 위험** · `AuthController`의 제한 조회, BCrypt 검증, 실패 기록은 별도 단계다. 동시 요청은 실패가 집계되기 전에 더 많이 승인 심사에 진입할 수 있어 임계값이 진행 중 BCrypt의 상한이 되지 않는다. 독립 설계 승인 안건으로 per-key admission 및 최대 동시 BCrypt 작업량을 검토한다.
- **확인된 결함** · 격리 DB의 rollback-only fixture에서 예약과 RESERVE 원장의 합계가 일치하지만 SYSTEM DAY·MONTH 링크가 모두 없을 때, 정합성 검사 결과는 OK였다. 계약상 필수 연결을 어떻게 복원하거나 판정할지 별도 승인이 필요하다. 원장 및 링크 데이터를 자동 수정하는 변경은 없다.
- **정상 확인** · MariaDB에서 실제 deadlock victim의 첫 쓰기가 롤백됐고 두 작업은 총 3회 시도로 완료됐다. 운영과 같은 MyBatis 잠금 경로에서 1205를 발생시켜 최초 실행과 재시도 3회가 소진되고 선행 쓰기까지 모두 롤백됨을 확인했다. 초기 진단에서 JdbcTemplate의 기본 변환기는 1205를 UncategorizedSQLException으로 분류했다. 잠금 재시도 경계에 향후 JdbcTemplate 경로를 넣을 경우 별도 검증이 필요하다.
- **잠재 위험** · 기존 REQUIRED 기반 `LockRetryingTransactions`가 상위 트랜잭션에 참여하면 재시도 후에도 rollback-only가 남는다. 테스트에서 내부 콜백은 두 번 실행됐지만 외부 커밋이 실패했고 경합 전 쓰기도 모두 롤백됐다. 일반 T02 API 경로는 재시도 경계를 직접 시작한다. 향후 T03에서 외부 트랜잭션 안에서 호출할 경우 독립 트랜잭션 경계 또는 호출 계약에 대한 승인 필요.
- **문서** · 원장 설계의 정합성 검사 경로를 실제 `POST /api/v1/budget/consistency-checks`로 바로잡았다.
- **통합 전달** · P-T02-02는 기존 fingerprint 두 건만 설계 승인됐다. 통합 담당자가 `DECISIONS.md`에 승인 상태를 반영하고 `STATE.md`와 `TASKS.md`에 본 보고서 및 결과를 연결한다. 기존 예외 파일과 migration V1~V5는 편집하지 않았다.

## 검증 환경 및 기록

- MariaDB 11.8.2 임시 인스턴스 `127.0.0.1:43307`, `utf8mb4_unicode_ci`, UTC, deadlock detection 활성. TestDatabases가 격리한 테스트 스키마에만 V1~V5를 적용했다. 작업·운영 DB는 사용하지 않았다.
- 수정 전 검증은 기준 커밋의 임시 소스 사본에 회귀 테스트만 복사해 실행했다. RV-01·02·05·06 통합 8건에서 예상 실패를 확인했다. RV-04 명세 단위 테스트도 예상대로 실패했다. RV-03은 수정 worktree에서 키 로직 변경 전에 테스트를 실행해 UNIQUE 위반을 확인했다.
- `DEBUG=false` 환경에서 `.\gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --no-build-cache` 성공. 단위 129건, 통합 90건, 실패·오류·skip 0건. 최초 전체 실행에서 상속된 DEBUG 환경이 CLI 조건 보고서에 비활성 Tomcat 클래스 이름을 노출해 기존 문자열 검사가 실패했다. 재실행에서는 테스트 프로세스의 DEBUG만 false로 설정했다.
- 체크섬을 확인한 gitleaks 8.30.1의 `git --staged --redact .` 및 `git --redact .` 검사에서 누수 없음. 기존 fingerprint 두 건을 유지했다.
- `npm.cmd ci` 후 `npm.cmd run generate`, `npm.cmd run lint:openapi`, `npm.cmd run check` 통과. 기존 Redocly 경고 2건(CSRF 4xx 정의, PageMeta 직접 미참조).
- 백엔드 전용 [Draft PR #2](https://github.com/Hjinsu/ai-studio/pull/2)의 HEAD `52c6f97`에서 [CI 45544416344](https://github.com/Hjinsu/ai-studio/actions/runs/45544416344) 6개 작업이 모두 통과했다. Linux 단위·Testcontainers 통합 테스트, boot JAR 및 이미지 빌드, Windows 백엔드, 양쪽 프론트엔드, OpenAPI, gitleaks를 포함한다. 이 결과를 남기는 후속 커밋은 보고서만 수정한다.
- **미검증** · 실제 동시 로그인 부하에서의 BCrypt 작업량은 계측하지 않았다. UI를 합친 최신 통합 PR HEAD의 CI와 Claude Code 독립 리뷰는 통합 담당자에게 이관했다.
- 통합 담당자에게 Claude Code의 독립 백엔드 리뷰와 UI를 포함한 최신 통합 PR HEAD의 CI 검증을 요청한다. main 병합, T02 DONE 전환, T03 착수, 실제 유료 호출·운영 지출 설정·게시 작업은 수행하지 않았다.

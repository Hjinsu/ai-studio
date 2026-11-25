# T02-FIX-EXT 백엔드 안정성 개선

- 담당 Codex · 2026-11-25 · T02 상태 IMPLEMENTED, 종료 승인 보류.
- 기준 `7b7a57366850c10655932f57c9b1515e767ed723`. fetch 이후 별도 `fix/t02-backend` worktree의 HEAD와 Draft PR #2가 일치했다.
- 선행 RV-01~06의 설계 검토 승인과 수정 이력은 [기존 보고서](T02_CODEX_FIX_REPORT.md)를 참조한다. UI worktree와 통합 상태 문서는 통합 담당자 관리 범위다.

## 결함별 재현과 수정

| ID | 기준 커밋에서 재현 | 적용한 수정과 회귀 테스트 |
|---|---|---|
| RV-07 HIGH · ADR-017·026·038·054 | 원장 및 bucket 합계는 맞지만 SYSTEM DAY만 누락, MONTH만 누락, 둘 다 누락, 다른 채널 범위의 bucket 연결인 4개 fixture에서 정합성 검사가 이 구조 오류를 보고하지 않았다. | `BudgetMapper`가 예약 당시 저장된 시각을 포함하는 SYSTEM DAY/MONTH가 각각 하나인지 검사한다. 다른 범위, 잘못된 기간, 중복 SYSTEM/CHANNEL 유형도 진단한다. 정책 생성 시각과 링크된 bucket의 저장 경계를 사용하고 이후 정책 상태·revision·시간대 변경과 독립적이다. `BudgetControlService`가 기존 합계 검사와 결합해 결정적인 순서로 보고하고 ERROR를 기록한다. `BudgetLedgerIT`는 합계가 일치하는 모든 링크 오류와 반복 결과를, `BudgetTimeIT`는 이전 revision·시간대의 정상 링크를, `BudgetFailClosedIT`는 신규 예약 차단과 기존 확정·해제를 검증한다. |
| RV-08 MEDIUM · ADR-036·045·051 | latch로 인증 작업을 점유해도 동일 login, 동일 IP, 인스턴스 전체의 추가 호출이 모두 인증 처리에 진입했다. 3개 재현 테스트에서 기대한 429 대신 자격 증명 실패 결과가 반환됐다. | `LoginAdmission`이 정규화 계정당 1건·remoteAddr당 2건·인스턴스 전체 4건을 인증 시작 전에 제한한다. 대기열을 두지 않고 포화 시 기존 RATE_LIMIT_EXCEEDED 429와 1초 Retry-After를 사용한다. 정상·미존재 계정 모두 적용하며 더미 BCrypt도 보호한다. 처리 중 키만 저장하고 모든 완료·예외 경로에서 제거한다. 기존 차단을 입장 전과 입장 직후 확인하고 포화 응답을 실패 카운터에 반영하지 않는다. `AuthControllerTest`와 `AuthIT`에서 세 한도, 정상 복구, 인증·DB 예외, 실제 HTTP 및 기존 차단을 검증한다. |
| RV-09 HIGH · ADR-043·055 | 활성 상위 트랜잭션에서 재시도 콜백을 호출하면 거부 대신 SQL 변경이 실행됐다. 기존 진단에서는 실패 후 콜백이 재실행돼도 상위 rollback-only 상태가 유지됐다. | `LockRetryingTransactions`가 활성 상위 트랜잭션을 감지해 콜백 실행 전에 IllegalStateException으로 거부한다. 기존 READ COMMITTED와 전체 롤백 이후 재시도, 잠금 순서와 REQUIRED 기반 정책·제어 트랜잭션을 보존한다. `BudgetConcurrencyIT`는 외부 트랜잭션 거부 전 콜백 0회·SQL 변경 없음, 실제 데드락 롤백 및 재시도 성공, 1205에서 최초+3회 소진, 원장·예약·bucket 롤백을 검증한다. 기존 경합 테스트는 테스트용 커밋 리스너로 독립 트랜잭션의 커밋 직전만 멈춘다. |

## 계약 및 운영 검토

- BCrypt 점검은 기존 Spring Security Crypto 7.1.3과 Java 21, 6코어/12스레드 AMD Ryzen 5 5500GT에서 cost 12 검증 12건을 1회 실행했다. 예열 후 워커 1개에서 2.80초(4.29건/초), 4개에서 0.74초(16.21건/초)였다. 이 수치는 해당 개발 PC의 BCrypt 단독 표본이며 운영 환경 지연이나 처리량은 미계측이다. IP와 전체 제한은 인스턴스 단위다.
- RV-05의 `AUTH_LOGIN SUCCESS`와 `last_login_at`은 자격 증명 확인과 필수 DB 기록 커밋을 의미한다. 뒤의 세션 저장이 실패하면 세션과 컨텍스트를 폐기하고 HTTP 오류를 반환한다. 이미 커밋된 DB 기록은 보존한다. [인증 설계](../design/T02_AUTH.md)
- 제품 호출 경로 확인 결과 `reserve`, `markReconciliationRequired`와 `applyEvent`가 직접 재시도 경계를 호출한다. HTTP 정산 resolve·adjustments는 트랜잭션 없는 컨트롤러에서 시작한다. 내부 잠금과 감사 쓰기는 기존 트랜잭션에 참여한다. 멱등 재응답은 읽기 경로를 유지한다. 향후 T03 작업 상태 전이와 예산 호출의 트랜잭션 계약은 별도 승인 설계 사항이다. [원장 설계](../design/T02_BUDGET_LEDGER.md)
- 과거 정책의 상태를 복원할 이력은 현재 스키마에 없으므로 링크 진단은 당시 불변 범위·기간 유형, 저장된 bucket 경계 및 예약 시각을 근거로 한다. 오류 발견 시 상태와 감사 기록만 저장하며 복구 또는 원장 재작성은 수행하지 않는다. RV-03 원장 키 호환성과 RV-04 DECIMAL(24,12) 정수부 제한을 보존했다.
- 2026-11-25 설계 검토에서 RV-07~09 방향과 RV-07의 `ConsistencyReport.issues[].checkCode` 값 `RESERVATION_BUCKET_LINK_MISMATCH` 추가를 승인했다. 기존 enum 값, HTTP 경로·구조·오류 정책 및 RV-08 로그인 429 계약을 유지했다.
- `openapi-typescript` 공식 생성 명령으로 타입을 다시 만들었다. 생성물은 `ConsistencyReport.issues[].checkCode` 문자열 유니언에 승인된 값 하나가 늘었다.
- UI 작업 트리의 정합성 표시에서 exhaustive switch를 사용하지 않음을 확인했다. `CHECK_CODE`는 문자열 키 매핑이고, 미등록 값은 코드 문자열로 표시된다. 통합 담당자에게 표시 문구 **예산 예약 연결 불일치**와 상세 **비용 예약에 필요한 예산 연결이 누락되었거나 올바르지 않습니다. 신규 비용 예약이 차단되었습니다.**를 전달한다.

## 변경 파일

- 예산 구현 `BudgetControlService.java`, `BudgetMapper.java`.
- 인증·재시도 구현 `AuthController.java`, `LoginAdmission.java`, `LockRetryingTransactions.java`.
- 회귀 테스트 `BudgetConcurrencyIT.java`, `BudgetFailClosedIT.java`, `BudgetLedgerIT.java`, `BudgetTimeIT.java`, `AuthControllerTest.java`, `AuthIT.java`.
- 계약·문서 `docs/api/openapi.yaml`, `frontend/src/api/generated/schema.d.ts`, `docs/design/T02_AUTH.md`, `docs/design/T02_BUDGET_LEDGER.md`, 이 보고서.

## 검증과 인수인계

- 수정 전 표적 실행에서 단위 3건 및 통합 5건이 예상대로 실패했다. 최종 로컬 검증은 MariaDB 11.8.2 임시 인스턴스 `127.0.0.1:43308`에서 진행했다. UTC, `utf8mb4_unicode_ci` 설정으로 테스트용 스키마에 V1~V5를 적용하고 확인 후 임시 인스턴스를 종료했다.
- `DEBUG=false`에서 `.\gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --no-build-cache --rerun-tasks` 성공. 단위 133건, 통합 97건, 실패·오류·건너뜀 0건과 Boot JAR 산출물을 확인했다.
- 실제 MVC 경로 `POST /api/v1/budget/consistency-checks`가 200, `consistent=false`, `entityType=RESERVATION`, 문자열 예약 ID와 `RESERVATION_BUCKET_LINK_MISMATCH`를 반환했다. 전체 응답의 OpenAPI 검증이 통과했고 ERROR 기록 후 새 예약 거부와 기존 예약의 확정·해제를 검증했다.
- `frontend/tools/openapi-typescript`에서 `npm.cmd run generate`, `npm.cmd run lint:openapi`, `npm.cmd run check` 통과. Redocly 기존 경고 2건(CSRF 4xx 정의, PageMeta 직접 미참조)이 표시됐다.
- 백엔드 브랜치의 `frontend`에서 `npm.cmd ci` 및 `npm.cmd run lint`, `typecheck`, `test`, `build` 통과. 프론트엔드 테스트 26건과 Vite 산출물을 확인했다.
- 원본 UI 작업 트리를 건드리지 않고 임시 사본의 생성 타입을 교체했다. 해당 사본의 `npm.cmd ci` 및 `npm.cmd run typecheck`가 통과했다. 정합성 화면의 임시 fixture로 신규 코드, 예약 대상 및 신규 예약 차단 안내를 렌더링하는 표적 Vitest 1건도 통과했다. 매핑이 없으면 코드가 오류 제목과 원시 코드 위치에 각각 표시된다.
- gitleaks 8.30.1의 staged 및 전체 Git 이력 검사에서 누수 없음. 승인된 기존 fingerprint 두 건을 유지했다.
- 운영 환경 인증 처리량은 배포와 같은 CPU 할당, BCrypt 설정 및 프록시 구성을 가진 테스트 환경에서 계정·IP별 및 전체의 동시 요청으로 확인한다. 한도 초과 429와 `Retry-After: 1`, 정상 처리 복구, 로그인 지연 및 CPU 사용량을 기록한다. 운영 배포 계측과 분산 제한 검증은 미실행이다.
- 제품 변경 커밋은 RV-08·09 `bb865f4`와 RV-07 `396575c16384e6c91422c273f71b7b578e9499e6`이다. Draft PR #2의 제품 검증 HEAD는 `396575c`였고 CI run `45548882612`에서 6개 작업 모두 통과했다. 최종 보고서는 이 제품 커밋 이후 문서만 갱신한다. [제품 검증 CI](https://github.com/Hjinsu/ai-studio/actions/runs/45548882612) · [최신 Draft PR HEAD 및 CI](https://github.com/Hjinsu/ai-studio/pull/2/checks)
- Claude Code 독립 리뷰, UI 브랜치 통합, 최신 통합 HEAD 검증은 통합 담당자에게 이관한다. T02는 IMPLEMENTED 상태이며 종료 승인은 보류됐다.

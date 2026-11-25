# T02-RV 백엔드 독립 코드 리뷰

- 담당 Codex · 검토일 2026-11-25 · 독립 리뷰 작성 완료
- 기준 `fa32535f7090a027da4c63964e5ba728bd704f5b` · `wip/t02-implementation-checkpoint-2026-11-25`. 세션 시작 시 로컬 HEAD와 Draft PR #1의 원격 HEAD가 일치한 커밋으로 고정했다. 파일·줄 번호는 이 커밋 기준이다.
- 판정은 **T02 종료 승인 보류 권고**다. 예약·정산의 동시 멱등성 및 예산 기간 경계를 우선 수정하고 격리 DB에서 검증해야 한다.

## 확인된 결함

아래 결함은 소스와 계약을 대조해 확인했다. 제시한 경계 사례는 후속 회귀 테스트 조건이며 이번 리뷰에서 별도로 실행하지 않았다.

### RV-01 · HIGH · 예약과 종료 이벤트의 동시 재요청이 멱등 계약을 위반한다

ADR-026·055, [BudgetReservationService.java:97](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L97), [동 파일:149](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L149), [동 파일:337](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L337), [동 파일:367](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L367).

두 호출이 잠금 전 key 조회에서 모두 미등록을 읽으면 후행 호출은 잠금을 기다린 후 INSERT까지 도달해야만 중복 키 처리로 들어간다. 선행 예약이 남은 한도를 모두 사용한 경우 같은 요청의 후행 호출은 INSERT 전에 `BUDGET_EXCEEDED`로 종료된다. 선행 CONFIRM 또는 RELEASE가 먼저 커밋되면 후행 호출은 종료 상태를 읽고 INSERT 전에 `INVALID_STATE_TRANSITION`을 반환한다. 같은 key의 내용이 다를 때도 우선해야 할 `RESOURCE_CONFLICT`가 가려질 수 있다.

직렬화 잠금 획득 후 한도·상태 검사 전에 key와 fingerprint를 다시 확인하고, 다른 예약 사이의 전역 event key 경쟁에는 UNIQUE 방어를 유지한다. 잠금 대기 순서를 제어한 테스트에서 한도 전액 예약과 CONFIRM·RELEASE의 동일 키 요청 두 건이 모두 같은 예약을 반환하고 원장이 한 번만 변경되는지 확인한다. 기존 `BudgetConcurrencyIT:293`은 반복 가능한 PROVISIONAL을 써서 종료 상태 경쟁을 다루지 않는다.

### RV-02 · HIGH · 잠금 대기 전에 읽은 시각으로 만료 환율과 종료 bucket을 선택한다

ADR-017·027·038·046·049, [BudgetReservationService.java:120](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L120), [동 파일:139](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L139).

`doReserve`는 제어·채널·정책 잠금을 기다리기 전에 시각을 고정한다. 작업이 대기 중 환율 만료나 일·월 경계를 넘으면 잠금 해제 후에도 이전 시각의 환율과 bucket을 쓴다. 승인 시점에 유효 환율이 없는 신규 외화 예약이 통과하거나 새 기간의 비용이 종료 기간에 연결될 수 있다.

예약의 판정 시점을 설계상 명시하고 관련 직렬화 잠금을 획득한 뒤 하나의 시각으로 환율·기간을 결정한다. 제어 또는 정책 잠금으로 예약을 대기시킨 후 테스트 시계를 경계 너머로 옮겨, 만료 환율 거부와 새 bucket 연결을 각각 검증한다. 기존 `BudgetTimeIT`의 만료 및 시간대 전환 테스트는 이 잠금 대기와 결합하지 않는다.

### RV-03 · MEDIUM · 긴 request key의 RESERVE 원장 키가 서로 충돌한다

ADR-026·053, [BudgetReservationService.java:90](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L90), [동 파일:174](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L174).

122~128자의 서로 다른 request key에 채널·목적·통화·금액이 같으면 접두사를 붙인 키가 길이 한계를 넘고, 두 요청 모두 request key를 포함하지 않는 동일 fingerprint를 원장 키로 쓴다. 두 번째 예약은 원장 UNIQUE 위반으로 롤백되며 자기 request key로 조회할 행도 없다. 서로 다른 정상 요청을 처리할 수 없다.

RESERVE 원장 키를 request key 자체에서 안정적으로 도출하고, 내부 생성 키와 관리자 이벤트 키의 이름 공간도 검토한다. 서로 다른 최대 길이 key 두 건의 성공과 각 key의 재요청에 대한 원장 1건 보장을 검증한다.

### RV-04 · MEDIUM · KRW 조정의 API 계약이 승인된 정산 규칙과 어긋난다

ADR-057, [BudgetReservationService.java:282](../../backend/src/main/java/com/aistudio/budget/BudgetReservationService.java#L282), [openapi.yaml:1374](../api/openapi.yaml#L1374).

OpenAPI는 `basis=KRW`이면 원본 인식 비용을 유지한다고 설명한다. [원장 설계 §2](../design/T02_BUDGET_LEDGER.md#2-이벤트와-델타)는 예약 통화가 KRW일 때 원본과 KRW의 delta가 같다고 명시하며 서비스도 이 규칙을 따른다. 예를 들어 100 KRW 확정 후 KRW 기준 5를 조정하면 원본 비용은 105로 바뀐다. 또한 요청 스키마의 `amount`는 `OriginalAmount`를 참조하므로 KRW 조정에서 서버가 거부하는 소수 7~12자리를 허용하고, KRW 저장 범위의 정수 13~18자리는 표현하지 못한다.

승인된 KRW 예약 예외를 API 설명에 명시하고 조정 기준별 자릿수 제약을 구현과 일치시킨다. KRW·외화 예약의 조정 및 환불에서 원본 delta를 각각 확인하고 KRW 기준 정수 13자리 및 소수 7자리 경계를 계약 테스트에 추가한다.

### RV-05 · MEDIUM · 로그인 후속 DB 쓰기가 실패해도 인증 세션이 남을 수 있다

ADR-037·051·060, [AuthController.java:111](../../backend/src/main/java/com/aistudio/security/AuthController.java#L111), [AuditService.java:52](../../backend/src/main/java/com/aistudio/audit/AuditService.java#L52).

로그인 성공 경로에서 세션 ID를 회전시키고 인증을 저장한 뒤 마지막 로그인 시각과 성공 감사 로그를 쓴다. 후속 DB 쓰기에서 예외가 발생하면 오류 응답을 반환하면서도 인증 컨텍스트는 세션에 저장된 상태다. 특히 감사 쓰기만 실패하면 성공 감사 없이 인증이 유지되고 UI가 보는 로그인 결과와 서버 상태가 어긋난다.

필수 DB 쓰기의 커밋 경계와 인증 세션 공개 순서를 정하고, 실패할 때 인증이 남지 않도록 한다. 감사 저장 실패를 주입한 테스트에서 오류 응답 이후 해당 세션이 보호 API에 인증되지 않는지 확인한다.

### RV-06 · MEDIUM · BIGINT 범위를 넘는 계약상 유효 ID가 500으로 처리된다

ADR-012·032, [BudgetControllers.java:44](../../backend/src/main/java/com/aistudio/budget/BudgetControllers.java#L44), [동 파일:274](../../backend/src/main/java/com/aistudio/budget/BudgetControllers.java#L274), [openapi.yaml:924](../api/openapi.yaml#L924), [GlobalExceptionHandler.java:68](../../backend/src/main/java/com/aistudio/shared/api/GlobalExceptionHandler.java#L68).

OpenAPI와 컨트롤러 정규식은 양의 19자리 ID를 허용한다. signed BIGINT 최댓값을 넘는 19자리 문자열도 통과한 뒤 `Long.parseLong`에서 예외가 발생하고 공통 500 처리로 이어진다. 경로와 조회 필터 양쪽에서 입력 오류가 서버 오류로 분류된다.

컨트롤러 공통 ID 변환에서 범위를 검증해 400 공통 오류를 반환하고 명세의 범위도 일치시킨다. 최대 signed BIGINT와 그보다 1 큰 값을 경로·쿼리에 각각 검증한다.

## 검토 범위와 추가 검증

| 영역 | 확인한 근거와 검증 과제 |
|---|---|
| 인증·보안 | `SecurityConfiguration`, `CredentialVersionFilter`, `AdminAuthenticationProvider`, `LoginRateLimiter` 및 profile 설정을 검토했다. 보호 API의 ADMIN 권한, 로그인 포함 CSRF, 세션 회전·토큰 폐기, credential version 검사, 미존재 계정의 더미 BCrypt 및 remoteAddr 사용을 확인했다. 실패 제한 확인과 BCrypt 실행 및 실패 집계가 별도 단계이므로 동시 인증 요청에서 임계값을 넘는 진행 중 작업이 생긴다. 동시 요청 제한의 기대 동작을 명확히 하고 login+IP 외 login·IP 축 및 차단 만료도 회귀 검증한다. |
| 관리자 CLI | `AdminCommandRunner`, `AdminAccountService`, `PasswordPolicy`, V3를 검토했다. 숨김 입력, 배열 초기화, 초기 관리자 singleton 제약·트랜잭션과 재설정 시 credential version 증가를 확인했다. `AdminCliIT`는 대체 입력으로 생성·재설정을 실행하고 `AdminCliJarIT`는 산출물의 비대화형 거부 경로를 확인한다. 실제 Windows 대화형 터미널에서의 숨김 입력과 성공 종료는 여전히 사용자 검증 항목이다. |
| 예산·거래 | SYSTEM DAY·MONTH 필수, 기본 지출 차단, 채널 상태와 제어 행의 잠금, 정합성 오류 차단 및 잠금 순서를 확인했다. `LockRetryingTransactions:28–40`의 기본 REQUIRED는 외부 트랜잭션에 참여하면 독립 READ COMMITTED·전체 rollback 후 재시도를 보장하지 않는다. 현행 호출 경로를 유지하며 향후 호출자 트랜잭션과의 경계를 테스트하고, 실제 데드락·잠금 대기 초과 및 재시도 소진을 검증한다. |
| 비용 원장·환율 | 예약 연결 bucket에 정산을 반영하고 예약 환율을 재사용한다. 최신 발효 환율 만료 시 이전 환율로 fallback하지 않으며, 기존 IT에 만료·미래 환율 및 기간 경계가 있다. 정합성 SQL은 연결된 행의 합계를 확인한다. 필수 SYSTEM 연결 자체가 누락된 채 합계까지 일치하는 데이터는 검사 항목에 포함되지 않으므로 누락 연결 진단 추가를 검토한다. |
| DB 권한 | `prod-app-grants.sql`, V3~V5, `ProductionGrantsIT`에서 앱 계정의 원장·감사·불변 이력 SELECT/INSERT, DDL·DELETE 제한과 운영 권한 계정의 정상 경로를 확인했다. 운영 binlog ON 상태의 트리거 생성, 기존 계정의 상위 grant·역할 상속은 배포 환경에서 별도 확인이 필요하다(ADR-025·050·058). |
| OpenAPI·테스트 | ADR-062 평탄화, `OpenApiContractTest`의 중첩 미정의 필드·필수 필드 검증과 CI 타입 재생성 검사를 확인했다. 실제 HTTP 계약에는 RV-01·04·06의 응답까지 추가한다. 설계 문서 §9의 정합성 검사 경로를 실제 계약인 `POST /api/v1/budget/consistency-checks`에 맞춰 정리할 필요가 있다. |

## gitleaks 예외 검토 · P-T02-02

- `git show 4b29e7b:backend/src/main/java/com/aistudio/budget/BudgetMapper.java` 485행은 동일 정책의 bucket 중첩을 찾는 SQL에서 두 컬럼을 비교한다. `git show e0ae07e:.gitleaksignore` 2행은 같은 비교를 인용했던 주석이다. 두 위치 모두 자격 증명이나 비밀값이 없는 오탐이다.
- 현재 [예외 파일](../../.gitleaksignore)은 커밋·경로·규칙·행이 고정된 fingerprint 두 건만 허용한다. CI는 전체 이력을 checkout한 후 고정 버전 8.30.1로 `gitleaks git --redact --verbose .`를 실행한다. 최신 리뷰 기준 CI 로그에 13 commits scanned 및 no leaks found가 표시됐다. P-T02-02는 현 범위로 승인 권고하며 결정은 승인 대기 상태다.

## 검증 및 인수인계

- 기준 커밋의 [CI run 45530569438](https://github.com/Hjinsu/ai-studio/actions/runs/45530569438)에서 6개 job 성공을 확인했다. Linux 로그에서 단위 테스트, Testcontainers 통합 테스트, boot JAR 및 컨테이너 이미지 빌드가 성공했다. 통합 테스트 개별 건수는 로그에서 확인되지 않았다.
- 기준 커밋의 `git archive`를 임시 디렉터리에 풀어 `.\gradlew.bat :backend:test :backend:bootJar --no-build-cache --rerun-tasks`를 실행했다. 단위 테스트 127건 통과, 실패·오류·건너뜀 0건이며 boot JAR 빌드가 성공했다.
- 검토 중 로컬 DB migration과 운영 DB 변경은 수행하지 않았다. 통합 테스트와 gitleaks는 최신 기준 CI의 실행 결과를 확인했으며 로컬에서 재실행하지 않았다.
- 산출물은 이 보고서 한 파일이다. 제품 소스·UI·공통 상태 파일은 변경하지 않았다. main 병합, T02 DONE 전환, T03 착수, 커밋·푸시는 수행하지 않았다.
- 다음 작업은 사용자 승인 후 T02-FIX 범위를 배정하고 RV-01·02부터 회귀 검증을 추가하는 것이다. 최종 통합 검증은 UI 작업이 포함된 최신 PR HEAD에서 별도로 수행한다.

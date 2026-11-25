# T03-B-RV 독립 코드 리뷰

2026-11-25 KST · Codex · **HOLD** · T03-C 착수 차단

## 기준과 범위

- 리뷰 대상 origin/t03/b-domain `a2500a8a6bf63dda701dc75bead51a390e8f43b7`. git fetch 후 사용자 지정 SHA와 일치했다. 판독과 테스트는 별도 detached worktree에서 수행했다.
- 설계 `d495296a85c0c312807a06c6fd24aa19b1f4872f`, 제품 `main@47ef697`. 비교는 구현 변경 33개 파일을 포함한다. AGENTS/TASKS/STATE/DECISIONS, 협업 가이드, t03-final 및 T03_B_REPORT의 주장·코드를 대조했다.
- [Draft PR #3](https://github.com/Hjinsu/ai-studio/pull/3), base `t03/integration`, head 위 SHA. 리뷰 보고서만 신규 작성했다. 사용자 개발 MariaDB 3306에 접근하지 않았다.
- V1~V5, 제품 OpenAPI/생성 타입, budget, frontend, CI 변경 없음. Run 실행·Worker 구현 없음. B의 command_log, V6, 채널 상태 락과 FD-16, 회귀 테스트·권한은 배정 범위다.

## 발견 사항

| ID | 심각도 / 판정 | 파일·줄 및 ADR | 조건·영향·검증 | 권장 수정 / 차단 |
|---|---|---|---|---|
| RV-B-01 | **HIGH · DB에서 재현** | V6__create_content_domain.sql:118–138, 215–252, 277–281. FD-05, ADR-067/068. ChannelMapper.java:88–89 | DRAFT의 콘텐츠에 다른 run_no로 QUEUED 2개를 커밋했다. 결과 `DRAFT / NULL / 비종료 2`. PRODUCING에서 1→2 포인터 직접 교체도 성공했다. 두 번째 nonterminal을 막는 DB 키와 역방향 검증이 없다. FD-16이 포인터만 세므로 고아 Run을 놓치고 보관할 수 있다. B에는 Run 생성 HTTP가 없어 현행 사용자 API를 통한 생성은 확인되지 않았다. | 포인터 조건과 함께 콘텐츠당 비종료 Run에 DB UNIQUE guard를 설계한다. 불가피한 INSERT→포인터와 포인터 해제→종료 중간 상태는 승인된 단일 writer TX로 제한하고, 보관·부팅·claim은 실제 비종료 Run과 포인터 불일치를 감지하고 fail closed한다. 직접 포인터 교체와 고아·이중 비종료 케이스를 회귀 테스트한다. **C 착수 차단**. |
| RV-B-02 | **MEDIUM · R10 통합 위험, DB 롤백 실측** | V6:243–245, 277–281, ContentFixtures.java:138–144, ContentSchemaIT.java:246–252. ADR-068/070/071 | 격리 DB에서 BEGIN → 포인터 해제 → FAKE manifest가 있는 종료 UPDATE의 CHECK 오류(4025) → 연결 종료 후 `PRODUCING / 1 / RUNNING` 복원을 확인했다. 실패한 문장 하나가 전체 TX 롤백을 보장하는 것은 아니다. 성공 경로 fixture는 자체 TransactionTemplate가 없으며 실제 C R10 서비스는 미구현이다. | C에서 content X→run X로 선취득, 단일 최상위 TX에 가드·포인터·종료·카운트/p12/감사 포함. 둘째 UPDATE 0행·오류를 전파하여 반드시 전체 롤백. 실제 서비스 쪽 fault test 필수. **C 진입 전에 계약을 확정**, 합격은 C 구현 게이트. |
| RV-B-03 | **LOW · 잠재 호환성** | TopicNormalizer.java:15–28, TopicNormalizerTest.java. ADR-067 및 T03 topic 해시 계약 | v1 단독에서는 제목→preimage가 단사다. v12 도입 시 (1, 2topic)과 (12, topic)이 모두 `12topic`으로 인코딩된다. SHA-256 입력·출력 일치를 독립 확인했다. 버전 변경 시 오탐 409 가능. | 고정 길이 또는 버전·제목 분리 도메인 인코딩(예 JSON 배열)으로 버전 경계를 명확히 한다. 기존 v1 데이터가 존재하면 임의 재해시 대신 전환·교차 버전 중복 규칙을 승인한다. 현재는 v1 고정으로 **독립 차단 아님**. |
| RV-B-04 | **MEDIUM · 테스트 재현 / 배포 위험 분리** | ProductionGrantsIT.java:70–79,225–234, prod-app-grants.sql:20–24, V6:175–281. ADR-025/026 및 FV-02 | 임시 MariaDB binlog ON에서 앱 계정 CREATE TRIGGER가 1419(SUPER 없음)로 거절됐다. 테스트는 오류에 denied를 기대하므로 실패했다. 파일 권한에 TRIGGER/SUPER는 없다. root 기반 격리 migration으로 V6 적용은 성공했지만 운영 최소 권한 migration 사용자의 생성 성공까지 입증하지 못했다. | binlog 환경별 권한 부정을 SQLState/코드와 부작용 0으로 확인하고, 실제 배포용 별도 migration 계정/DBA 절차를 검증한다. 앱 SUPER 부여 금지. **운영 배포 게이트**, 로컬 1419만으로 제품 코드 장애 판정하지 않는다. |
| RV-B-05 | **LOW · 계약 검증 보완** | ContentService.java:99–111,148–159,275–311, TopicService.java:51–66, CanonicalJson.java:62–88. ADR-026/074 | JSON 객체 키 순서·정수·배열·null은 결정적이다. 명령 지문에는 raw 제목/brief/settings가 들어가 저장 시 줄바꿈·trim/missing-vs-empty 정규화 결과가 같은 요청도 409가 될 수 있다. 예산 지문 변경은 없다. | v1 replay 기준을 정규화된 JSON의 요청값으로 문서화한다. 저장 의미까지 동등하게 재생할 필요가 있으면 정규화 범위와 버전·기존 지문 호환을 먼저 확정한다. surrogate/보조 평면 길이·큰 입력 회귀 추가. **독립 차단 아님**. |
| RV-B-06 | **LOW · 회귀 테스트 환경 민감성** | AdminCliJarIT.java:59–62. ADR-031 | 로컬 프로세스에 상속된 DEBUG에서 Spring 조건 보고서에 Tomcat 문자열이 있어 기존 문자열 부정 assertion이 실패했다. 실패 로그는 서버가 실제로 바인딩했다는 증거가 아니다. | 테스트 프로세스에서 DEBUG를 격리하거나 웹 스택 실제 기동 증거를 단정적으로 검사한다. **독립 차단 아님**. |

## RV-B1~B8 상세

- **B1**: MigrationConventionTest의 기존 체크섬·V6 등록과 Git diff의 V1~V5 무변경 확인. V6는 channel_config 대체 키→topic/content/revision/run→역방향 FK→queue_command_log→트리거. content 3열과 쌍 UNIQUE, channel_config 소유권, 동일 콘텐츠 revision/run, 명시 NULL 테스트 및 V5 실제 서비스 데이터 업그레이드 checksum을 확인했다. V6의 다단계 DDL 적용 실패는 전체 DB TX 롤백을 기대할 수 없으므로 깨끗한 DB와 업그레이드 검증을 유지한다.
- **B2**: 포인터→같은 콘텐츠의 비종료 Run, 제목 BINARY 동등, terminal/취소 의도/FAKE manifest 방어는 한 방향으로 동작한다. 역방향·유일성과 R10 실서비스는 RV-B-01/02 참조. 신규 직접 SQL은 비종료 Run을 반드시 연결하는 계약을 검증해야 한다.
- **B3**: IdempotentCommands.java:48–88. 전역 binary request_key, 동일 키+지문+명령타입 재생, 교차 타입/다른 본문 409, 효과와 로그 insert 하나의 top-level READ COMMITTED TX. DuplicateKey나 동시 상태 검사 오류 후 롤백 밖에서 로그를 재조회한다. ContentApiIT의 중간 실패와 ContentConcurrencyIT의 같은 키 revision 경쟁은 잔여 효과/단일 revision을 확인한다. 생성 재생은 현재 리소스, revision 재생은 원래 revision ID. 이후 보관과 관계없이 같은 키는 선검사 재생이다. 커밋 응답 유실의 네트워크 fault injection은 미실행. 실제 다시 POST 시 저장 로그가 있으면 재생된다. 기존 로그가 있을 때 선조회는 최상위 실행기를 거치지 않으므로 이후 C 호출계층은 항상 외부 상위 TX 없이 이 유틸을 호출해야 한다.
- **B4**: NFKC→Locale.ROOT→Unicode 공간 압축→strip, DB 길이와 별개로 Java UTF-16 길이 제한. CanonicalJson은 codepoint 정렬, ordered array, 명시 null, integer-only. Canonical settings UTF-8 16KiB 제한, settings 허용 키 정책은 설계 미정. v1 hash 프레이밍은 RV-B-03.
- **B5**: 기존 SecurityConfiguration.java:69–92의 ADMIN 및 CSRF 적용. ApiId의 문자열 패턴·long 범위, version/baseRevisionNo, 양수 페이지 제한과 LIKE literal escape(!, %, _) 확인. 타 채널 Topic은 404, 타 Run/revision 소유권은 복합 FK와 서비스 검사. 새로운 실제 OpenAPI는 제품 파일 통합 전이다. 숫자 JSON의 Jackson scalar 강제 변환 여부와 보조 평면 최대 길이 경계는 독립 HTTP로 검증하지 않았다.
- **B6**: ChannelService.java:134–150의 control X→channel X 및 ContentService.java:162–165의 channel S→content X. ContentConcurrencyIT의 LOCK WAIT 확인과 barrier를 통해 실제 B 경합을 검증했다. 활성 Run을 포인터로만 판정하는 결함은 RV-B-01. pause/resume이 기존 정산을 거절하는 경로는 없지만 control X에서 예약/정산과 대기한다. V6의 content→run FK 및 run→revision FK, terminal 검사 조회를 실제 C Q0/R10과 묶어 교차 락을 실측할 필요가 있다. B 잠금 통과가 C/D 순서 전체를 입증하지 않는다.
- **B7**: 새 APP 권한에서 topic/revision/command_log SELECT/INSERT, content/run SELECT/INSERT/UPDATE. DELETE/DDL 및 기존 원장 변경 금지. T02에서 만든 값과 제약 보존 검증은 ContentUpgradeIT 포함. binlog 프로필 차이와 CLI assertion은 RV-B-04/06. 3306 포트 미사용.
- **B8**: 승인 범위와 일치. V6의 추가 trigger는 종료 순서에 영향을 준다. A1~A7 및 B-D01~07 판정은 아래와 같다.

## B-D01~07 대응

원 보고서 §12는 구현 선택을 번호 없이 나열한다. 다음 표는 그 순서(1~6) 및 §4/§12의 R10 결정을 D07로 매핑한다.

| ID | 판정 |
|---|---|
| B-D01 | settings 객체·정수·canonical 16KiB는 수용 가능. 허용 키의 제품 정책은 계약 승인 시 확정 |
| B-D02 | v1 구분자 없는 hash는 현행 동작 확인. 버전 확장 전 프레이밍 필요(RV-B-03) |
| B-D03 | 보관 채널의 revision 쓰기 409, 콘텐츠 보관 허용 수용. 고아 Run은 별개 차단 |
| B-D04 | 다른 채널 Topic의 404 수용, 교차 소유권 테스트 확인 |
| B-D05 | 도메인 DB UTC 및 예산 Clock 분리 수용, 진행 단계 간 시각 혼용 금지 |
| B-D06 | B CanonicalJson 분리 수용. 기존 Budget Fingerprints 동작 유지 |
| B-D07 | R10 포인터 선행 해제 방향 수용. 전체 실패 롤백 및 역방향 불변의 선행 합의 필요(RV-B-01/02) |

## OpenAPI A1~A7

| ID | 판정 |
|---|---|
| A1 | B 경로 및 평탄 스키마 반영·생성 타입 검증 적절. 현 테스트 overlay는 제품 문서 대체 불가 |
| A2 | D 이전 optional, D의 모든 read path 및 타입 검증을 완료한 뒤 required로 전환 권고 |
| A3 | 활성 Run 409 INVALID_STATE_TRANSITION 문서화 적절. 포인터만 조회하는 한계를 먼저 해소 |
| A4 | DUPLICATE_TOPIC과 NO_CHANGES details 형식 기록 적절 |
| A5 | 실제 채널 언어 기본·최신 설정 revision·기간 제한 및 settings 계약 표현 적절. 원시/저장 정규화의 replay 의미 문서화 |
| A6 | 상태 오류와 타 채널 404를 제품 계약에 명시 적절 |
| A7 | 미구현 Run/Job/Queue는 후속 계약. B의 CI 성공이 해당 경로 제공을 의미하지 않음 |

## FV 및 검증

| 조건 | B 판정 |
|---|---|
| FV-01 | V6 범위 적합. worker→attempt 순서는 C에서 확인 |
| FV-02 | B 권한 및 불변 테스트 확인. binlog ON 운영 migration 계정 및 직접 활성 Run 불변 미완 |
| FV-03 | B의 실제 대기·경쟁 검증. Q0/R10와 D 공통/암묵 경계는 후속 검증 |
| FV-04 | B 참조 키 있음. R10 전체 TX·receipt/P12는 C/D에서 검증 |
| FV-05 | overlay 단위/통합, 제품 계약·생성 타입의 기존 CI 결과와 구분 |
| FV-06 | 127.0.0.1의 별도 임시 DB만 사용. FAKE 비용 실검증 C/D |

### 독립 테스트와 CI

| 검증 | 결과 |
|---|---|
| Windows 단위 테스트 | `gradlew.bat :backend:test --rerun-tasks --no-build-cache` 실행 결과 21 suites, 147/147, skip 0 |
| bootJar | 캐시 없는 재실행에서 성공. AdminCliJarIT의 비웹 동작은 아래 표준 프로필 재실행에서 통과 |
| 격리 MariaDB 11.8.2, 43307, binlog ON | 18 suites 132건 중 130 통과, 2 실패. ProductionGrantsIT의 MariaDB 1419 거절 메시지와 로컬 DEBUG를 상속한 AdminCliJarIT 문자열 assertion |
| 격리 MariaDB 11.8.2, 43309, binlog OFF | 프로세스 단위 DEBUG 해제 후 `gradlew.bat :backend:integrationTest --rerun-tasks --no-build-cache`의 Gradle BUILD SUCCESSFUL. 18 suites 132/132, skip 0. 종료 시 PowerShell이 MariaDB 클라이언트의 stderr 경고를 NativeCommandError로 처리하여 래퍼 종료 코드는 1이었다. 실제 Gradle 결과와 XML은 성공 |
| DB 직접 검증, 43308 | V1~V6 SQL을 동일 버전 별도 인스턴스에 적용. 두 비종료 Run/포인터 교체 재현, R10 두 번째 SQL이 실패한 후 세션 종료로 롤백 및 포인터 복원 확인 |
| GitHub Actions | [45623881433](https://github.com/Hjinsu/ai-studio/actions/runs/45623881433) 고정 SHA와 일치, **7/7 성공**. Linux 단위·MariaDB 11.8.8 Testcontainers 통합·bootJar·Compose 확인·Docker 이미지, Windows 백엔드, Linux/Windows 프론트·Edge E2E, 현행 OpenAPI/생성 타입, gitleaks 성공. 성공 시 테스트 보고서 업로드 단계는 skip |

11.8.2 로컬 표준 프로필과 11.8.8 CI에서 신규/업그레이드·B 통합 테스트 경로를 통과했다. binlog ON의 운영 migration 계정, 실제 T03-C R10/큐 동시성, 커밋 응답 유실을 주입한 재생, T03 신규 제품 OpenAPI와 생성 타입 통합은 미검증이다. CI 성공은 RV-B-01의 직접 SQL 상태가 불가능하다는 증거가 아니다.

### 승인 조건

RV-B-01을 별도 수정 작업에 배정하고, T03-C에 들어가기 전에 단일 nonterminal 및 고아·보관 방어 계약과 V6의 배포 적용 상태에 따른 보정 전략을 승인한다. R10 원자성 가드 및 실패 주입을 인수 조건에 넣는다. 실제 C/D 테스트, OpenAPI 전환, 운영 binlog migration 계정은 해당 완료·배포 게이트에서 확인한다. **최종 권고 HOLD.**

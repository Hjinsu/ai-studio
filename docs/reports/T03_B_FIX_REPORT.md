# T03-B-FIX 독립 리뷰 결함 수정 보고서

작성 2026-11-25 · 담당 Claude Code · 상태 **T03-B IMPLEMENTED 유지**(DONE 아님) · 커밋·푸시 없음(사용자 승인 대기)

- 브랜치 `t03/b-domain`, 기준 SHA `a2500a8a6bf63dda701dc75bead51a390e8f43b7`
- 리뷰 `docs/reviews/T03_B_CODEX_REVIEW.md`(Codex, HOLD, RV-B-01~06). 리뷰 파일은 integration worktree(`AI-Studio-design-v0.1`)의 미추적 파일로 존재했고, 읽기만 했다.
- 구현 보고서 [T03_B_REPORT.md](T03_B_REPORT.md)

## 1. V6 적용 이력 확인 및 변경 전략

| 대상 | 확인 결과 |
|---|---|
| 사용자 개발 DB `ai_studio`(127.0.0.1:3306) | **읽기 전용 조회**(사용자 승인, `.env` 계정, SELECT만): `flyway_schema_history` = V1~V5만, V6 테이블(topic·content·content_revision·content_run·queue_command_log) 0개. topic v1 데이터 없음 |
| `t03/b-domain` worktree | `.env` 없음 — 이 worktree의 bootRun은 3306에 접속하지 못한다 |
| 그 외 적용처 | 폐기 가능한 격리 DB만: Claude 임시 서버(3307/3308)의 `ai_studio_test_*`, Codex 임시 서버(43307~43309), CI Testcontainers |

전략: 사용자가 **미배포 V6 수정 예외를 명시 승인**(2026-11-25)해 V6을 제자리 보완했다. V1~V5는 무변경(기준 대비 diff 0). 체크섬 변경 기록:

| | V6 SHA-256(LF 정규화) |
|---|---|
| 이전(`a2500a8`) | `4fa717cc451bcd0593bf8eb0c3d9ce7b1a2954b912398939d2086db4f0780c9b` |
| 현재 | `304900fda20821c83a9f3f3a56c9568f4215544158020569bd6f36e9d2412588` |

`MigrationConventionTest`에 새 값을 등록하고 예외 사유를 주석으로 남겼다. 사용자 개발 DB에는 아무것도 쓰지 않았다. **이 V6을 적용한 격리 DB 외 환경이 생기면 이후 변경은 반드시 신규 migration으로 한다.**

## 2. RV-B-01 (HIGH) — 콘텐츠당 비종료 Run 1개

### 수정

| 위치 | 내용 |
|---|---|
| V6 `content_run` | `nonterminal_content_id BIGINT AS (CASE WHEN status IN ('QUEUED','RUNNING') THEN content_id END) PERSISTENT` + `uk_content_run_nonterminal UNIQUE(nonterminal_content_id)`. 종료 run은 NULL이라 이력 무제한 보존. 기존 `active_run_id` 복합 FK·소유권 FK 유지 |
| V6 `trg_content_before_update` | (1) 활성 포인터 **직접 교체 금지**(`OLD`·`NEW` 모두 NOT NULL이고 다르면 거절, 해제→종료→새 run 순서만 허용) (2) 포인터가 없어도 **비종료 run이 있으면 revision 포인터 변경 거절** (3) **비종료 run이 남은 콘텐츠의 ARCHIVED 전이 거절** |
| `ChannelMapper.countActiveRuns` (FD-16) | 포인터 수 + 채널 콘텐츠의 **실제 비종료 run 수**를 함께 센다. 고아 run도 409 INVALID_STATE_TRANSITION |
| `ContentService.requireNoRun` | revision 생성·콘텐츠 보관이 content X 아래에서 포인터 또는 실제 비종료 run을 확인해 409 INVALID_STATE_TRANSITION. 포인터와 run이 다르면 경고 로그만 남기고 **자동 보정·삭제하지 않는다** |

포인터→비종료 run 방향(기존 트리거) + 비종료 run 최대 1개(UNIQUE) + 직접 교체 금지로, 커밋된 상태에서 가능한 조합은 `{run 없음}`, `{비종료 run 1개, 포인터가 그 run}`, `{비종료 run 1개, 포인터 NULL(고아·Q0/R10 중간 상태)}`뿐이다. 마지막 경우는 DB 커밋 시점 제약으로 막을 수 없으므로(MariaDB 지연 제약 없음) 보관·revision·채널 보관이 fail closed한다. 서버 기동·claim에서의 고아 감지는 T03-C가 구현한다(§8).

### 잠금 검증(신규 인덱스·트리거의 암묵 잠금 실측, MariaDB 11.8.2)

| 경로 | READ COMMITTED | REPEATABLE READ |
|---|---|---|
| content UPDATE 트리거의 content_run 조회(보관·revision 검사) — 상대 run 행을 다른 TX가 X 보유 | 대기 없음(일관 읽기), 즉시 판정 | **next-key/gap 잠금**: 다른 콘텐츠의 run INSERT까지 대기(실측) |
| content_run UPDATE 트리거의 content 조회(종료 시 포인터 확인) — 상대 content 행 X 보유 | 대기 없음 | (RR 쓰기 경로 금지로 해당 없음) |
| content_run INSERT | content 행 잠금 없음(FK는 content_revision 행 S) | — |
| 같은 콘텐츠 두 번째 비종료 run INSERT | `uk_content_run_nonterminal`에서 LOCK WAIT → 선행 커밋 시 중복 오류, 선행 롤백 시 진행 | — |

**구현 중 발견하고 되돌린 것**: 처음에는 run INSERT 트리거에 "보관 콘텐츠에 run 생성 금지" 조회를 넣었다. INSERT 문 안의 트리거 조회가 content 행 **S 잠금**이 되어, run INSERT 뒤 content X를 얻는 경로에서 **S→X 변환 교착**이 실측됐다(LOCK 명세가 금지한 패턴). 그 조회는 제거했다. 보관 콘텐츠는 DRAFT 확인(C, content X 아래)과 보관 트리거(비종료 run 있으면 보관 거절)로 방어한다.

결론: (1) 콘텐츠·run 쓰기 경로는 **반드시 READ COMMITTED 최상위 TX**(`LockRetryingTransactions`). RR이면 새 트리거 조회가 gap 잠금을 만든다. (2) run 생성은 Q0대로 **content X를 run INSERT보다 먼저** 얻는다. (3) 전역 순서 content → content_run과 역순인 잠금 경로는 없다.

## 3. RV-B-02 (MEDIUM) — R10 인수 계약(T03-C 게이트)

B는 DB 계약 테스트까지만 하고 실제 R10 서비스는 C에서 구현한다. **C 인수 조건**:

1. 잠금 순서 `content X → content_run X → (job·operation 등 C/D 하위)`. 후보는 비잠금으로 찾고 PK 순으로 잠근 뒤 재검증.
2. 모든 종료 조건(R10: 모든 Job terminal, 미해결 비용·operation 0, 필수 P12 반영)을 같은 잠금 아래에서 검증.
3. **같은 최상위 READ COMMITTED TX**에서 `content`(status DRAFT, active_run_id NULL, version+1) → `content_run` 종료 UPDATE 순서. 순서를 바꾸면 트리거가 거부한다.
4. 포인터 해제·종료 UPDATE의 **영향 행 수가 1이 아니거나 오류면 예외로 전파해 전체 롤백**. 문장 하나의 실패가 TX 롤백을 보장하지 않으므로 호출자가 롤백을 책임진다.
5. 감사·이벤트·카운터·P12 반영을 같은 TX에 포함(원자성).
6. **실제 C 서비스에 대한 장애 주입 테스트 필수**: 포인터 해제 직후 실패, 종료 UPDATE 0행, 감사 실패, 커밋 직전 실패 → 포인터·상태·run이 원래 값으로 남는지.
7. run 생성(Q0): channel S → queue_control X → content X → run INSERT(QUEUED) → `UPDATE content … WHERE active_run_id IS NULL` 영향 1행 확인, 아니면 전체 롤백(고아 run 금지).
8. 기동·claim·reconciler는 `nonterminal_content_id`와 `active_run_id` 불일치를 **감지·경고하고 fail closed**하며 자동 수정하지 않는다(T03 STATE의 Q-INV-05 원칙).

B의 DB 계약 테스트(실제 C 서비스 테스트 아님): `ContentSchemaIT.failedRunCreationTransactionLeavesNoOrphanRun`, `failedTerminalUpdateAfterPointerReleaseRollsBackEverything`(CHECK 오류·0행 두 경우 모두 PRODUCING/포인터/RUNNING 복원), `ContentConcurrencyIT.finishingARunAndCreatingTheNextOneSerializeOnTheContentLock`.

## 4. RV-B-03 (LOW) — Topic hash 버전 경계

선행 확인: §1에서 영속 DB에 topic v1 데이터 없음. `TopicNormalizer.hash` = `SHA-256(UTF-8(canonical JSON [normalizationVersion, normalizedTitle]))`, 예: `[1,"space travel"]`. `normalization_version`은 1 유지(미배포라 재해시 대상 데이터 없음). 테스트: `(1,"2topic")`≠`(12,"topic")`, 따옴표·쉼표가 든 제목의 이스케이프 경계, 1~20 버전 교차 조합의 preimage 비충돌. 예산 fingerprint·revision hash 계약은 변경 없음(`budget/**` diff 0, RevisionHashTest 통과).

## 5. RV-B-04 (MEDIUM) — 운영 권한 거절 검증

`ProductionGrantsIT.forbiddenOperationsAreDeniedByPrivileges`를 다음과 같이 바꿨다.
- 메시지 문자열 대신 **SQL 오류 코드·SQLState** 단언: 테이블·명령 권한 거절 `1142 / 42000`(DELETE·불변 UPDATE·TRUNCATE·DROP·ALTER·CREATE TABLE·mysql.user·GRANT). CREATE TRIGGER는 binlog OFF `1142/42000`, binlog ON `1419/HY000`(SUPER 없음).
- binlog ON에서는 1419만으로 TRIGGER 권한 부재를 입증할 수 없으므로, 격리 root가 `log_bin_trust_function_creators`를 잠시 1로 켜 **실제 권한 거절 1142**를 확인한 뒤 finally로 0으로 되돌린다.
- **부작용 0**: 조건 없는 실제 DELETE/UPDATE를 시도하고 전 테이블 `CHECKSUM TABLE … EXTENDED` 동일, 테이블 목록·`it_x` 컬럼·`it_t` 트리거 변화 없음을 root로 확인.
- 앱 계정 `SHOW GRANTS`에 ALL/DELETE/DROP/ALTER/CREATE/**TRIGGER**/SUPER/GRANT OPTION 없음. 앱 권한 파일은 변경하지 않았다(SUPER·TRIGGER·DDL 미부여).

**격리 root 검증과 운영 migration 계정 배포 검증의 구분**: 테스트의 Flyway는 격리 DB root로 실행한다. 별도로 binlog ON 서버에서 SUPER 없는 migration 계정(`ALL PRIVILEGES ON db.*`)을 수동 실측했다 — `log_bin_trust_function_creators=0`이면 **V3**(T02 기존 트리거)에서 이미 `1419`로 실패, `=1`이면 V1~V6 전부 성공(트리거 28개). 즉 운영 binlog ON 배포에는 V6 이전부터 DBA의 SUPER 기반 migration 또는 `log_bin_trust_function_creators=1`(ADR-050) 절차가 필요하며, **운영 배포 게이트로 남는다**(이번 변경으로 새로 생긴 요구 아님).

## 6. RV-B-05 (LOW) — 멱등 지문 계약과 텍스트 경계

- **지문 v1 계약 유지**: 명령 유형·경로 ID·요청 본문 값(요청 그대로, null 명시)의 canonical JSON SHA-256. trim·줄바꿈 통일·기본값 적용 **이전** 값이므로 저장 결과가 같아도 요청 값이 다르면 같은 키에서 409. 테스트로 고정: `"Same Title"` vs `" Same Title"` → 409, language 생략 vs `"en"` → 409, JSON 키 순서·공백만 다른 같은 요청 → 200 재생.
- **길이 단위 수정(제품 결함 수정)**: 기존 검증이 UTF-16 길이(`String.length()`·`@Size`)를 써서, 보조 평면 문자 255개 제목(코드포인트 255, OpenAPI maxLength·utf8mb4 VARCHAR(255) 허용)을 400으로 거절했다. `content/command/Texts`로 **코드포인트 길이** 검사로 바꾸고, 컨트롤러의 UTF-16 `@Size(max)`를 제거했다(필수·공백 검사는 유지). 대상: topic title(255)·note(500), workingTitle(255), brief(4000), changeReason(500).
- **짝 없는 surrogate**: utf8mb4로 저장할 수 없어 기존에는 DB 오류(500) 가능성이 있었다. 지문 계산 전에 필드별로 검사해 400 VALIDATION_FAILED(`details[0].field` 지정). settings 내부 키·값은 CanonicalJson이 거절 → `settings` 400.
- 큰 입력: settings 정규화 JSON 16,384바이트 경계(통과/초과 400), 300키 × 중첩 객체의 키 순서 무관 동일 저장값, 2,000키 결정성, 다단 중첩 정렬·배열 순서 유지 테스트.
- 예산 Fingerprints 무변경. **Codex OpenAPI 문서화 요청 추가(A8)**: 지문은 요청 값 기준이라는 replay 의미, 길이는 코드포인트 기준, 짝 없는 surrogate는 400.

## 7. RV-B-06 (LOW) — AdminCliJarIT 환경 격리

하위 `java -jar` 프로세스 환경에서 `DEBUG`·`TRACE`·`SPRING_*`·`LOGGING_*`·`AI_STUDIO_*`·`JAVA_TOOL_OPTIONS`·`JDK_JAVA_OPTIONS`·`_JAVA_OPTIONS`를 제거한다(접속 정보는 모두 `-D`로 전달). 비웹 검증은 유지·강화: 기존 Tomcat/Netty/Undertow/web server 부정 단언에 `started on port|listening on port` 패턴 부정 추가, 콘솔 거부 종료 코드 2·Flyway 실행·관리자 미생성 단언 유지. 격리 함수 단위 테스트 추가. 실측: 테스트 JVM에 `DEBUG=1`, `SPRING_MAIN_WEB_APPLICATION_TYPE=servlet`을 상속시킨 채 실행해 통과.

## 8. 필수 회귀 테스트 대응

| # | 요구 | 테스트 | 결과 |
|---|---|---|---|
| 1 | 같은 콘텐츠 QUEUED 2개 거절 | ContentSchemaIT.twoQueuedRunsForTheSameContentAreRejected | 통과 |
| 2 | QUEUED+RUNNING 공존 거절 | queuedAndRunningCannotCoexist | 통과 |
| 3 | 다른 콘텐츠 비종료 run 허용 | differentContentsMayEachHaveANonterminalRun | 통과 |
| 4 | 종료 run 이력 다수 보존 | terminalRunHistoryIsUnlimitedAndANewRunCanFollow(5건) | 통과 |
| 5 | 종료 후 새 비종료 run 허용 | 같은 테스트 | 통과 |
| 6 | 포인터 직접 교체 거절 | activePointerCannotBeSwappedDirectly | 통과 |
| 7 | 고아 run → 채널 보관 거절 | ContentApiIT.orphanRunBlocksChannelArchive | 통과(409, 자동 정리 없음) |
| 8 | 고아 run → 콘텐츠 보관·revision 거절 | ContentApiIT.orphanRunBlocksContentArchiveAndRevision, ContentSchemaIT.orphanRunBlocks…AtTheDatabase | 통과(API 409 + DB 트리거) |
| 9 | 두 커넥션 동시 INSERT 승자 1 | ContentConcurrencyIT.concurrentNonterminalRunInsertsHaveOneWinner, activeRunPointerRace(×5) | 통과(LOCK WAIT 실측) |
| 10 | Run 생성 TX 실패 시 고아 없음 | ContentSchemaIT.failedRunCreationTransactionLeavesNoOrphanRun, ContentConcurrencyIT.rolledBackRunCreation… | 통과 |
| 11 | 포인터 해제 후 종료 실패 → 전체 롤백 | failedTerminalUpdateAfterPointerReleaseRollsBackEverything | 통과(CHECK 오류·0행) |
| 12 | 신규 DB·V5 업그레이드 | DatabaseBootstrapIT, DatabaseUpgradeIT, ContentUpgradeIT, MigrationConventionTest | 통과 |
| 13 | 운영 앱 최소 권한 | ProductionGrantsIT(binlog OFF·ON) | 통과 |
| 14 | 기존 T01/T02 전체 회귀 | 전체 suite | 통과 |

1~11은 **B의 DB 계약 테스트**(SQL fixture로 Q0/R10 순서를 재현)이며 실제 C 서비스 테스트가 아니다.

## 9. 테스트 실제 결과

환경: Windows, Docker 없음 → 로컬 테스트 가이드의 **별도 임시 MariaDB 11.8.2**만 사용(세션 scratchpad 데이터 폴더, 임시 비밀번호, UTC·utf8mb4_unicode_ci·max_connections 300). 사용자 3306 DB에는 §1의 읽기 전용 조회 외 접근 없음.

| 명령 | 서버 | 결과 |
|---|---|---|
| `gradlew.bat :backend:test --rerun-tasks --no-build-cache` | — | **21 suites / 151 tests, 실패 0, 오류 0, 건너뜀 0** |
| `gradlew.bat :backend:integrationTest --rerun-tasks --no-build-cache` | 3307, **binlog OFF**(log_bin=0) | **18 suites / 153 tests, 실패 0, 오류 0, 건너뜀 0** |
| 같은 명령 | 3308, **binlog ON**(log_bin=1, ROW, trust=0) | **18 suites / 153 tests, 실패 0, 오류 0, 건너뜀 0** |
| ContentConcurrencyIT 반복 | 3307 | 추가 3회 모두 16/16(불안정 없음) |
| AdminCliJarIT, `DEBUG=1`·`SPRING_MAIN_WEB_APPLICATION_TYPE=servlet` 상속 | 3307 | 2/2 |
| `gradlew.bat :backend:bootJar` | — | BUILD SUCCESSFUL, `backend-0.1.0-SNAPSHOT.jar` 33,878,792 B |

suite별(integration, 두 서버 동일): ContentSchemaIT 21, ContentApiIT 17, ContentConcurrencyIT 16, ContentUpgradeIT 1, ProductionGrantsIT 2, AdminCliJarIT 2, 기존 T01/T02 94(Actuator 13, BudgetConcurrency 21, BudgetFailClosed 1, BudgetLedger 18, BudgetTime 6, ChannelIT 6, DatabaseBootstrap 8, DatabaseUpgrade 1, ErrorDispatch 5, AdminBootstrap 2, AdminCli 1, AuthIT 12). 단위 신규: TopicNormalizerTest +1, CanonicalJsonTest +3.

## 10. 미검증 사항

- CI(GitHub Actions, Testcontainers MariaDB 11.8.8)는 이 수정본으로 미실행(커밋 전).
- 실제 T03-C 서비스의 Q0/R10·claim·기동 고아 감지와 장애 주입(§3 게이트), C/D 공통 잠금 매트릭스.
- 운영 migration 계정·DBA 절차의 binlog ON 배포 검증(§5, 수동 실측만).
- 커밋 응답 유실 네트워크 장애 주입 재생, 성능(PERF), EXPLAIN 상세.
- OpenAPI·생성 타입 통합(Codex A1~A8).
- 짝 없는 surrogate 외의 비문자(U+FFFE 등)·정규화 형식(NFC/NFD 차이)은 별도 정책 없음 — 지문은 요청 값 그대로라 NFC/NFD가 다르면 다른 요청이다.

## 11. 전체 변경 파일(기준 `a2500a8` 대비, 18개)

제품(9): `db/migration/V6__create_content_domain.sql`, `channel/ChannelMapper.java`, `content/{ContentService,ContentMapper,ContentController}.java`, `content/command/{CanonicalJson,Texts(신규)}.java`, `topic/{TopicNormalizer,TopicService,TopicController}.java` — 신규 1개 포함 10개.
테스트(8): `content/{ContentSchemaIT,ContentApiIT,ContentConcurrencyIT}`, `content/command/CanonicalJsonTest`, `topic/TopicNormalizerTest`, `security/{ProductionGrantsIT,AdminCliJarIT}`, `shared/db/MigrationConventionTest`.
문서: 이 보고서(신규), `T03_B_REPORT.md`(이 보고서 참조 추가).
변경 없음: V1~V5, `budget/**`, OpenAPI·생성 타입, frontend, CI, STATE/TASKS/DECISIONS, `prod-app-grants.sql`.

## 12. git diff --stat (작업 트리, `a2500a8` 대비, 문서 제외)

```
 .../java/com/aistudio/channel/ChannelMapper.java   |  11 +-
 .../com/aistudio/content/ContentController.java    |   8 +-
 .../java/com/aistudio/content/ContentMapper.java   |   4 +
 .../java/com/aistudio/content/ContentService.java  |  44 ++++-
 .../aistudio/content/command/CanonicalJson.java    |  21 +-
 .../java/com/aistudio/topic/TopicController.java   |   2 +-
 .../java/com/aistudio/topic/TopicNormalizer.java   |  12 +-
 .../main/java/com/aistudio/topic/TopicService.java |  12 +-
 .../db/migration/V6__create_content_domain.sql     |  19 +-
 .../java/com/aistudio/content/ContentApiIT.java    | 127 +++++++++++++
 .../com/aistudio/content/ContentConcurrencyIT.java | 180 +++++++++++++++++-
 .../java/com/aistudio/content/ContentSchemaIT.java | 211 +++++++++++++++++++++
 .../content/command/CanonicalJsonTest.java         |  43 +++++
 .../java/com/aistudio/security/AdminCliJarIT.java  |  33 +++-
 .../com/aistudio/security/ProductionGrantsIT.java  |  92 +++++++--
 .../shared/db/MigrationConventionTest.java         |   3 +-
 .../com/aistudio/topic/TopicNormalizerTest.java    |  23 ++-
 17 files changed, 789 insertions(+), 56 deletions(-)
```
신규(미추적): `content/command/Texts.java`(49줄), `docs/reports/T03_B_FIX_REPORT.md`. `git diff --check` 문제 없음, 비밀정보 패턴·임시 DB 비밀번호 검색 0건.

## 13. 다음 담당자

- 사용자: 커밋·푸시 승인(권장 메시지 `fix: address T03-B review findings RV-B-01..06`).
- Codex: 수정본 재리뷰(특히 V6 보완·체크섬 예외, §2 잠금 실측, §5 binlog 분리), CI 실행, OpenAPI A1~A8 반영.
- T03-C(착수 승인 전 BLOCKED): §3 R10·Q0 인수 조건과 RR 금지·content X 선취득 규칙을 전제로 한다.

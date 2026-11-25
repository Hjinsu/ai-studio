# T03-B 콘텐츠 도메인 및 DB 구현 보고서

작성 2026-11-25 · 담당 Claude Code · 상태 **IMPLEMENTED**(Codex 독립 리뷰·통합 검증·사용자 승인 전, DONE 아님)

> **갱신(T03-B-FIX)**: Codex 리뷰 RV-B-01~06 수정으로 V6(사용자 예외 승인)·활성 run 방어·topic hash·길이 단위·권한 테스트가 바뀌었다. 최신 내용과 T03-C R10 인수 조건은 [T03_B_FIX_REPORT.md](T03_B_FIX_REPORT.md)가 이 문서보다 우선한다.

## 1. 기준 커밋 및 작업 브랜치

| 항목 | 값 |
|---|---|
| 작업 ID | T03-B (FV-01~06 조건부 착수 승인) |
| 작업 브랜치 / worktree | `t03/b-domain` / `ai-studio-t03-b` (별도 worktree, integration worktree와 분리) |
| 설계 기준선 | `d495296a85c0c312807a06c6fd24aa19b1f4872f` (`docs: freeze T03 design baseline and prepare T03-B`) |
| 제품 비교 기준 | `main@47ef697` |
| 커밋 | 없음(사용자 승인 전 커밋·푸시 하지 않음). 변경은 작업 트리에만 있다 |
| Migration | `V6__create_content_domain.sql` 1개. V1~V5 무변경(체크섬 테스트 통과) |

## 2. 구현한 기능

- **V6 migration** — channel_config 소유권 키, topic·content·content_revision·content_run, 역참조 소유권 FK 3개, queue_command_log, B 범위 불변·상태 트리거(§4).
- **Topic** (`topic/**`) — 채널별 등록·조회·검색. 정규화 v1(NFKC → 소문자 Locale.ROOT → 공백 압축 → trim)과 `SHA-256(normalization_version || normalized_title)`. 같은 채널 중복은 409 DUPLICATE_RESOURCE(`details[0]={field:"title", reason:"DUPLICATE_TOPIC:<id>"}`), 다른 채널 허용, 보관 채널 409 PREREQUISITE_NOT_MET.
- **Content** (`content/**`) — 등록(content → revision 1 → 현재 포인터 → 명령 로그를 한 TX), 상세, 목록(channelId·topicId·contentType·status·q, id desc, page/size 0/20, 최대 100), 보관(DRAFT → ARCHIVED, version 일치). 채널·유형·topic 불변(트리거), 제목 복제본 = 현재 revision working_title(트리거).
- **Content Revision** — 불변 입력 사양. revision_no = 현재+1, baseRevisionNo 불일치 409 RESOURCE_CONFLICT, 같은 hash 409 DUPLICATE_RESOURCE(`reason NO_CHANGES`), 최신 channel_config 고정, 목표 길이는 설정의 유형별 범위, baseRunId는 같은 콘텐츠의 SUCCEEDED run(400 VALIDATION_FAILED). 포인터·제목·version 원자 갱신.
- **revision_hash v1** — `SHA-256(UTF-8(canonical JSON))`, 키 사전순(baseRunId, brief, channelConfigId, contentId, hashVersion, language, settings, targetDurationSeconds, workingTitle), null 명시, 정수만, 객체 키 코드포인트 순, 줄바꿈 `\n` 통일 후 저장값 사용.
- **Content Run 도메인 기반** — 테이블·소유권 FK·활성 포인터 복합 FK·FAKE 전용 CHECK·종료 불변 트리거. **실행 가능한 Run 생성·Dispatcher·Worker·Run API는 구현하지 않음(T03-C).**
- **FD-16 / CTRL** — `ChannelService.archive`: budget_control **X** → channel X 뒤 활성 run 존재 시 409 INVALID_STATE_TRANSITION. pause/resume도 budget_control S → **X**로 변경(최종 명세 LOCK CTRL, 인수인계 "채널 상태 전이는 budget_control X→channel X").
- **멱등성** — `queue_command_log` 기반 `IdempotentCommands`: 같은 키·같은 지문은 현재 리소스 200 재생, 다른 지문(다른 명령 유형 포함, 전역 키 공간)은 409 RESOURCE_CONFLICT. 동시 같은 키는 UNIQUE 위반 또는 선행 요청 효과로 실패 → 롤백 후 재조회로 재생. 최상위 READ COMMITTED + `LockRetryingTransactions`(교착·잠금 시간초과 전체 재시도).

## 3. 전체 변경 파일 목록

수정(7): `channel/ChannelService.java`, `channel/ChannelMapper.java`, `infra/mariadb/prod-app-grants.sql`, `DatabaseBootstrapIT.java`, `DatabaseUpgradeIT.java`, `security/ProductionGrantsIT.java`, `shared/db/MigrationConventionTest.java`.

신규 제품(15): `db/migration/V6__create_content_domain.sql`; `topic/{Topic,TopicMapper,TopicNormalizer,TopicService,TopicController}.java`; `content/{Content,ContentRevision,ContentMapper,ContentService,ContentController,RevisionHash}.java`; `content/command/{CanonicalJson,CommandLogMapper,IdempotentCommands}.java`.

신규 테스트(11): `topic/TopicNormalizerTest`, `content/{RevisionHashTest,T03ContractOverlayTest}`, `content/command/CanonicalJsonTest`, `content/{ContentSchemaIT,ContentApiIT,ContentConcurrencyIT,ContentUpgradeIT}`, 지원 `content/{ContentFixtures,T03ContractOverlay}`.

문서(1): 이 보고서. 허용 범위 밖 파일(STATE/TASKS/DECISIONS, OpenAPI, 생성 타입, frontend, CI, 예산 코드)은 변경하지 않았다.

## 4. V6 Migration 구조

| 순서 | 내용 |
|---|---|
| 1 | `channel_config ADD uk_channel_config_owner UNIQUE(channel_id,id)` |
| 2 | `topic` — UNIQUE(channel_id, normalized_hash), UNIQUE(id, channel_id), hash CHECK |
| 3 | `content` — UNIQUE(id,channel_id), (id,content_type), **(id,channel_id,content_type)**; FK (topic_id,channel_id)→topic; 상태·보관·활성 CHECK |
| 4 | `content_revision` — FK (content_id,channel_id)→content, (channel_id,channel_config_id)→channel_config; UNIQUE(content_id,revision_no),(content_id,id); 언어·길이(1~14400)·hash CHECK |
| 5 | `content_run` — FK (content_id,content_revision_id)→content_revision; FAKE 전용, 상태·시작·종료·취소·manifest CHECK |
| 6~8 | `content (id,current_revision_id)→content_revision(content_id,id)`, `content (id,active_run_id)→content_run(content_id,id)`, `content_revision (content_id,base_run_id)→content_run(content_id,id)` |
| 9 | `queue_command_log` — UNIQUE(request_key) utf8mb4_bin, 키·지문·command_type CHECK |
| 10 | 트리거(아래) |

FK는 모두 RESTRICT(테스트로 확인). utf8mb4_bin 컬럼: topic.normalized_hash, content_revision.revision_hash, content_run.output_manifest_hash, queue_command_log.request_key/request_fingerprint(MigrationConventionTest 허용 목록 갱신).

트리거(`SIGNAL 45000`):

| 테이블 | 규칙 |
|---|---|
| topic, content_revision, queue_command_log | UPDATE/DELETE 금지 |
| content_revision INSERT | base_run_id는 같은 콘텐츠의 SUCCEEDED run |
| content INSERT | DRAFT, 포인터 NULL로만 생성 |
| content UPDATE | ARCHIVED 이후 변경 금지; id·channel_id·topic_id·content_type·created_by·created_at 불변; current_revision_id NULL 복귀 금지; 활성 run 중 revision 포인터 변경 금지; title = 현재 revision working_title(BINARY); 새 active_run_id는 같은 콘텐츠의 QUEUED/RUNNING run |
| content, content_run DELETE | 금지 |
| content_run INSERT | QUEUED, 취소·fail-fast 없음 |
| content_run UPDATE | 종료(SUCCEEDED/FAILED/CANCELLED) 후 변경 금지; 식별 컬럼 불변; QUEUED 복귀 금지; 취소 의도 1회 기록 후 불변; **활성 포인터가 가리키는 동안 종료 전이 금지** |

**설계 초안 대비 추가한 DB 방어(검토 요청)** — 아래는 최종 명세의 계약("current_revision_id는 같은 콘텐츠 revision, active_run_id는 같은 콘텐츠의 비종료 Run", "FAKE Run은 manifest 없음", "제목 복제본", "NULL 우회 거절")을 DB에서 강제하려고 초안 트리거 목록에 더한 것이다. 계약 자체를 바꾸지는 않지만 리뷰에서 확인이 필요하다.
1. `ck_run_fake_manifest`: FAKE면 output_manifest_hash NULL.
2. content INSERT/UPDATE 트리거의 포인터·제목·활성 run 검사, content_run INSERT/UPDATE 트리거의 QUEUED 생성·복귀 금지·취소 의도 불변·포인터 해제 선행.
3. 결과로 **T03-C의 R10은 같은 TX에서 content 갱신(active_run_id NULL, DRAFT) → content_run 종료 순서**를 따라야 한다(잠금 순서 content → content_run과 일치).

## 5. 기존 DB 호환성 검증

- **신규 DB**: DatabaseBootstrapIT — V1~V6 적용, B 테이블 존재·C/D 테이블 부재, bin 컬럼 목록 정확 일치.
- **V1 → V2 → 최신**: DatabaseUpgradeIT — 이력 1~6, uk_channel_config_owner 존재.
- **V5(T02 실데이터) → V6**: ContentUpgradeIT — 앱을 `spring.flyway.target=5`로 띄워 T02 서비스로 채널·설정 revision 2·pause/resume·정책·환율·지출 허용·USD/KRW 예약·확정·원장·감사를 만든 뒤 migration 계정으로 V6 적용. (1) V1~V5 이력·체크섬 불변, (2) T02 13개 테이블 `CHECKSUM TABLE … EXTENDED` 동일, (3) 기존 제약·FK·CHECK·트리거 보존, 추가된 것은 `uk_channel_config_owner` 1건뿐, (4) 정합성 검사 이슈 0, (5) 업그레이드된 기존 채널·설정(revision 2)으로 topic·content 생성, 채널 보관 정상.
- **MigrationConventionTest**: V1~V5 SHA-256 불변, V6 등록(`4fa717cc…0c9b`), collation 규칙.

## 6. API 구현 범위 및 Codex 계약 반영 요청

구현 경로(T03 OpenAPI 초안의 B 범위): `GET/POST /api/v1/topics`, `GET /api/v1/topics/{topicId}`, `GET/POST /api/v1/contents`, `GET /api/v1/contents/{contentId}`, `POST /api/v1/contents/{contentId}/archive`, `GET/POST /api/v1/contents/{contentId}/revisions`, `GET /api/v1/contents/{contentId}/revisions/{revisionNo}`. 생성 201(+Location), 같은 키 재생 200. ADMIN 세션·CSRF는 기존 SecurityConfiguration(`/api/**` ROLE_ADMIN)에 그대로 적용, 19개 공통 오류 코드 무변경, 문자열 BIGINT ID, UTC ISO 8601.

FV-05 검증 방법: 테스트 전용 `T03ContractOverlay`가 제품 `docs/api/openapi.yaml`에 초안 B 경로·스키마를 메모리에서 합친 사본으로 ContentApiIT의 모든 요청·응답을 검증한다(파일은 수정하지 않음). 검증기가 위반을 실제로 거부하는지 T03ContractOverlayTest로 확인했다.

**Codex 통합 담당자 반영 요청**

| # | 항목 | 내용 |
|---|---|---|
| A1 | B 경로 병합 | 위 7개 경로와 Topic/TopicCreate/TopicPage/Content/ContentCreate/ContentPage/Revision/RevisionInput/RevisionPage/VersionRequest를 제품 계약에 병합하고 생성 타입 재생성 |
| A2 | `Content.budgetSummary` | 초안에서 required지만 T03-D(비용) 소유라 **B 응답에 없음**. D 구현 전까지 optional로 두거나 D가 채운 뒤 required 유지 중 결정 필요. 테스트 사본은 이 1건만 required에서 제외 |
| A3 | 채널 archive 409 | 제품 `POST /channels/{id}/archive`에 활성 run 보유 시 409 INVALID_STATE_TRANSITION(FD-16) 설명 추가 |
| A4 | 오류 상세 | topic 중복 `details[0]={field:"title",reason:"DUPLICATE_TOPIC:<id>"}`, revision 무변경 `details[0]={field:null,reason:"NO_CHANGES"}` 명시 |
| A5 | 기본값·검증 | ContentCreate.language 생략 시 채널 언어, channelConfigRevisionNo 생략 시 최신·지정 시 최신과 불일치 409, targetDurationSeconds는 고정 설정의 유형별 범위(범위 밖 400 VALIDATION_FAILED), settings는 객체·정수만·정규화 16KB 이하(허용 키 목록은 명세 없음, §12) |
| A6 | 상태 오류 | revision 생성: PRODUCING/ARCHIVED 콘텐츠 409 INVALID_STATE_TRANSITION, 보관 채널 콘텐츠 409 PREREQUISITE_NOT_MET; 다른 채널 topic으로 콘텐츠 생성 404 |
| A7 | Run 경로 | `/contents/{id}/runs`, `/runs/**`, `/jobs/**`, `/queue/**`는 B 미구현(C 범위) |

## 7. 단위 테스트 실제 결과

`gradlew.bat :backend:test` → **BUILD SUCCESSFUL, 21 suites / 147 tests, 실패 0, 오류 0, 건너뜀 0.** 신규: TopicNormalizerTest(3), CanonicalJsonTest(5), RevisionHashTest(3), T03ContractOverlayTest(3). MigrationConventionTest(V6 등록·V1~V5 체크섬) 포함.

## 8. 통합 테스트 실제 결과

`gradlew.bat :backend:integrationTest` → **BUILD SUCCESSFUL, 18 suites / 132 tests, 실패 0, 오류 0, 건너뜀 0** (1분 12초).

| Suite | 결과 | 범위 |
|---|---|---|
| ContentSchemaIT | 12/12 | 키·FK 구조, 교차 채널·교차 콘텐츠 소유권 거절, NULL 우회 거절, 불변 트리거, owner 컬럼·보관 종료, 제목 동기화, 활성 포인터, run 수명주기·FAKE·종료 불변, base run, topic 채널별 UNIQUE, CHECK |
| ContentApiIT | 11/11 | 401/403(CSRF), Idempotency-Key 누락·형식 400, topic 재생·다른 본문 409·정규화 중복 409·타 채널 허용, 보관 채널, 콘텐츠 생성 원자성(중간 실패 주입 시 잔여 행 0, 같은 키 재시도 성공), 소유권·입력, 목록·페이지·LIKE 이스케이프, revision 포인터·baseRevisionNo·NO_CHANGES·settings 키 순서, base run, PRODUCING/ARCHIVED 전이, FD-16 |
| ContentConcurrencyIT | 11/11 | §9 |
| ContentUpgradeIT | 1/1 | §5 |
| 기존 T01/T02 회귀 | 97/97 | ActuatorExposure 13, BudgetConcurrency 21, BudgetFailClosed 1, BudgetLedger 18, BudgetTime 6, ChannelIT 6, DatabaseBootstrap 8, DatabaseUpgrade 1, ErrorDispatch 5, AdminBootstrap 2, AdminCli 1, AdminCliJar 1, AuthIT 12, ProductionGrants 2 |

**실행 환경(FV-06)**: Docker 미설치로 Testcontainers 대신 로컬 가이드의 **별도 임시 MariaDB 11.8.2**(127.0.0.1:3307, 세션 scratchpad 데이터 폴더, 임시 비밀번호, UTC·utf8mb4_unicode_ci·max_connections 300)를 `AI_STUDIO_IT_DB_*`로 지정했다. 테스트가 `ai_studio_test_*` DB를 만들었다. 3306 작업 DB(`ai_studio`)에는 접속·migration·데이터 입력을 하지 않았다.

## 9. 동시성 및 잠금 검증 결과

두 DB 커넥션(원시 JDBC READ COMMITTED 트랜잭션 + 서비스 스레드)과 CyclicBarrier를 사용했다. 대기 판정은 수면 추정이 아니라 `information_schema.innodb_trx`의 `LOCK WAIT`를 확인한 뒤 선행 TX를 커밋하는 방식이다.

| 시나리오 | 실측 결과 |
|---|---|
| 동일 Topic: A가 channel S + topic INSERT 보유 | B(서비스)가 UNIQUE(channel_id,normalized_hash)에서 LOCK WAIT → A 커밋 후 409 DUPLICATE_RESOURCE(A의 ID). 행 1개 |
| 동일 Topic barrier 4요청 × 5회 | 매회 생성 1, 나머지는 재생 또는 DUPLICATE_RESOURCE, 행 1개 |
| Revision: A가 channel S → content X + revision 2 보유 | B가 content X에서 LOCK WAIT → A 커밋 후 409 RESOURCE_CONFLICT, revision_no 최대 2, 제목은 A 값 |
| 같은 baseRevisionNo barrier 2요청 × 5회 | 매회 201 1건·409 1건, revision_no 1,2 연속, 제목 = 현재 revision |
| 같은 키 revision barrier | 한쪽 신규, 한쪽 재생, 같은 revision ID |
| 활성 Run 포인터 경쟁 barrier × 5회 | 조건부 UPDATE(`active_run_id IS NULL`)로 승자 1, 패자 롤백, run 1건·PRODUCING 1 |
| 같은 run_no 동시 INSERT | B가 uk_content_run_no에서 LOCK WAIT → A 커밋 후 중복 오류 |
| 채널 보관 ↔ 활성화: A가 channel S → content X로 PRODUCING 설정 보유 | archive가 budget_control X 획득 후 channel X에서 LOCK WAIT → A 커밋 후 409 INVALID_STATE_TRANSITION, 채널 ACTIVE 유지 |
| 콘텐츠 작성 ↔ 채널 보관: B가 control X → channel X로 ARCHIVED 보유 | 콘텐츠 생성·revision 생성 모두 channel S에서 LOCK WAIT → 커밋 후 409 PREREQUISITE_NOT_MET, 행 증가 0 |
| 채널 pause ↔ 예약 경로 control S | pause가 budget_control X에서 LOCK WAIT → 해제 후 성공 |
| 혼합 부하(4 스레드 revision + topic, 10회) | 교착·잠금 시간초과 0, 콘텐츠별 revision_no 연속, 제목 동기화 |

명시 잠금 순서: topic/content 생성 channel S → (FK S); revision·content 보관 channel S → content X; 채널 상태 변경 budget_control X → channel X. 역순 획득 경로는 없다.

**미검증(실측하지 않음)**: EXPLAIN·secondary index 경유 암묵 락의 상세 계측(A5-03 전체), C/D 경로(queue_control, operation, 예산 TX)와의 교차 매트릭스(FV-03 후속), 실제 Run 생성 코드 경로(C 미구현 — 위 Run 시나리오는 Q0 계약 순서를 SQL로 재현).

## 10. 운영 권한 및 불변성 검증

`prod-app-grants.sql`: topic·content_revision·queue_command_log **SELECT, INSERT**; content·content_run **SELECT, INSERT, UPDATE**. DELETE·DDL 없음, channel_config 권한 무변경. ProductionGrantsIT(2/2)가 템플릿을 그대로 적용한 앱 계정으로 (1) topic·content·revision 생성과 콘텐츠·채널 보관을 수행하고, (2) 모든 테이블 DELETE/TRUNCATE/DROP/ALTER, 불변 테이블(신규 3개 포함) UPDATE 거부를 확인했다. 트리거 이중 방어는 ContentSchemaIT에서 확인. **binlog ON 환경의 트리거 생성 권한(ADR-050, FV-02 후속)은 임시 서버 log_bin=OFF라 미검증.**

## 11. 빌드 결과

`gradlew.bat :backend:bootJar` → BUILD SUCCESSFUL, `backend/build/libs/backend-0.1.0-SNAPSHOT.jar`(33,876,256 B). integrationTest의 AdminCliJarIT가 이 jar로 실행 경로를 확인했다. 비밀정보 검사: gitleaks 미설치라 변경 파일 전체에 키워드·토큰 패턴(AKIA/ghp_/sk-/xox, password/secret/token/api key 대입)과 임시 DB 비밀번호 문자열 검색 → 0건.

## 12. 미실행 검증 및 잔여 위험

- **CI/Testcontainers(Linux, MariaDB 11.8.8) 미실행** — 로컬 11.8.2 임시 서버로만 실행. PR HEAD CI가 필요하다.
- binlog ON 트리거 생성·권한 실측 미실행(FV-02 후속).
- 성능(PERF 1k/10k/100k) 미실행. 콘텐츠 목록 q 검색은 `title LIKE '%q%'`로 인덱스를 쓰지 않는다.
- OpenAPI 병합·생성 타입·frontend는 범위 밖(A1~A7).
- **명세 공백으로 구현에서 정한 사항(리뷰 확인 요청)**: (1) settings 허용 키 목록이 명세에 없어 객체·정수·16KB만 강제, (2) topic hash는 문서 그대로 `version` 문자열과 정규화 제목을 구분자 없이 연결(버전 두 자리 이상 시 이론상 모호 — 현재 v1만 사용), (3) 보관 채널 콘텐츠의 revision 생성은 409 PREREQUISITE_NOT_MET, 콘텐츠 보관은 허용, (4) 다른 채널 topic 지정은 404, (5) topic/content/revision/명령 로그 시각은 A3 §3 규칙대로 DB `UTC_TIMESTAMP(6)`, 채널·예산은 기존 앱 Clock, (6) 정규화·hash 유틸은 예산 코드 변경 금지에 따라 `budget/Fingerprints`를 옮기지 않고 `content/command/CanonicalJson`에 새로 두었다(shared 이동은 통합 담당자 조율).
- §4의 추가 트리거가 C의 R10 구현 순서를 제약한다(content 포인터 해제 → run 종료).
- 채널 pause/resume의 budget_control S → X 변경으로 채널 상태 변경이 진행 중 예약·정산과 직렬화된다(명세 반영, T02 회귀 테스트 전체 통과).

## 13. git diff --stat

추적 파일(`git diff --stat`):

```
 .../java/com/aistudio/channel/ChannelMapper.java   |  4 +++
 .../java/com/aistudio/channel/ChannelService.java  | 30 +++++++++++++++++++---
 .../java/com/aistudio/DatabaseBootstrapIT.java     | 10 +++++---
 .../test/java/com/aistudio/DatabaseUpgradeIT.java  |  9 ++++++-
 .../com/aistudio/security/ProductionGrantsIT.java  | 25 ++++++++++++++++--
 .../shared/db/MigrationConventionTest.java         | 11 ++++++--
 infra/mariadb/prod-app-grants.sql                  |  7 +++++
 7 files changed, 85 insertions(+), 11 deletions(-)
```

신규 파일(미추적, 줄 수): 제품 1,598줄(V6 284, ContentService 357, ContentMapper 187, ContentController 162, TopicService 132, IdempotentCommands 100, CanonicalJson 90, TopicController 80, TopicMapper 76, RevisionHash 36, TopicNormalizer 30, CommandLogMapper 30, Content 16, ContentRevision 10, Topic 8), 테스트 2,026줄(ContentConcurrencyIT 526, ContentApiIT 486, ContentSchemaIT 358, ContentUpgradeIT 170, ContentFixtures 146, T03ContractOverlay 120, T03ContractOverlayTest 68, CanonicalJsonTest 62, RevisionHashTest 50, TopicNormalizerTest 40), 이 보고서.

## 14. T03-C/D에 제공할 참조 키와 인터페이스

| 제공 키(V6) | 용도 |
|---|---|
| `content(id,channel_id)` uk_content_channel, `content(id,content_type)` uk_content_type, `content(id,channel_id,content_type)` uk_content_channel_type | D의 budget_bucket/reservation 3열 소유 FK, C의 automation_job 비정규화 FK |
| `content_revision(content_id,id)` uk_content_revision_owner | run → revision, 승인 대상 |
| `content_run(content_id,id)` uk_content_run_owner | automation_job → run, content.active_run_id |
| `channel_config(channel_id,id)` uk_channel_config_owner | revision 설정 소유 |
| `topic(id,channel_id)` uk_topic_owner | content → topic |

인터페이스·규약:
- `queue_command_log` + `IdempotentCommands.execute(Command, effect, onDuplicate)`: C의 CREATE_RUN/CANCEL_RUN/RESUME_JOB이 그대로 사용 가능(command_type CHECK에 이미 포함). 효과와 로그가 한 최상위 READ COMMITTED TX.
- Run 생성(C, Q0): channel S → queue_control X → content X → `INSERT content_run(status='QUEUED', execution_mode='FAKE')` → `UPDATE content SET status='PRODUCING', active_run_id=? … WHERE active_run_id IS NULL`.
- R10(C): 같은 TX에서 **content 먼저**(`status='DRAFT', active_run_id=NULL`) → content_run 종료. 순서를 바꾸면 트리거가 거부한다.
- 채널 보관 FD-16은 `content.active_run_id IS NOT NULL` 기준(`ChannelMapper.countActiveRuns`). C가 활성 포인터를 계약대로 유지하면 추가 연동이 필요 없다.
- `ChannelService.lockShared`(channel S), `latestConfigInTransaction`(MANDATORY), `TopicService.findInChannel`(MANDATORY), `ContentService.get/list`가 공개 API다. D의 `ContentDetail.budgetSummary`는 ContentController 응답에 필드 추가가 필요하다(A2).

## 15. 다음 담당자 인수인계

- **상태**: T03-B IMPLEMENTED. 커밋·푸시·PR 없음 — 사용자 승인 후 `t03/b-domain`에 커밋한다. STATE.md·TASKS.md·DECISIONS.md는 병렬 작업 규칙에 따라 통합 담당자(Codex)가 갱신한다.
- **DB migration 적용 상태**: V6은 격리 테스트 DB(`ai_studio_test_*`, 임시 서버 3307)에만 적용했다. 사용자 `ai_studio` 개발 DB에는 적용하지 않았다.
- **재현 명령**(저장소 루트, Windows): `gradlew.bat :backend:test`, `gradlew.bat :backend:integrationTest`(Docker 또는 `AI_STUDIO_IT_DB_HOST/PORT/USER/PASSWORD`로 별도 임시 MariaDB, [로컬 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md)), `gradlew.bat :backend:bootJar`. Linux는 `./gradlew`. 임시 서버를 재사용하면 이전 `ai_studio_test_*` DB와 PID가 겹칠 수 있으니 실행 전 정리한다.
- **Codex 다음 작업**: (1) 이 변경의 독립 리뷰(특히 §4 추가 트리거, §12 명세 공백 결정), (2) OpenAPI·생성 타입 병합(A1~A7), (3) PR HEAD CI(Testcontainers 11.8.8) 실행, (4) binlog ON 트리거 실측 계획.
- **T03-C 착수 조건**: 사용자 승인. B의 §14 키·R10 순서를 전제로 V7(queue_worker_instance → job_attempt 순서, FV-01)을 Codex와 번호 조율 후 작성.

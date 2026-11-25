# T03-B-FIX-RV 표적 재리뷰

2026-11-25 KST · Codex · 권고 **CONDITIONAL_APPROVE**

## 기준 및 범위

- 사용자 재확인 요청으로 fetch 후 `origin/t03/b-domain`과 [Draft PR #3](https://github.com/Hjinsu/ai-studio/pull/3)의 동일 HEAD `b5ffc73d61e100dbf4d9a847360aea404efd8e7d`를 고정했다. PR base는 `t03/integration`이다.
- 비교는 `a2500a8a6bf63dda701dc75bead51a390e8f43b7..b5ffc73`. 설계 기준 `d495296a85c0c312807a06c6fd24aa19b1f4872f`와 ADR-067~076, T03 최종 명세, B 원보고·수정보고, 기존 Codex 리뷰를 대조했다. detached worktree에서 실행했다.
- 범위는 RV-B-01~06 및 회귀다. 제품·V6·실제 OpenAPI·생성 타입·CI 설정 파일을 수정하지 않았다. 임시 DB 두 곳(127.0.0.1:43317/43318)만 사용했다. 사용자 개발 DB 접근 없음.

## 결함별 판정

| ID | 판정 | 근거와 잔여 조건 |
|---|---|---|
| RV-B-01 | 이중 비종료 및 직접 교체 해결, 고아 한 건 제한 명시 | V6:121–125의 PERSISTENT generated nonterminal_content_id UNIQUE가 QUEUED/RUNNING을 동일 content_id로 묶고 terminal은 NULL로 둔다. V6:236–259의 트리거가 활성 포인터 직접 교체와 고아 상태에서 archive/current revision 변경을 거절한다. ChannelMapper.java:88–96과 ContentService.java:166–171,204–215,352–366의 API 가드도 비종료 Run을 확인한다. |
| RV-B-02 | B DB 계약 충족, C 실제 구현 검증 대기 | ContentSchemaIT.java:476–560에서 포인터 해제 후 manifest CHECK 오류와 UPDATE 0행 예외 둘 다 롤백한다. ContentConcurrencyIT.java:394–567에서 UNIQUE 대기·해제, 좁은 락, isolation 차이, 종료와 신규 생성 직렬화를 검증한다. 실제 Q0/R10 서비스가 없는 B에서는 DB 계약 테스트까지다. |
| RV-B-03 | 해결 | TopicNormalizer.java:27–36의 canonical JSON [version,title] 프레이밍으로 기존 (1,2topic)/(12,topic) preimage 충돌을 제거했다. TopicNormalizerTest의 버전 경계·이스케이프 검증. 영속 V1 데이터 부재는 FIX_REPORT §1의 사용자 승인 읽기 전용 개발 DB 조회 및 격리 테스트 적용처 기록을 근거로 삼았다. 이번 리뷰에서 개발 DB를 조회하지 않았다. |
| RV-B-04 | 테스트 보완 해결, 배포 게이트 유지 | ProductionGrantsIT.java:210–269에서 binlog OFF 1142/42000, ON 1419/HY000을 구분한다. 후자는 격리 root로 trust를 전환해 앱의 실제 TRIGGER 권한 거절 1142를 별도로 확인하고 설정을 복원한다. checksum·스키마·트리거 재조회에서 부작용 0과 권한 목록의 SUPER/TRIGGER/DDL 부재를 검증한다. 운영 최소 권한 migration 계정/DBA 절차는 배포 게이트다. |
| RV-B-05 | 구현과 시험 보완, 실제 계약 문서 반영 대기 | 요청값 기준 지문을 유지한다. JSON 키 순서/포맷 차이에 같은 응답, trim/기본값 적용으로 저장 결과만 같은 다른 요청은 409. Texts 및 CanonicalJson의 surrogate 검사, ContentApiIT.java:527– 및 canonical settings 16KiB 경계 테스트를 확인했다. budget 및 RevisionHash.java 변경 없음. |
| RV-B-06 | 해결 | AdminCliJarIT.java:66 이하에서 DEBUG/TRACE/SPRING/LOGGING/AI_STUDIO/JVM 설정을 자식 환경에서 격리하고 필요한 격리 DB 설정을 부여한다. 상속 환경 제거 단위 테스트와 bootJar CLI 비웹·콘솔 없음 회귀를 유지한다. |

## 직접 SQL 재현과 잠금

동일 SHA의 `ContentSchemaIT`와 `ContentConcurrencyIT`를 독립 실행했다. 기존 리뷰의 직접 재현과 동일한 INSERT/UPDATE 경로를 대상 코드의 검증 SQL로 다시 확인했다.

- `twoQueuedRunsForTheSameContentAreRejected`, `queuedAndRunningCannotCoexist`: 두 번째 INSERT는 uk_content_run_nonterminal에서 거절되고 nonterminal 1개다. `activePointerCannotBeSwappedDirectly`: 두 번째 활성 후보 INSERT를 UNIQUE로 차단하며, 해제 없는 다른 ID 직접 UPDATE도 트리거로 거절한다. SQL을 통해 terminal 이력을 여러 건 보존하고 종료 후 신규 Run 및 다른 콘텐츠의 독립 Run 생성을 확인했다.
- `orphanRunBlocksContentArchiveAndRevisionChangeAtTheDatabase`는 직접 보관 SQL과 current revision 포인터 변경을 막는다. 불변 revision 행 자체에 대한 fixture INSERT와 current 포인터 전환은 별개다. B의 실제 HTTP 보관·revision 명령은 409를 반환한다. `orphanRunBlocksChannelArchive`에서 고아 행이 있는 채널의 409도 확인한다. 자동 수정·삭제 없이 고아가 그대로 남는다.
- 신규 경쟁 검증은 두 연결의 lock-wait와 정해진 트랜잭션 순서를 사용한다. 동일 콘텐츠에서 UNIQUE 대기→선행 커밋 시 실패, 선행 롤백 뒤 신규 생성 가능. Run INSERT의 revision FK 및 트리거 조회에 대해 READ COMMITTED의 다른 콘텐츠 진행을 확인하고 REPEATABLE READ의 gap 락을 별도로 재현한다. 채널 보관↔Run 활성화 경쟁과 content X→Run 종료/다음 생성도 검증한다. B 검증을 근거로 C/D 전체 락이 안전하다고 판단하지 않는다.
- R10은 SQL 오류, 예상 0행을 예외로 승격한 두 케이스에서 rollback 후 PRODUCING/기존 활성 포인터/RUNNING을 검증한다. 결과는 B DB 계약 증거다.

### 한계 및 신규 회귀

정상 B API에 신규 차단 결함을 발견하지 않았다. **포인터 없는 비종료 Run 1건은 직접 SQL 또는 잘못된 writer TX로 남을 수 있다.** 즉시 제약만으로 INSERT→포인터 갱신의 순환 관계를 커밋 시 검사할 수 없다는 설계 한계다. `content_run` 직접 INSERT가 content 상태를 강제하지 않으므로 보관·revision 가드만으로 C의 Q0를 대체할 수 없다. 보관 경합 검증은 채널/콘텐츠 선행 잠금을 지키는 writer에 적용된다. 트리거에 content 조회를 임의로 복원하면 수정 보고서에 실측된 S→X 변환 교착 위험이 돌아온다.

## V6 승인 예외 및 검증

미출시 Migration의 사용자 승인 일회성 예외다. 이전 SHA의 V6 LF 정규화 SHA-256 `4fa717cc451bcd0593bf8eb0c3d9ce7b1a2954b912398939d2086db4f0780c9b`, 수정 파일 `304900fda20821c83a9f3f3a56c9568f4215544158020569bd6f36e9d2412588`. 실제 수정 파일과 MigrationConventionTest.java:39 등록이 일치했다. V1~V5 Git diff 0 및 해시 테스트 포함. 신규 DB는 DatabaseBootstrapIT, 실제 T02 데이터 보존은 ContentUpgradeIT로 확인했다. 영속 환경에 V6이 적용된 후 변경은 신규 migration으로 처리한다.

## OpenAPI A1~A8

| 요청 | 판정 |
|---|---|
| A1 | B 경로/스키마를 현행 제품 계약에 통합하는 범위 적절. overlay 결과와 실제 반영을 구분 |
| A2 | D 전 budgetSummary optional, D의 모든 read 응답 확인 후 required 전환 |
| A3 | 활성 포인터와 실제 비종료 Run 모두 채널 보관 409에 해당함을 문서화 |
| A4 | DUPLICATE_TOPIC 및 NO_CHANGES 오류 세부 계약 적용 |
| A5 | 채널 언어, 설정 revision, 기간, 객체·정수 settings와 canonical UTF-8 16KiB 설정 유지 |
| A6 | 보관·Revision 상태 409 및 교차 소유 404 명시 |
| A7 | Run API는 C에서 반영. B 리뷰 승인 범위에 포함하지 않음 |
| A8 | 요청값 기준 멱등 지문, 코드포인트 길이 및 잘못된 surrogate 400 명시 |

## 독립 실행과 CI

| 독립 검증 | 결과 |
|---|---|
| Windows backend:test + bootJar | 캐시 없는 재실행으로 단위 151/151, skip 0, 실제 bootJar 생성 성공 |
| MariaDB 11.8.2 binlog OFF (43317) | backend:integrationTest 153/153, skip 0, exit 0. 신규 DB, V5 업그레이드, 직접 SQL·API·동시성 포함 |
| MariaDB 11.8.2 binlog ON (43318, trust=0 시작) | backend:integrationTest 153/153, skip 0, exit 0. 1419 및 1142 거절과 설정 복원·부작용 0 검증 |
| [GitHub Actions 45634311996](https://github.com/Hjinsu/ai-studio/actions/runs/45634311996) | PR #3 수정 HEAD와 일치. 7/7 성공, Linux MariaDB 11.8.8 Testcontainers 통합·bootJar·이미지·Compose 확인, Windows 백엔드, 프론트 Linux/Windows·Edge, 기존 OpenAPI/타입, gitleaks 성공 |

Gradle에 `--rerun-tasks --no-build-cache`를 사용하고 로컬 실행에 DEBUG=true를 상속시켰다. 최초 PowerShell 래퍼가 컴파일러 stderr 경고를 terminating error로 다룬 호출은 테스트 결과에서 제외했다. 외부 Gradle 프로세스의 종료 코드를 직접 확인하도록 래퍼를 고친 뒤 위 결과를 얻었다. CI의 개별 테스트 숫자는 API만으로 확인하지 않았다. 최초 PR run이나 기존 SHA의 성적을 재사용하지 않았다. `git diff --check a2500a8 HEAD` 통과.

## C 인수 조건 및 결론

- Q0는 최상위 READ COMMITTED에서 channel S→queue_control X→content X를 먼저 얻고 상태/고아/중복을 검사한 뒤 INSERT→포인터 갱신을 한 커밋으로 묶는다. UPDATE 영향 1행 필수, 실패는 전체 롤백.
- R10은 content X→content_run X 및 후속 정순으로 진행한다. 종료 전제와 P12를 확인하고 포인터 해제→Run 종료 순서로 같은 TX에 넣는다. 두 UPDATE 각각 영향 1행 및 예외 전파, 실제 서비스의 오류·0행·감사/커밋 직전 장애 주입이 필수다.
- 기동·claim·reconciler가 active_run_id↔비종료 행 불일치를 감지하면 경고와 fail closed. 자동 삭제·보정 금지. C 실코드의 복합 FK·UNIQUE·트리거 역순 락 방지와 실제 Job 카운터를 별도 검증한다.
- 운영 binlog ON의 migration 계정/DBA 절차, OpenAPI/타입 A1~A8 통합, T03-C 실행 경로 검증은 잔여 게이트다. 이번 결과는 수정본 리뷰에 대한 조건부 승인이다. 브랜치 병합, DONE 전환 및 C 착수는 사용자 별도 승인에 따른다.

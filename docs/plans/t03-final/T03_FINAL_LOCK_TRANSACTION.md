# T03 잠금 및 트랜잭션

전역 순서 **budget_control → reservation_intent → channel → budget_policy → budget_bucket → budget_reservation → queue_control → content → content_run → automation_job → job_operation → attempt / dispatch / receipt / event**. S는 공유, X는 배타. 같은 종류는 기본 키 오름차순, 키가 문자열이면 bin 순서. 후보 집합은 비잠금으로 찾고 PK 순서로 잠근 뒤 다시 검증한다. 쿼리 ORDER BY 자체를 잠금 순서 증거로 삼지 않는다. 경로별 필요 부분집합만 사용한다. FK가 요구하는 최종 모드부터 얻고 S→X 변환 및 큐→예산 역진입을 막는다.

| TX | 읽기·쓰기 | 명시 순서 및 선행 FK/UNIQUE 검사 | 커밋 |
|---|---|---|---|
| Q0 | 채널/설정/topic·revision·Run·graph, 명령 로그 | channel S → queue_control X(큐 변경 시) → content X → 기존 run/job. topic/config/actor 같은 불변 부모의 존재·소유를 검증. content 키, active pointer, request_key 및 DAG 키 INSERT는 부모 확보 후 | 같은 콘텐츠에 활성 Run 하나, 명령 로그와 효과 한 커밋 |
| Q1 | claim·attempt·lease 및 slot | channel S → queue_control X → content S → run X → job X → attempt INSERT. worker 부모 FK S, (job,attempt_no) 및 token UNIQUE. 현재 attempt FK는 신규 attempt INSERT 후 | 유효 Lease ≤2. claim당 attempt 1 |
| Q2 | heartbeat·sweep·취소·완료·R10 | 필요한 channel → queue_control → content → run → job → operation → attempt/event. 상태를 바꾸는 경로 X, 참조 S. 하위 락으로 시작하는 heartbeat는 상위 락으로 올라가지 않는다 | fencing 및 카운터·취소·집계 일관. 비용 해소 호출은 별도 |
| TX1 | operation 및 키 | 큐 순서 → job X → 생성 attempt 부모 S → effect slot/seq/reserve key UNIQUE | PREPARED |
| TX1b | 의도 등록/VOID | control X(최초 INSERT) 또는 S(기존 행) → intent 삽입 슬롯 → 필요한 큐 부모 X/S를 정순 확보. operation FK는 **부모 확인 뒤** INSERT. 같은 키의 등록·VOID 경쟁은 기존 intent 행 X로 처리하고, 신규 행은 control X에서 재조회 후 INSERT. 잠긴 operation을 보유한 채 동일 intent 키를 새로 기다리는 경로는 금지 | OPEN 또는 VOID 하나 |
| TX2 | 정책·bucket·기대 집합·예약 | control S → intent X → channel S → 정책 X → 기존 bucket X → reservation 기존 재생 확인 → 콘텐츠 부모 S 및 operation 참조 검증. content FK가 있는 신규 bucket INSERT는 LIVE 부모와 **모든 잠길 기존 bucket** 확인 뒤. 신규 PK는 미공개 행, 유일 키 충돌 경로에서 역순 대기 금지. matched policy X 및 content S로 LIFETIME 생성 직렬화 | RESERVED, 예약·링크·RESERVE 이벤트 원자 |
| TX3 | operation–예약 연결 | control S → intent X → reservation S → 큐 부분집합 → operation X. 예약 소유·key/지문 일치 후 FK 연결 | 같은 예약에 일회 연결 |
| TX4 | 허가 또는 미송신 회수 판정 | control S → intent X → channel S → policy/bucket/reservation S(승인 상태에 필요한 부모) → queue_control → content → run → job X → operation X → current attempt/dispatch. toggle·cancel·fail-fast·deadline·lease를 재검증. dispatch UNIQUE(operation_id)와 발급 attempt 소유 FK를 검사 | DISPATCHING과 유일 dispatch 또는 CLOSE_REQUESTED/VOID |
| TX5 | 늦은 증거 접수 | immutable dispatch 부모 S → 증거 digest UNIQUE INSERT. 앞선 큐·비용 락 추가 요구 없음 | 불변 근거만 저장 |
| TX5b | 근거 채택·event 계획/attestation | 큐 순서 → job_operation X → 근거 확인 및 event intent/attestation INSERT. reservation/cost_log FK가 필요해지는 쓰기는 TX6으로 분리 | 채택 CAS, 확정 payload/seq |
| TX6 | 자동 정산 또는 RELEASE | control S → 필요한 intent X → bucket X → reservation X → operation X 및 event intent X → cost_log/receipt/audit. receipt의 ledger FK는 신규 ledger INSERT 후, 기존 재생은 원장 참조의 예약 소유 확인 | 금액·원장·receipt·의도 완료를 원자 저장 |
| TX7 | P12 및 Run 종료 | 사전 원장/receipt 읽기 → 큐 순서 → operation 버전·불변 receipt의 소유 확인 → job/event/run 집계. 비용 행을 쓰거나 잠그지 않는다 | 단일 큐 적용 및 조건부 R10 |
| M1 | 관리자 정산 의도 등록/취소 | reservation X → settlement_guard X → 기존 settlement_operation X. 생성은 새 operation INSERT 후 guard pointer. 같은 키 UNIQUE 경쟁 시 롤백·재조회. 이 경로는 앞 순위 락을 뒤에 취득하지 않는다 | 예약당 미완결 1개 또는 취소 |
| M2 | 관리자 정산 실행 | control S → bucket X → reservation X → settlement_guard X → settlement_operation X → ledger/receipt/audit. 새 ledger를 먼저 쓰며 확정 재생은 해당 ID의 receipt 사용 | 정산·receipt·guard 해제 원자 |
| CTRL | 정책·지출·채널·검사 | budget_control X. 채널 변경은 channel X를 다음에 취득. 활성 Run 확인은 같은 channel S를 요구하는 Run 생성과 직렬화. 정책·bucket 개정은 ID 순. 검사 시작/결과 게시 각각 control X | version, 결정 상태와 감사 일치 |

TX1b의 최초 삽입은 없는 행에 SELECT FOR UPDATE만으로 락을 보장할 수 없다. 동시 최초 INSERT와 부모 락의 순서가 상충할 수 있어 **신규 intent 생성 경로는 budget_control X**로 직렬화한다. 순서표의 기존 intent 재조회·쓰기에는 S를 사용할 수 있다. TX2의 선행 부모 및 bucket 생성은 실제 DDL의 InnoDB FK/UNIQUE 경로를 확인한다. 같은 policy X 아래 예상 bucket이 아직 없을 때는 기존 행의 락을 모두 얻고 콘텐츠 부모 S를 선취득한 뒤 쓰기를 시작한다. 다른 경로가 부모를 보유한 채 앞 단계로 진입하면 금지한다. 암묵 락 경로가 검증 결과 계약과 어긋나면 구현 착수 전에 SQL 순서를 다시 대조한다.

모든 예약/정산 진입점은 LockRetryingTransactions 최상위 READ COMMITTED. 교착/시간초과/기존 스냅샷 충돌은 전체 롤백 뒤 동일 키로 최대 3회 추가 재시도한다. 외부 요청은 DB TX 종료 후 실행한다. TX4 성공이 확정된 커밋만 일회성 송신 권한을 부여하며 조회로 재발급하지 않는다.

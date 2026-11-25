# T03 비용, 실행 허가 및 복구

SYSTEM DAY/MONTH 두 활성 정책 필수, 기존 CHANNEL DAY/MONTH 활성 정책 적용. 콘텐츠의 유형·채널을 서버가 job/content에서 검증하고 활성 전역 및 채널별 CONTENT_TYPE LIFETIME을 모두 적용한다. 활성 LIFETIME 0개는 PREREQUISITE_NOT_MET, 활성 0원은 BUDGET_EXCEEDED. 첫 성공 예약 커밋의 LIFETIME policy ID 집합을 영속 동결한다. RELEASE, 환불, revision/run 교체에도 유지한다. 해당 콘텐츠의 적용 집합이 바뀌는 생성·enable·disable을 control X에서 409로 차단한다. 금액 개정은 같은 bucket의 limit/revision만 변경하며 누적액·기존 링크 보존. TX4에서는 현재 한도보다 spent+reserved가 많으면 미허가 예약의 집행을 차단한다. 이미 발생한 비용, 지연 정산, 환불은 차단 중에도 처리한다. FX와 DECIMAL 및 V5 정산 수식을 유지한다.

예약 시각의 전체 정책 선택 증거는 scope별 적격 활성 정책 ID, 선택 목록의 수와 지문 및 당시 budget_control 정책 세대를 남긴다. 그 세대의 immutable 정책 상태 기록(정책 개정·활성/비활성 이벤트)을 함께 사용해 선택을 검증한다. 기대 링크와 실제 링크 양방향 anti-join, 동결 집합, SYSTEM 필수 개수, CHANNEL 선택 증거, 정책 revision·스냅샷 한도·소유권을 교차 확인한다. 현재 활성 정책을 과거에 소급하지 않는다. T02 v0는 현행 SQL 의미로 검사한다. 검사 RUNNING/FAILED/UNKNOWN/ERROR는 신규 예약과 TX4를 차단한다. 검사 결과가 확정되지 않으면 기존 ERROR를 유지한다. 세대 CAS로 늦은 검사 결과의 OK 덮어쓰기를 막는다. 부팅 시 중단 검사·미확정 intent를 진단한다. 1k/10k/100k p95 1/3/10초는 측정 후보이며 정산 지연 및 control X 보유 시간을 함께 측정한다.

논리 슬롯당 operation 한 개. immutable payload·fingerprint·schema version, event_seq 및 결정 시각을 영속화한다. 생성 retry는 기존 슬롯과 request key를 재사용한다. 키 예시는 job-budget:v1:<uuid>, job-settle:v1:<uuid>:<seq>, admin-settle:v1:<uuid>. 내부 reserve:/reserve-v2:는 유지한다. 동일 키 다른 payload 409 RESOURCE_CONFLICT. 정규화에서 binary 키, UTF-8, 명시 null, 문자열 금액, 서버 소유 ID를 고정한다. 새 attempt가 새 효과를 발급하지 않는다.

실행 허가는 TX4의 DISPATCHING 및 UNIQUE dispatch 성공 커밋이다. 허가 이전 안전 실패만 같은 operation으로 재시도한다. 허가 이후 NOT_EXECUTED 판정도 두 번째 dispatch를 허용하지 않는다. 후속 실행은 이전 정산 종결 및 사용자 신규 Run 요청이 필요하다. 조회 없음은 실패·미송신 증거가 아니다. 성공이 확인된 허가 커밋을 보유한 실행자만 송신할 수 있다. 그 이전에 허가된 송신이 차단 커밋 이후 발생할 수 있다. 실제 공급자 멱등·조회·drain·강한 중단 조건 및 상한은 T07 게이트다.

| 단계 | 커밋 직전 장애 | 직후 장애·응답 유실 |
|---|---|---|
| Q1 | claim 취소, 후보 재검색 | lease로 재조회, 만료 회수 전 상태 변경 금지 |
| TX1/TX1b | 같은 슬롯/키로 재개 | 영속 operation/OPEN 또는 VOID 채택 |
| TX2 | 락 해제 및 롤백 확인 후 같은 키 | RESERVED에서 같은 예약·RESERVE를 채택 |
| TX3 | intent를 확인하여 다시 연결 | 동일 예약 연결 유지 |
| TX4 | 미커밋과 유효 owner가 입증될 때만 재평가 | dispatch 존재 시 재발급 금지. 송신 전 crash도 불명 |
| TX5/TX5b | 같은 digest로 재접수·채택 재검증 | 불변 증거와 event 계획을 재생, 상충 격리 |
| TX6 | 동일 payload/키로 재개 | ledger·불변 receipt를 읽어 P12 재생 |
| TX7 | receipt 조회 후 재적용 | 큐 완료와 Run 종료는 반복 효과 0 |
| 회수 | 동일 intent X로 진행 예약과 경쟁 판정 | OPEN→VOID는 예약 0, 미허가 RESERVED→CLOSE_REQUESTED 후 고정 RELEASE로 RELEASED |
| M1/M2 | 같은 생성 키/operation ID 재조회 및 적용 | 동일 receipt, 별개 비용 효과 없음 |
| attestation/검사 게시 | 이전 불명·차단 유지 | 동일 판정 키·검사 세대로 조회 |

취소와 TX2/4는 intent 키로 직렬화한다. TTL은 후보 선택용이다. OPEN에 VOID tombstone, RESERVED에 미허가 입증 후 CLOSE_REQUESTED, TX6에서만 RELEASED. dispatch가 있거나 과금 불명인 예약은 자동 해제하지 않는다. 단계별 예산 재계획 503은 허가 전 롤백 확인 뒤 동일 키 재시도, 커밋 불명은 조회, 외부 불명은 RECOVERY_REQUIRED다. 만료 worker는 불변 evidence만 접수한다. 활성 Owner 또는 Reconciler가 같은 dispatch/attempt와 canonical digest를 확인하고 CAS 채택한다. 상충 증거는 격리한다.

관리자 attestation은 actor·사유·근거 hash/ref·DB 시각·idempotency key·이전 버전을 감사에 남긴다. 청구 CHARGED/NOT_CHARGED/UNKNOWN과 산출물 VERIFIED/FAILED/UNKNOWN을 별개로 기록한다. 큐 소유 미확정 건의 CONFIRM/RELEASE는 내부 event intent로만 수행한다. 일반 settlement_operation은 레거시 예약 또는 해소된 예약의 적격 별개 조정에 한정한다. TX6/M2 영수증은 기존·확정 금액과 상태·ledger event id/key를 보존한다.

CR-02는 차단 → 읽기 전용 차이·근거 수집 → 격리 재현 → 권한·해시·보정안 승인 → 승인 계정 보정 및 감사 → 전체 검사 → 별도 지출 변경 승인 순서다. 원장 UPDATE/DELETE·자동 보정 금지. 정당한 비용 정정은 추가 조정/환불 이벤트로만 기록한다. CR-01의 remoteAddr 및 운영 배포 전 신뢰 프록시 검증은 유지한다.

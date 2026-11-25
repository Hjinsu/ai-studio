# T03 계획

T03은 topic, 불변 입력 Revision, 산출물을 소유하는 Run, DB 작업 큐·lease, 콘텐츠 예산과 영속 복구, 관리자 UI를 제공한다. Revision/Run/manifest_hash 세 값이 T06 승인 대상이다. FAKE Run은 실제 승인·게시에서 제외한다. 콘텐츠당 활성 Run은 하나다. 승인 전 별도 개발 착수는 금지한다.

| FD | 구현 계약 |
|---|---|
| 01 | DB §소유권의 3열 UNIQUE/FK |
| 02 | LOCK의 비용→큐 순서 |
| 03 | LOCK의 부모·UNIQUE 선행 계획 |
| 04 | COST의 단일 dispatch |
| 05 | STATE의 R10 종료 가드 |
| 06 | API/COST의 영속 관리자 정산 |
| 07 | COST의 LIFETIME 동결 |
| 08 | Quartz JDBC T08 도입, T09 재사용 |
| 09 | 자동 실행 최초 포함 3회 |
| 10 | 명시적 관리자 재개 2회 |
| 11 | DB의 동시 유효 Lease 최대 2 |
| 12 | 하향 한도 초과의 미허가 예약까지 차단 |
| 13 | FAKE 전용 |
| 14 | 검사 미확정·RUNNING·FAILED·ERROR 차단 |
| 15 | 기존 API 규격과 신규 계약 검토 |
| 16 | 활성 Run 있는 채널 보관 409 |

Job DAG는 서버가 선택한 pipeline 버전에서 고정한다. 동일 Run FK, 자기 간선 금지, 순환 검증, fan-in 잠금과 재평가를 사용한다. EXPANDER/CHILDREN/BARRIER의 스키마·귀속·결정적 키와 원자적 확장 규칙을 보존한다. 실제 장면 확장은 T04~T05에 수행한다. Topic은 채널 내 정규화 중복 검사를 지원한다. Provider 상세 요청 로그는 실제 연동 단계의 별도 범위다.

Dispatcher ScheduledExecutorService(1초), 전용 Heartbeat(15초), 제한된 Worker Pool, Spring Scheduler Sweep(10초) 및 Reconciler. Lease 60초와 attempt 실행 deadline 적용. DB UTC로 큐 시각을 판정하고 예산 기간·환율은 기존 앱 Clock을 쓴다. 기동 시 중단된 검사와 미확정 operation을 진단한 뒤 dispatch 가능 여부를 평가한다. 실제 공급자, 지출 허용, 게시에는 별도 승인이 필요하다. Quartz JDBC JobStore는 T08에서 Flyway로 관리하며 자동 초기화 금지 원칙을 유지한다.

A5 차단 대응: 01 DB 소유 키, 02~03 LOCK 경로표, 04~05 STATE/COST 종료와 재발급, 06 API 전환, 07 COST 선택 증거, 08 FD-08, 09 API 변경 초안, 10 COST 검사/기동 및 TEST 실측. 통합 DDL과 스키마의 실행 검증 전까지 설계 반영 상태로 기록한다.

## A5 해결 위치와 남은 검증

| A5 | 반영한 계약 | 구현에서 확인할 조건 |
|---|---|---|
| 01 | DATABASE 3열 대체 키와 v0/v1 NULL 구분 | 타 채널·유형 입력이 FK/CHECK로 거절된다 |
| 02 | LOCK의 비용→큐 순서, 채널 상태 변경 control X | 신규 Run과 채널 보관, 허가와 지출 차단이 같은 경계에서 직렬화된다 |
| 03 | LOCK의 부모 선취득, 최초 intent 키 직렬화 | FK·UNIQUE 및 secondary index 경유 락을 계측하고 역순 대기가 없는지 확인한다 |
| 04 | STATE/COST의 허가 후 NOT_EXECUTED 처리 | 같은 operation의 새로운 dispatch는 0건이다 |
| 05 | STATE R10과 COST TX6/TX7 receipt | 미확정 비용이나 P12 미적용 시 활성 포인터 유지 |
| 06 | API 영속 정산과 attestation 분리 | 이전 쓰기 API는 409, 일반 정산으로 작업 큐를 우회하지 못한다 |
| 07 | DATABASE/COST 정책 세대 및 기대·실제·동결 검사 | 같은 정책을 기대·실제에서 함께 빼도 ERROR |
| 08 | FD-08 및 ADR-075 | T03 런타임의 Quartz JDBC 스키마 의존성 0 |
| 09 | API/OpenAPI 전환 초안 | 기존 규격으로 요청·응답과 생성 타입을 검증한다 |
| 10 | COST의 검사 상태/부팅 처리 | 중단된 검사 및 결과 미확정 상태에서 신규 허가 0, 기존 정산 유지 |

최종 검토에는 DB FK/UNIQUE 암묵 락, V5 업그레이드, 불변 트리거·운영 grant 초안, OpenAPI overlay 통합을 포함한다. 이 단계에서 실행한 검증은 Git diff·OpenAPI 참조·검증 항목 존재 확인이다. 물리 호환성, 테스트 통과, CI 성공은 확정하지 않았다. 명세 승인과 B의 조건부 착수 승인을 기록했다. 필수 검증은 인수인계의 FV-01~06을 따른다. C~F는 별도 승인 대기다.

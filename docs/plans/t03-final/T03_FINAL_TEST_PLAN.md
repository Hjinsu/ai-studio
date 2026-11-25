# T03 검증 계획 및 추적

아래는 계획이다. 구현, 격리 DB, 성능, 브라우저, CI는 미실행이다. 표의 문서 약칭은 이 디렉터리 T03_FINAL_*.md를 가리킨다.

| 그룹 | 합격 조건 |
|---|---|
| DB | 빈 DB 및 V5 fixture 업그레이드, V1~V5 해시·원장/환율/링크 동일. 타입·NULL·복합 FK 위반 거절, binlog ON 권한·트리거 검증 |
| LOCK | 두 세션 barrier, 실제 FK/UNIQUE 락 및 EXPLAIN, control/채널 변경과 허가 교차. 역순 접근 0, 교착은 전체 롤백 후 동일 키 |
| QUEUE | 동시 유효 lease ≤2, stale Worker 상태 쓰기 0, expiry/heartbeat/deadline, 최초 포함 자동 3 및 관리자 2, fan-in 승격 1 |
| RECOVERY | 각 Q1/TX1~7/M1~2 커밋 직전/직후 및 응답 유실. 동일 효과당 dispatch ≤1, 미해결 비용 terminal Run 0, 동일 receipt P12 효과 1 |
| BUDGET | 동결 정책 바꾸는 쓰기 409, 3열 소유 및 sentinel 검증, 정책·링크 두 집합 공동 누락도 ERROR. 하향 초과 미허가 차단 및 기존 비용 정산 |
| API | 서버 OpenAPI 요청/응답·생성 타입 diff, 문자열 액수 및 UTC. 기존 쓰기 409 및 원장 변화 0, 다중 탭 guard 1, replay receipt 불변 |
| UI | 라이트/다크/모바일, 상태·권한·세션 전환, 별도 의도 확인과 조회, fake 표시 및 실제 호출 0 |
| PERF | 격리 DB 1k/10k/100k p95 후보 1/3/10초와 lock hold, 실패 시 차단, CR-02 dry-run 원장 수정 0 |

| 항목 | 최종 명세 위치·계약 | 구현 검증 |
|---|---|---|
| CDR-01 | STATE WAITING/RECOVERY_REQUIRED | QUEUE/RECOVERY |
| CDR-02 | PLAN revision/run/manifest | 계약·관련 회귀 |
| CDR-03 | PLAN 확장 DAG, fan-in | QUEUE/RECOVERY |
| CDR-04 | STATE cancel_requested_at | 계약·관련 회귀 |
| CDR-05 | COST attestation | API/RECOVERY |
| CDR-06 | COST 정책 선택 | DB/BUDGET |
| CDR-07 | API Idempotency-Key | API/RECOVERY |
| CDR-08 | LOCK Q1/Q2·fan-in | QUEUE/RECOVERY |
| CDR-09 | DATABASE active_run_id | QUEUE/RECOVERY |
| CDR-10 | STATE 완료·attention | 계약·관련 회귀 |
| CDR-11 | DATABASE 복합 FK | DB/BUDGET |
| CDR-12 | PLAN Quartz | 계약·관련 회귀 |
| CDR-13 | LOCK DB Lease 상한 | QUEUE/RECOVERY |
| CDR-14 | STATE deadline | QUEUE/RECOVERY |
| CDR-15 | STATE 3+2 | QUEUE/RECOVERY |
| CDR-16 | PLAN DB UTC/Clock | DB/BUDGET |
| CDR-17 | PLAN topic 및 연기 항목 | 계약·관련 회귀 |
| CDR-18 | PLAN provider 상세 로그 | 계약·관련 회귀 |
| CDR-19 | DATABASE title 동기화 | 계약·관련 회귀 |
| CDR-20 | API run 취소/수동 재개 | API/RECOVERY |
| CDR-21 | DATABASE FIFO 인덱스 | 계약·관련 회귀 |
| CDR-22 | DATABASE 권한 및 트리거 | 계약·관련 회귀 |
| CDR-23 | DATABASE 언어·해시 | 계약·관련 회귀 |
| CDR-24 | API/UI 서버 pipeline 및 production | API/RECOVERY |
| CDR-25 | HANDOFF 배정·이력 | 계약·관련 회귀 |
| A2-01 | COST 기대 링크 검사 | DB/BUDGET |
| A2-02 | DATABASE 소유권/동결 | DB/BUDGET |
| A2-03 | LOCK TX2/4, COST 회수 | LOCK/RECOVERY |
| A2-04 | LOCK 큐 정순 및 COST 지연 증거 | QUEUE/RECOVERY |
| A2-05 | API 영속 정산 | API/RECOVERY |
| A2-06 | COST 키·순번 | API/RECOVERY |
| A2-07 | COST 검사와 성능 | DB/BUDGET |
| A2-08 | COST 오류 분류 | 계약·관련 회귀 |
| A5-01 | DATABASE 3열 FK | DB/BUDGET |
| A5-02 | LOCK 정순 | LOCK/RECOVERY |
| A5-03 | LOCK 암묵 락 실측 | LOCK/RECOVERY |
| A5-04 | STATE 허가 이후 재발급 금지 | QUEUE/RECOVERY |
| A5-05 | STATE R10/P12 | 계약·관련 회귀 |
| A5-06 | API 기존 쓰기 전환 | API/RECOVERY |
| A5-07 | COST 정책 세대 | DB/BUDGET |
| A5-08 | PLAN Quartz T08 | 계약·관련 회귀 |
| A5-09 | API/OpenAPI 초안 | API/RECOVERY |
| A5-10 | COST 검사 및 부팅 | QUEUE/RECOVERY |
| INT-01 | DATABASE operation 슬롯 | 계약·관련 회귀 |
| INT-02 | LOCK 순서 | LOCK/RECOVERY |
| INT-03 | LOCK TX4 | LOCK/RECOVERY |
| INT-04 | STATE PRE/INFLIGHT/NONE | 계약·관련 회귀 |
| INT-05 | STATE R10 | 계약·관련 회귀 |
| INT-06 | STATE P12와 API attestation | API/RECOVERY |
| INT-07 | COST evidence 및 재발급 | 계약·관련 회귀 |
| INT-08 | COST 503 | 계약·관련 회귀 |
| INT-09 | COST T07 상한 | 계약·관련 회귀 |
| INT-10 | PLAN 후속 상세 로그 | 계약·관련 회귀 |
| INT-11 | API summary | API/RECOVERY |
| INT-12 | DATABASE FK 순서 | DB/BUDGET |
| INT-13 | COST 서버 도출 소유권 | DB/BUDGET |
| INT-14 | STATE 보관/정산 | QUEUE/RECOVERY |
| INT-15 | PLAN 기동 검사 | DB/BUDGET |
| T02-제약-01 | COST CONTENT_TYPE 링크 | 계약·관련 회귀 |
| T02-제약-02 | LOCK 최상위 TX | LOCK/RECOVERY |
| T02-제약-03 | COST 영속 복구 | 계약·관련 회귀 |
| T02-제약-04 | COST 내부 접두사 | API/RECOVERY |
| T02-제약-05 | COST 503 | 계약·관련 회귀 |
| T02-제약-06 | COST 성능 | PERF |
| T02-제약-07 | COST CR-01 | PERF |
| T02-제약-08 | COST CR-02 | PERF |
| T02-제약-09 | PLAN 운영 분산 시각/제한 별도 | 계약·관련 회귀 |
| T02-제약-10 | API P-T02-05 | API/RECOVERY |
| FD-01 | DATABASE 소유권 | DB/BUDGET |
| FD-02 | LOCK 공통 순서 | LOCK/RECOVERY |
| FD-03 | LOCK FK/UNIQUE | DB/BUDGET |
| FD-04 | COST 단일 발급 | 계약·관련 회귀 |
| FD-05 | STATE R10 | 계약·관련 회귀 |
| FD-06 | API 영속 정산 | API/RECOVERY |
| FD-07 | COST LIFETIME | DB/BUDGET |
| FD-08 | PLAN Quartz | 계약·관련 회귀 |
| FD-09 | STATE 자동 3회 | 계약·관련 회귀 |
| FD-10 | STATE 수동 2회 | 계약·관련 회귀 |
| FD-11 | LOCK 유효 Lease | QUEUE/RECOVERY |
| FD-12 | COST 한도 하향 | 계약·관련 회귀 |
| FD-13 | PLAN FAKE | 계약·관련 회귀 |
| FD-14 | COST 검사 차단 | DB/BUDGET |
| FD-15 | API 현행 규격 | API/RECOVERY |
| FD-16 | STATE 채널 보관 | QUEUE/RECOVERY |
| P-T02-05 | API 영속 정산·구 API 제거 | API/RECOVERY |
| CR-01 | COST 운영 배포 전 프록시·IP | 운영 격리 환경의 헤더 신뢰 경계 검증 |
| CR-02 | COST 승인 기반 보정 | PERF/DB, 원장 수정 0 |

도메인·비용 회귀에 A3 §12와 A4 §10 케이스를 유지하되, 허가 이후 NOT_EXECUTED의 재발급 기대값은 0이며 FD-11은 DB UTC 기준 유효 Lease 세션을 센다.

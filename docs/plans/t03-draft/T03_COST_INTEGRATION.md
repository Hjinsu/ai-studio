# 비용 통제 및 영속 복구 (제안)
ADR-017/019/026~030/038/042/043/048/054~057/061, RV-01~09 유지. 원장 append-only, 정산 기존 링크 및 환율 고정, 실제 비용 초과도 기록. 시스템 DAY/MONTH 필수, 기존 CHANNEL 적용 규칙 유지, 콘텐츠 작업은 CONTENT_TYPE LIFETIME 필수(제안), 정책 누락/비활성/0원 fail closed. 신규 지출 차단 유지, 기존 정산 허용. 정책별 한도는 중복 합산 금지.

## LIFETIME
적용 범위는 실제 기존 CONTENT_TYPE 정책의 전역/채널별 선택 계약 확인 후 확정. 제안: 해당 유형 전역 정책 및 채널별 적용 정책이 모두 존재하면 각각 적용, 비활성 정책을 우회하지 않음; 최소 한 개 활성 적용 유형 정책 필수. 각 policy/content 단위 고정 bucket, revision/run 변경으로 비용 초기화 금지. 정책 재생성으로 초기화 금지. 정책 변경 시 동일 bucket에서 한도 갱신, 기존 소비·예약·링크 보존. content 소유 채널/유형 검증. 기존 SYSTEM/CHANNEL DAY/MONTH 연결 검사는 유지. reservation.content_id NULL인 기존 T02 예약은 기존 계약으로 검사, content_id 있는 예약은 당시 적용 정책의 정확한 링크 집합 및 유형·기간·소유권 검증. 현재 정책 목록을 과거 예약에 소급하지 않는다. 검사 시각/정책 revision 판정은 실제 RV-07 구현과 대조. 기존 예약 링크 수정 금지. content 예산 집계는 하나의 bucket 기준으로 표시, 여러 정책이면 최소 잔여 한도.

## 트랜잭션 경계
1. TX1: job token 검증, 논리 operation/불변 요청/키 준비.
2. TX2: 기존 budget service 최상위 TX, request_key로 reserve. 외부 TX 없이 호출. 성공/불확실 응답은 동일 key 조회/재실행으로 확인.
3. TX3: 예약 ID 연결, 상태 RESERVED. 취소/lease 상실이면 외부 호출 금지, orphan recovery로 정리.
4. TX4: 유효 token과 실행 조건 검증, DISPATCHING intent를 커밋. 실제 호출은 TX 밖. 이후 crash는 요청 전송 여부를 증명할 수 없으면 불확실. 외부 호출 직전 지출 차단/채널 조건 재검증 정책 및 경쟁 경계 A에서 확정(이미 시작된 호출의 강제 취소는 보장 불가).
5. TX5: 결과와 immutable 정산 payload 기록; stale worker는 job 결과를 변경 못함. 불확실 응답은 복구 절차로만 처리.
6. TX6: 기존 budget settlement 최상위 TX, 안정 event_key. 미확정 시 원장 조회/동일 payload 재시도.
7. TX7: 원장 결과 확인 후 operation COMPLETED/job 완료. 성공 기록 전에 정산 성공 증거 필요.

상태 PREPARED→RESERVED→DISPATCHING→RESULT_RECORDED→SETTLEMENT_PENDING→COMPLETED. 미확정 상태 RECOVERY_REQUIRED, 미실행 및 예약 해소 확인 시 CANCELLED. 예약 자체 RECONCILIATION_REQUIRED는 기존 서비스 계약 사용. 큐 락을 보유한 채 비용 호출 금지; budget_control→channel→policy→bucket→reservation→event INSERT 유지. 예약 전후 crash, 링크 없는 예약, 완료 후 job 미반영을 양방향으로 탐지하는 reconciler; 동일 operation에 동시 처리 권한 guard 및 키 불일치 fail closed. 자동 복구는 증명 가능한 멱등 DB 연산만 허용, 외부 재호출/자동 해제 금지.

## 키
run:v1:<uuid>, op:v1:<uuid>, job-budget:v1:<uuid>, settle:v1:<uuid>:confirm:1 / provisional:1 / release:1, admin-settle:v1:<uuid>. reserve:/reserve-v2: 사용 금지. 길이/regex 실제 DDL 확인. 같은 key 같은 canonical payload 재사용; 다른 payload 409. 요청 단위 UUID는 재시도 전에 영속 저장. 복수 정산 이벤트는 각 logical event sequence를 영속 생성, 시도 횟수로 key 변경 금지.

## P-T02-05 관리자 정산
별도 settlement_operation 제안. 생성 API는 client creation key+fingerprint 멱등, 서버 ID/불변 payload 저장. 실행은 해당 ID만 사용, 원장 event_key 고정, 결과 조회 GET 및 미완료 목록 제공. 새로고침/다른 탭/기기에서 기존 pending/unknown operation을 우선 제시하고 별개 정산 생성은 명시적 확인. 생성 응답 유실도 목록/키로 찾는다. 동일 reservation에 불확실 operation이 있으면 새로운 조정 실행을 차단하고 기존 결과를 확인; 생성 경쟁은 DB guard로 보호. 정상적인 같은 금액의 별개 조정은 기존 완료 확인 후 새 operation으로 허용. 기존 직접 resolve/adjustment API로 우회되지 않도록 서버에서 영속 operation 계약을 강제(기존 호환 변경은 ADR/OpenAPI 승인 필요). 페이지 상태나 localStorage만으로 해결하지 않는다. 권한/CSRF 및 T02 세션 generation 경계 적용. 원장 커밋 후 operation 결과 저장 실패는 원장으로 복구; GET 미조회만으로 미실행 단정 금지.

## CR-01/02 및 성능
신뢰 프록시는 운영 전에 명시 allowlist, untrusted forwarded header 제거, 신뢰 hop 검증, rate limit 정책 재검증. 로컬은 remoteAddr 유지. 오류 복구: 지출 차단→격리 백업/진단(예약 ID, link set, policy revision, bucket 합계, ledger delta, operation)→사용자 승인→원장 불변을 지키는 보정 migration/관리 명령 별도 승인→정합성 재검사→명시적 지출 해제(실제 과금 승인 전 금지). 자동 데이터 수정 금지. 검사 규모 1k/10k/100k 예약의 scan 시간, control lock hold, reserve/settle p95 및 타임아웃을 격리 DB에서 측정; 허용치 A에서 합의하고 결과 보고, 안전성 완화 금지. 다중 인스턴스 도입 전 DB 시각 일관성·분산 로그인 재검토.

# DB 작업 실행 엔진 (제안)
Quartz는 탐색/복구 트리거만 담당; DB가 상태의 유일한 원천. Quartz JDBC JobStore는 Flyway 관리. 단일 인스턴스, worker 2개, poll 1초, lease 60초, heartbeat 15초, sweep 10초, 콘텐츠별 활성 run 1개/실행 job 2개. 전역 다중 인스턴스 실행 제한을 보장한다고 주장하지 않는다. 환경 설정 AI_STUDIO_ 접두사. 실제 provider 미등록/실행 금지.

## Claim / fencing
Dispatcher는 worker permit 확보 후 짧은 READ COMMITTED TX에서 eligible job을 선택한다. run/channel 활성 여부와 의존성/취소/시도 상한을 재확인. 동일 run 잠금과 job 잠금 순서는 A에서 단일화하고 모든 집계/취소/복구에 적용한다. budget lock과 섞지 않는다. 예시 후보 쿼리(그대로 실행할 migration 아님):
```sql
SELECT id FROM job WHERE status='READY' AND available_at<=UTC_TIMESTAMP(6)
ORDER BY priority DESC,available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED;
```
실제 claim은 run/job 순서 및 슬롯 제한 검증 후 token(UUID), owner, expiry, attempt_count 증가, attempt/event를 원자 저장. claim 실패 permit 반환. claim 후 TX 종료, 실행 시작. heartbeat/complete/update는 id+token+허용 상태+lease_expires_at>DB UTC 조건과 affected rows=1 검사. stale worker 결과 차단; 외부 부작용은 token만으로 차단 불가능. 만료 시 sweep이 동일 잠금 순서로 재확인하고 attempt 종료, retry/recovery 전이. heartbeat와 sweep 경합 테스트. heartbeat는 worker 실행과 독립 스케줄러. 재시작은 미확정 operation부터 진단, 만료되지 않은 lease 강제 탈취 금지.

## DAG
run 생성 TX에서 고정 pipeline version과 job set/edges 저장, 위상 정렬로 순환/누락/자기참조 거부, 최대 nodes/edges 제한을 설정하고 테스트. 동적 DAG 변경은 T03 제외. 모두 성공한 predecessor만 READY. 결과 전달은 동일 run 내 불변 결과 참조; 이전 run 결과 혼합 금지. dependency promotion/terminal 집계는 중복 실행 가능한 reconciler로 회복.

## Retry
최초 포함 최대 5회 제안(초기 문서 3회 제안과 충돌: 사용자 승인 필요), base 5초, cap 300초, exponential+jitter, Retry-After는 더 긴 경우 존중. UTC available_at에 실제 결정 지연 저장. 예산 재계획 소진 503, DB 일시 장애는 기존 key로 안전할 때만 retry; BUDGET_EXCEEDED/POLICY_VIOLATION/PREREQUISITE_NOT_MET는 ACTION_REQUIRED, 잘못된 입력 확정 실패. 외부 결과 불명은 무조건 RECOVERY_REQUIRED. 관리자가 재개해도 기존 attempt 기록/키 유지; 한도 초과 시 새 run 또는 승인된 복구만.

## 운영
graceful shutdown: claim 중단, 진행 작업 제한 시간 대기, 미완료는 lease/operation으로 복구; 종료 시 비용 예약 자동 해제 금지. fake executor는 성공/실패/지연/취소/응답유실 모드를 allowlist로 제공, 외부 네트워크 호출 금지. 운영 프로필에서 fake 실행 UI 활성화 여부 명시. 로그에는 job/run/trace ID, 비밀/요청 민감 본문 제외. 큐 metrics: ready age, claim rate, retry, lease expiry, recovery count; 기존 health 공개 범위 유지.

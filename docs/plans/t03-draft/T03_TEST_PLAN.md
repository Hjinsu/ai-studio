# 검증 계획 (제안)
테스트 수는 사전 확정하지 않는다. 기존 T02 백엔드 133 단위/97 통합, 프론트 73 단위/10 mock E2E는 과거 검증 기준이며 신규 실제 결과와 구분한다. 운영 DB 3306 사용자 데이터 사용 금지; Testcontainers/격리 테스트 DB. CI 7 job 유지 및 신규 검증 추가. 미실행은 명시.

| ID | 필수 시나리오 |
|---|---|
| DB01 | 빈 DB V1~신규, V5→신규, 이전 migration checksum 불변, 기존 예약/원장 호환 |
| DB02 | cross-content revision/run 및 channel 소유권, 불변 revision, 동시 revision 생성 |
| DB03 | 동시 run 생성 1개, 동일 키 replay, 다른 payload 409, 미확정 run 재생성 차단 |
| Q01 | DAG 순환/self/cross-run, fan-out/fan-in, 선행 실패/취소 전파 |
| Q02 | 동시 claim 경쟁, slot 상한, ready 시간·우선순위, starvation 측정 |
| Q03 | heartbeat/expiry 경합, stale token complete 거부, DB UTC |
| Q04 | 모든 상태 전이와 금지 전이, 취소/완료 경합, terminal 집계 |
| Q05 | retry 상한·backoff·jitter·Retry-After, 503 vs 비즈니스 오류 |
| Q06 | 서버 강제 종료 및 재시작, 미확정 작업 재호출 금지 |
| C01 | LIFETIME 콘텐츠별 분리, revision/run/정책 변경 후 누적 유지, 채널/유형 불일치 |
| C02 | 기존 T02 예약 검사 호환, 신규 링크 누락/중복/잘못된 bucket 검출, ERROR 신규 지출 차단 |
| C03 | budget service 외부 TX 금지, lock order 및 경쟁, 금액/환율 기존 정밀도 |
| C04 | 각 TX 경계 crash 주입: PREPARED, reserve commit, link, dispatch intent, 결과, settlement commit, job complete |
| C05 | orphan reserve 탐지, 동일 키 재시도, 미확정 자동 해제 금지, 중복 정산 차단 |
| C06 | 수동 정산 생성/실행 응답 유실, 새로고침/탭/기기, 동시 요청, 같은 내용 별개 정상 조정, 기존 직접 API 우회 차단 |
| C07 | 지출 차단/채널 pause/보관 시 신규 예약 금지, 기존 정산 허용, fake 격리 |
| C08 | 정합성 검사 규모별 control lock/지연 측정, CR-02 진단 dry-run |
| API01 | 실제 MockMvc OpenAPI 요청/응답 및 추가 필드 거부, ID 경계, 인증/CSRF/오류 |
| UI01 | 목록/상세/이력/작업/복구, loading/empty/error, 한글/라이트·다크/모바일 |
| UI02 | 오래된 세션 콜백 및 요청 차단, 정산 조회 실패 후 operation 유지, 401/403/429 |
| E2E01 | mock 브라우저 검증과 별도 실제 백엔드+격리 DB 브라우저 검증(가능 여부 A에서 확정) |
| REG01 | 기존 T02 전체 회귀, OpenAPI 재생성 diff, lint/typecheck/build, gitleaks |

장애 테스트는 sleep 기반 경쟁 대신 barrier/latch/제어 시계/명시 fault injection 우선. DB UTC 조건은 실제 DB 통합으로 확인. 각 시나리오에서 job/attempt/operation/reservation/bucket/ledger의 최종 불변조건을 assert. 테스트 보고서에 명령, 환경, 커밋, 통과/실패/미실행 및 CI ID 기록. 독립 리뷰는 핵심 상태/락/원장/세션 경계에 집중. 사용자 검증 후에만 DONE 승인 요청.

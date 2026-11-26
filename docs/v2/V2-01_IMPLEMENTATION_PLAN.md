# V2-01 구현 계획

ChatGPT APPROVE WITH MINOR FIXES 및 사용자 승인. 출발 기준은 `0187078172439535a96cc9d466aa6ab1d933acd0`이며, 병렬 구현 기준은 검증한 `v2/common` 커밋이다. 개인 PC의 단일 JVM에서 고정 단계와 FAKE 비용 대역으로 검증한다. [품질·수익화 요구사항](05_CONTENT_QUALITY_AND_MONETIZATION.md)의 검수·재생성도 같은 요청과 비용 계약을 사용한다.

## 소유권과 저장 구조

| 후보 | 결정안 및 참조 |
|---|---|
| `production_request` | 신규. 채널과 불변 채널 설정 버전, 요청자, 콘텐츠 유형, 고정 파이프라인 버전, KRW 상한, 생성 멱등 키·본문 해시, 상태·취소 의도·실행 lease를 저장한다. 소재 연결 전에도 존재한다. 채널·설정은 복합 FK, 선정 후 콘텐츠·Run은 같은 콘텐츠를 보장하는 복합 FK와 Run 고유 제약으로 연결한다. Topic과 Revision은 각각 Content와 Run에서 조회한다. |
| `production_step` | 신규. 요청 FK와 고정 단계 식별자·대상·입력 세대를 고유 키로 사용한다. 상태, 시도 횟수, 재시도 시각, 결과 참조와 마지막 오류를 저장한다. 최초에는 단계당 한 행으로 실행한다. 향후 부분 재생성은 변경 단계와 영향받는 후속 단계에만 같은 요청의 새 입력 세대를 할당한다. 실제 구현은 해당 제품 단계에서 진행한다. |
| `production_attempt` | 보류. 현재 시도 횟수·실행 시작/종료·마지막 결과는 단계 행, 유료 시도와 증거는 효과 행 및 원장으로 추적한다. 전체 로컬 시도 이력이 사용자 요구가 될 때 검토한다. |
| `provider_operation` | 신규. 단계 FK, 논리 효과 고유 키, 정규화 요청 해시, 상태, dispatch 시각, 예약 FK(고유), 외부 참조·정산 근거·영수증·적용 상태를 보존한다. 효과 키는 요청/단계/대상/입력 세대로 정하며 실행 시도나 lease 교체로 바뀌지 않는다. |
| `provider_receipt` | 보류. 효과당 최종 영수증 하나를 `provider_operation`에 불변 값으로 저장한다. 영수증 ID는 효과 ID이고, 결과 종류·결과 참조/해시·최종 원장 이벤트 참조·확정 시각을 포함한다. 여러 금액 이벤트의 이력은 `cost_log`로 보존한다. |

재사용 대상은 Topic 정규화·중복 처리, `TopicService`, `ContentService`, `ContentMapper`, V6 소유권 제약 및 `content_run`이다. 요청자는 기존 관리자 인증을 사용하며 서버가 채널·요청·단계·Run 연결을 검증한다. DISCOVER 전에 스냅샷과 상한을 요청 행에 커밋한다. SELECT 성공 시 요청 락과 실행 fencing을 확인한 한 트랜잭션에서 선정 해시 검증, Topic 조회/생성, Content와 초기 Revision 생성, FAKE Run 생성 및 active 포인터/요청 연결, SELECT 완료를 처리한다. 동일 해시 재생은 기존 연결을 반환하고 다른 해시는 충돌 처리한다. 기존 서비스의 독립 재시도 진입점 대신 동일 트랜잭션에 참여하는 도메인 로직을 재사용하고 락 재시도는 바깥 트랜잭션 전체에 적용한다.

## 실행과 비용 경계

- 고정 순서는 DISCOVER → SELECT → RESEARCH → SCRIPT → SCENES → IMAGE → TTS → SUBTITLE → RENDER → METADATA → QC다. V2-01은 FAKE 실행으로 이 경로를 검증한다. 요청 상태는 `QUEUED / RUNNING / RECOVERY_REQUIRED / SUCCEEDED / FAILED / CANCELLED`, 단계는 `PENDING / RUNNING / RETRY_WAIT / RECOVERY_REQUIRED / SUCCEEDED / FAILED / CANCELLED`로 제한한다. 취소 의도는 별도로 보존한다. Run은 V6 상태만 사용하며 요청이 복구 대기 중이면 연결된 Run을 종료하지 않는다.
- claim 시 기존 budget_control 1번 행의 배타 잠금을 거쳐 DB UTC 기준 유효 요청 lease 수를 확인하고 요청을 잠근다. Worker 기본 1, 유효 lease 상한 2, 요청당 실행 단계 하나다. lease는 소유자·만료·단조 증가 epoch를 저장한다. 갱신·완료·dispatch는 요청 락 아래 유효 epoch로 검증한다. 만료된 실행자는 상태 변경을 할 수 없으며 이미 발송한 효과의 증거는 효과 ID를 기준으로 별도 접수할 수 있다. 로컬 무효과 작업과 발송 전 안전 실패만 제한된 지연을 두고 최대 3회 실행한다. 불명 소거 없이 재시도하거나 재생성으로 우회할 수 없다.
- 실행기는 비용 포트의 `executeEffect(stepOwner, effectKey, requestHash, pricedRequest)`로만 외부 효과를 요청한다. 포트가 전송을 소유하며 결과는 영수증/대기/거부/복구 필요로 제한한다. `readCostState(requestId)`는 미해결 예약·효과와 미적용 영수증을 돌려준다. `reconcile(operationId, evidenceKey, evidence)`는 조회 또는 관리자 확인 증거만 처리하며 발송을 수행하지 않는다. 영수증 적용과 단계 갱신은 실행기가 같은 트랜잭션에서 처리한다.
- **예약.** 요청 락 아래 효과 키·해시를 확인한다. `BudgetReservationService`의 예약·환율·일/월 시스템 및 채널 bucket 계산, `BudgetControlLock`, `budget_reservation_bucket`, `cost_log`를 재사용한다. 요청에 속한 모든 예약의 KRW 확정/인식액과 잔여 예약액, 신규 예약 환산액의 합을 상한과 비교한다. 요청 상한은 기존 활성 영상 유형 한도 이내에서 결정한다. 유형 정책이 낮아지면 신규 예약·dispatch에는 더 낮은 상한을 적용한다. 기존 channel budget·FX·period 잠금 순서를 유지한다. 예약과 효과 연결은 같은 트랜잭션이며 초과 시 둘 다 롤백한다. 기존 서비스의 최상위 트랜잭션 진입점은 중첩 호출을 거부하므로 원장 계산·쓰기 본체를 공통 트랜잭션에 참여시키고 기존 호출 경로의 동작을 유지한다. 예약 링크는 소재 선정 뒤에도 그대로 유지한다. 기존 공개 예산 명령으로 연결 예약을 해제·정산할 수 없도록 예약 락 아래 가드한다.
- **발송.** 별도 짧은 트랜잭션에서 요청/lease·취소·미해결 효과·예약 유효성·현재 한도와 기존 지출/정합성 차단을 다시 확인한다. 예산 기간이 바뀐 미발송 예약은 미실행으로 확정해 해제하고 해당 효과의 발송을 종료한다. 확정된 미발송 영수증을 적용하고 이전 효과가 해제된 경우에만 명시적으로 다음 입력 세대를 허용한다. 한도·기간·취소·차단을 새로 심사한다. 키는 요청 ID/단계/대상/입력 세대를 정규화해 SHA-256으로 만들며, 일시 오류·lease 만료·재시작은 세대를 바꾸지 않는다. 불명 효과나 단순 재시도로 새 효과를 만들 수 없다. `RESERVED → DISPATCHED` 전환을 커밋한 호출자 한 명만 트랜잭션 밖에서 1회 전송한다. 자동 HTTP 재전송은 끈다. 취소나 차단이 먼저 커밋되면 신규 허가가 없으며, 이미 허가된 전송은 회수할 수 없으므로 정산 대상에 남긴다. 허가 직후 종료도 결과 불명으로 본다. 재시작은 기존 `DISPATCHED`를 재발송하지 않는다.
- **정산·재생.** 타임아웃·증거 부족은 `UNKNOWN`으로 격리하고 예약을 보존한다. 검증 가능한 증거(외부 ID, 금액·통화 또는 미실행 근거)는 정규화 SHA-256과 함께 먼저 영속화한다. 같은 키·해시는 저장 결과를 재생하고, 동일 작업에 다른 확정 증거가 들어오면 충돌로 격리해 검토한다. 기존 정산과 영수증은 덮어쓰지 않는다. 요청 락 아래 안정된 이벤트 키로 원장 확정·해제와 최종 영수증을 한 트랜잭션으로 기록한다. 미실행 증거가 있을 때만 비용 없이 해제한다. 실제 비용이 예약을 초과하면 사실대로 원장에 기록하고 같은 트랜잭션에서 요청의 지출 차단을 고정하고 신규 예약·dispatch 및 정상 완료를 막는다. 영수증 커밋 후 장애는 결과 적용만 재생한다. 미해결 비용/예약, 미적용 영수증 또는 비용 조회 실패 시 요청과 Run을 정상 종료하지 않는다. 종료 시 V6의 active Run 포인터를 먼저 해제하고 Run과 요청 상태를 같은 트랜잭션에서 전환한다. 취소 시 신규 효과를 막고 확정된 미발송 예약만 해제하며, 불명 효과 정산 후 취소를 마친다.
- 공통 잠금 순서는 budget_control → 요청 → 기존 채널 → 정책 → bucket → 예약이다. 기존 채널 상태 변경과 예산 서비스도 budget_control을 채널보다 먼저 잠근다. 요청만 갱신·조회하는 API는 budget_control이나 채널 락을 나중에 획득하지 않는다. claim은 전역 행 배타 락을, 예약·dispatch는 공유 락을 처음부터 획득하며 잠금 승격은 사용하지 않는다. 요청을 다루는 변경은 기존 도메인·원장 락을 보유한 상태에서 요청 락을 역으로 획득하지 않는다. 네트워크/파일 작업은 트랜잭션 밖에서 수행한다. DB 교착 재시도는 순수 DB 작업에만 적용한다.

## 최소 API 및 파일 담당

- 기존 인증·CSRF·오류 응답 형식을 유지한다. `POST /api/v1/production-requests`는 채널 ID·콘텐츠 유형과 `Idempotency-Key`를 받아 요청 ID/상태를 반환한다. 같은 키와 같은 본문은 기존 요청, 다른 본문은 409 `RESOURCE_CONFLICT`다. `GET /api/v1/production-requests/{id}`는 단계, 연결 ID, 취소·복구 상태, KRW 상한/예약/확정액을 반환한다. 금액은 문자열이다. `POST .../{id}/cancel`은 반복 가능하게 취소 의도를 기록한다. 재시작 복구는 내부 실행기로 처리하고 V2-01의 정산은 내부 포트와 대역으로 검증한다. 실제 유료 API를 활성화하기 전에 사용자 수동 복구 경로를 제공한다.
- **Claude**는 `backend/src/{main,test}/java/com/aistudio/production/execution/` 및 해당 실행 전용 MyBatis 매퍼 XML을 소유한다. 고정 실행, lease/fencing, 취소·복구, FAKE 자체 테스트를 작성한다. C의 `RetryBackoff.java`·`RetryBackoffTest.java`와 `JobTransitionsTest.java`에서 필요한 규칙과 사례를 선별한다.
- **Codex**는 `production/contract/`, `production/cost/`, `production/api/`, 공통 요청/선정 연결, 비용 매퍼·기존 `budget/` 변경, Migration·운영 권한·`docs/api/openapi.yaml`·생성 타입 및 통합 검증을 소유한다. 기존 `BudgetReservationService`, `BudgetMapper`, `BudgetConcurrencyIT`·`BudgetFailClosedIT`를 재사용한다. D의 `OperationCostPort`에서 미해결 상태와 영수증 경계, `SettlementMathTest`·`ReservationBucketEvidenceTest`에서 필요한 사례를 선별한다. 포트와 스키마의 변경은 공동 합의 후 Codex가 반영한다. 착수·병렬 승인 후 별도 worktree에서 작업한다.

## Migration과 인수 검증

- 신규 Migration은 **V9**다. V1~V6 원본을 유지하고 C의 V7과 D의 V8 초안을 보존한다. 신규/기존 V1~V6 기준 격리 DB에서 V9 적용을 검증한다. V7 이력 DB는 누락 Migration 검증과 객체 충돌 위험이 있으므로 자동 기동을 차단한다. 해당 이력의 지원이 필요하면 C V7 원본과 데이터 의미를 보존하는 이관안을 별도 승인받아 격리 복제본에서 검증한 뒤 적용한다. Flyway 검증 우회 또는 이력 삭제를 사용하지 않는다.
- 동일 요청 재전달·다른 본문 충돌, 선정 연결 중 장애 롤백·재생, 다른 채널/Content 연결 거부, 3개 동시 claim과 만료 lease 교체·오래된 worker 쓰기 거부, 3회 재시도 제한과 취소 경합을 확인한다.
- 두 연결에서 일·월/채널·영상 한도 경합, 소재 선정 전후 및 검수·재생성 비용 합산, 기간 경계/환율, 스위치 차단, 기존 정산 API 우회 거부를 검증한다. 발송 허가 전후·응답 전후·원장 확정 후 각각 장애를 주입해 전송 횟수, 불명 상태의 예약 보존, 증거 중복과 receipt 재적용, 미해결 비용의 완료 차단을 확인한다.
- 테스트는 [격리 MariaDB 가이드](../guides/LOCAL_TEST_GUIDE.md)를 따른다. CI Testcontainers 또는 별도 loopback 포트와 임시 데이터 폴더를 사용한다. 회사 PC JDK 21.0.13은 아래 PowerShell 설정으로 Java·javac·Gradle Wrapper 9.8.2 실행을 확인했다. 설정 범위는 현재 셸이다. FFmpeg·FFprobe 준비는 V2-04에 배정한다.

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
java -version
javac -version
.\gradlew.bat --version
```

공통 구현은 스키마·상태·포트 및 생성·조회·취소 의도 API를 제공한다. 실행기, 소재 선정 연결과 비용 처리의 구현 및 결합 검증은 각 담당 브랜치에서 진행한다. 실행기 시작과 비용 대역 연결을 완료할 때까지 API의 요청은 대기 또는 취소 의도 상태로 남는다.

## Claude 착수 지시

`v2/product` worktree에서 `v2/common`과 시작 SHA가 같은지 확인한다. `production/execution`과 실행 전용 매퍼·자체 테스트에서만 작업한다. API가 미리 생성한 11개 단계에 고정 순서를 적용하고, lease·취소·재시작·3회 한도를 구현한다. 먼저 두 공통 포트를 테스트 대역으로 연결해 무효과 성공·실패·복구를 검증한다. SELECT 연결은 `ProductionSelectionPort`, 외부 효과·정산 상태·receipt 적용은 `ProductionCostPort`를 사용한다. 공통 파일 변경이 필요하면 Codex에 변경안을 전달한다. 자체 검증 후 변경 경로, 커밋, 실제 테스트 결과 및 보류 항목을 공유한다.

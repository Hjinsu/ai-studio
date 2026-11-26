# V2-01 제품 실행기 보고 — Claude Code

2026-11-26 KST · 상태 **IMPLEMENTED**(독립 리뷰·결합 검증 전) · 브랜치 `v2/product` · 기준 `5cba9a822073ac0d44529c1c5dfc72e93d918a04`(`v2/common`과 동일 확인)

병렬 작업 보고서다. STATE.md·TASKS.md·DECISIONS.md·OpenAPI·Migration·포트는 수정하지 않았다. 공통 파일 갱신은 통합 담당자(Codex)에게 맡긴다.

## 범위와 변경 파일

수정 범위는 `production/execution`(main·test)과 이 보고서다. 실행 SQL은 기존 저장소 관례대로 어노테이션 MyBatis 매퍼(`ExecutionMapper`)에 두었다. 별도 XML은 없다.

| 파일 | 역할 |
|---|---|
| `ProductionExecutor` | claim·fencing·heartbeat, 고정 11단계 구동, 로컬 재시도, 효과 제시·영수증 적용, 복구 대기, 취소, 종료와 Run 종료 |
| `StepPlanner` | DB 상태만으로 다음 동작 결정(재시작 후 동일 결정). 순서: 복구 → 중단 단계 → 취소 → 실패 → 다음 단계 |
| `ExecutionMapper` | V9 `production_request`/`production_step`와 V6 `content`/`content_run` 실행 전용 SQL. 시각은 모두 DB `UTC_TIMESTAMP(6)` |
| `ExecutionProperties` | `ai-studio.production.executor.*`. 기본 비활성, worker 1, lease 상한 2(초과 설정 거부), lease 60s, 재시도 5s→cap 5m, 복구 재확인 5m |
| `ProductionExecutionConfiguration` | `enabled=true`일 때만 빈 생성. 두 포트 구현이 반드시 있어야 하며 대체 경로 없음. 관리자 CLI에서는 worker 미기동 |
| `ProductionWorkerPool` | SmartLifecycle worker 스레드. owner는 프로세스 기동마다 고유 |
| `StageWork` / `FakeStageWork` | 단계 종류(LOCAL·EFFECT·SELECT)와 작업 정의. FAKE: SUBTITLE·RENDER 로컬, SELECT는 선정 포트, 나머지 8단계는 비용 포트 |
| `EffectRequestHash` | 정규화 요청 해시(SHA-256). 시도·epoch·재시작과 무관 |
| `RetryBackoff` | T03-C 규칙 선별(지수 + equal jitter) |
| `StepFailure`, `LeaseLostException` | 로컬 실패 분류, fencing 실패 |
| test: `StepPlannerTest`, `ExecutionRulesTest`, `ExecutionFakes`, `ProductionExecutorIT` | 실행기 단위 14건, 격리 통합 31건(종료 비용 경합 3건, 취소 경합 3건, FAKE 가격 1건 포함) |

## 실행 규칙(구현된 동작)

- **claim**: `budget_control` 배타 잠금 → DB UTC 기준 유효 lease 수 확인(상한 `maxLeases` ≤ 2) → 대상 요청 `FOR UPDATE` → owner·만료 기록, `lease_epoch + 1`, QUEUED→RUNNING. 대상은 비종료이며 유효 lease가 없고 미래 `retry_at` 단계가 없는 가장 오래된 요청이다. 취소 의도는 로컬 재시도 대기만 건너뛰고 복구 재확인 대기는 건너뛰지 않는다.
- **fencing**: claim 이후 모든 상태 변경은 요청 락 → owner·epoch·만료(DB 시각) 확인 → lease 갱신 후 수행한다. 만료됐거나 교체된 worker는 `LeaseLostException`으로 변경 없이 중단된다. 트랜잭션 밖 작업 중에는 lease/3 간격으로 heartbeat하며, 만료된 lease는 갱신하지 않는다.
- **로컬 단계**: 시작 시 시도 +1(최대 3, DB CHECK와 일치). 재시도 가능 실패는 `RETRY_WAIT` + DB 시각 기반 backoff 후 lease를 해제한다. 3회째 실패 또는 재시도 불가는 `FAILED`. 중단된(RUNNING) 로컬 단계는 실패한 시도로 보고 한도 내에서 다시 실행하며, 한도 소진 시 `INTERRUPTED`로 실패한다.
- **SELECT**: 요청 락·fencing을 확인한 같은 트랜잭션에서 `ProductionSelectionPort.link` → Run QUEUED→RUNNING → SELECT 완료. 실패 시 전체 롤백 후 로컬 재시도. 취소 의도가 있으면 연결하지 않는다.
- **효과 단계**: 단계 RUNNING을 커밋한 후 트랜잭션 밖에서 `executeEffect(owner, EffectKey.forInput(요청, 단계, 대상, 세대), 요청 해시, 가격)`만 호출한다.
  - `RECEIPT`: 같은 fenced 트랜잭션에서 `markReceiptApplied`와 단계 결과를 반영한다. `RESULT_ADOPTED`는 성공, `EXECUTED_FAILED`는 실패, `NOT_EXECUTED`는 실패 또는 취소 의도 시 취소로 처리한다. 자동 재시도는 하지 않는다.
  - `REJECTED`: 발송 없음, 단계 실패(취소 의도 시 취소).
  - `WAITING`·`RECOVERY_REQUIRED`·예외·null: 결과 불명. 단계·요청 `RECOVERY_REQUIRED`, `retry_at = 현재 + 복구 재확인`, lease 해제. 재확인 때 **같은 키·해시를 다시 제시**하며 시도로 세지 않는다. 새 효과를 만들지 않는다.
  - 영수증 적용 트랜잭션 실패 시 롤백하고 복구 대기로 전환한다. 재제시하면 포트가 저장된 영수증을 돌려주어 재적용한다.
- **종료**(종료 경합 수정 반영): 요청 행을 잠그고 fencing을 확인한 **하나의 트랜잭션** 안에서 `readCostState`를 호출하고, 그 결과에 따라 보류 또는 종료 쓰기를 같은 트랜잭션에서 수행한다. 정산·증거 기록자는 요청을 먼저 잠그므로 확인과 종료 사이에 비용 상태가 바뀌지 않는다. 비용 포트의 읽기는 이 트랜잭션(READ COMMITTED, 비잠금, budget_control 미획득)에 참여한다. 조회 실패나 종료 쓰기 오류는 Run·포인터·요청 변경을 모두 롤백한 뒤 별도 fenced 트랜잭션에서 복구 대기로 둔다(`COST_STATE_UNAVAILABLE`/`FINISH_FAILED`). `readCostState`가 미해결·미적용 영수증·조회 실패(성공은 지출 차단 포함) 중 하나라도 보고하면 종료하지 않는다. 요청을 `RECOVERY_REQUIRED`로 두고 마지막 실행 단계의 `retry_at`으로 재확인을 예약하며 Run은 열린 상태로 유지한다. 종료 목표는 계획 스냅샷(이전 트랜잭션)에서 정했더라도 이 종료 트랜잭션에서 잠근 요청 행의 `cancel_requested`를 다시 읽어, 그사이 취소 API가 요청 락에서 커밋한 취소가 있으면 `SUCCEEDED`/`FAILED` 대신 `CANCELLED`로 종료한다(종료 취소 경합 수정). 이 재확인은 비용 확인과 같은 트랜잭션·같은 락 아래에서 수행되며 비용 미해결·미적용 영수증·조회 실패 차단과 오류 시 전체 롤백은 그대로다. 지출 차단은 기존 규칙대로 `SUCCEEDED`만 막으므로 취소 종료는 막지 않는다(플래너가 최신 상태로 결정했을 때와 같음). 종료 가능하면 한 트랜잭션에서 미완료 단계를 취소(취소 시)하고, active Run 포인터를 해제(content DRAFT)한 뒤 Run과 요청을 종료하고 lease를 해제한다.
- **취소**: 미발송 단계는 시작하지 않고 `CANCELLED`로 둔다. 실행 중인 로컬 단계의 실제 결과는 보존한다. 불명 효과가 있으면 정산 후에 취소를 마친다.
- **잠금 순서**: claim은 budget_control(X) → 요청이다. 단계와 종료는 요청 → (선정 포트) → content → content_run 순서다. 요청 락 보유 중에 budget_control을 잡지 않는다. 비용 상태 조회(`readCostState`)는 요청 락을 보유한 종료 트랜잭션 안에서 비잠금 읽기로 수행하며 budget_control이나 다른 락을 얻지 않는다.
- **FAKE 가격**: 효과 단계의 최대 금액은 양수만 허용한다. `fake-effect-max-krw`가 0 이하이면 설정 바인딩에서 거부하고(`FakeStageWork`도 0 이하·null 거부), 어떤 `StageWork`든 0 이하 가격을 내면 요청 해시 계산에서 거부되어 비용 포트에 제시하지 않고 로컬 실패(`LOCAL_ERROR`, 3회 한도)로 처리한다.

## 테스트 결과(실제 실행)

JDK 21.0.13, Gradle Wrapper 9.8.2. 격리 MariaDB 11.8.2 임시 인스턴스는 `127.0.0.1:3307`, 임시 데이터 폴더(저장소 밖)와 임시 비밀번호를 사용했다. 사용자 DB(3306)는 사용하지 않았다.

```powershell
$env:AI_STUDIO_IT_DB_HOST='127.0.0.1'; $env:AI_STUDIO_IT_DB_PORT='3307'; $env:AI_STUDIO_IT_DB_USER='root'; $env:AI_STUDIO_IT_DB_PASSWORD='<임시>'
.\gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks --no-build-cache
```

- 단위 **164/164**(기준 151 + 신규 13). 격리 통합 **181/181**(기준 154 + 신규 27, 종료 경합 수정 3건 포함). bootJar 성공.
- 종료 경합 수정 검증: 비용 확인 시 요청 행이 잠겨 있음(다른 연결 `FOR UPDATE NOWAIT` 실패), 확인 직후 들어온 정산 기록자가 종료 커밋 후에야 락을 얻음(종료 상태 관측), 종료 중 오류 시 Run·content 포인터·요청 전체 롤백 후 재확인으로 완료. 세 테스트는 수정 전 `finish`에서 모두 실패함을 확인했다.
- 통합 검증 사례: 11단계 순서와 Run 종료, 백그라운드 worker, 3개 동시 claim 중 유효 lease 2개, 한 요청 단일 lease, 만료/교체 worker 쓰기·heartbeat 거부, 단계 수행 중 lease 상실 후 결과 기록 거부, 로컬 재시도 backoff·성공·3회 한도·재시도 불가, SELECT 연결 실패 롤백·재시도, 시작 전·재시도 대기·로컬 단계 중·선정 직전 취소, 불명 효과 격리·재제시 시 재발송 없음, 발송 후 예외, 불명 효과 중 취소, 실행 실패·거부, 발송 직후 재시작 복구, 영수증 커밋 전 재시작 시 재적용 1회, 로컬 단계 중 재시작과 반복 재시작 3회 한도, 비용 미해결·조회 실패·지출 차단 시 완료 보류 및 지출 차단 상태의 실패 종료.
- 변형 확인: lease 상한 검사를 3으로 바꾸면 동시 claim 테스트가 실패한다. 만료 검사를 제거해도 fenced 트랜잭션의 갱신 조건(`lease_until > 현재`)이 쓰기를 막는다. 이는 의도한 이중 방어다.
- 비밀정보: diff 패턴 검사와 임시 비밀번호 미포함을 확인했다. 로컬에는 gitleaks가 없어 CI의 gitleaks job에서 확인한다.

## 결합 검증과 실행기 독립 리뷰

검증 브랜치 `v2/review-v2-01`의 공통 기준은 `5cba9a8`이다. 실행기 `123f2fc252d06a5defd98a84f1b3b117e3b7f612`와 비용 `6675f86a8a996ff314b7316436e489d2d2445502`를 임시 결합하고 Claude의 보완을 반영했다.

- Codex 독립 리뷰에서 종료 계획 스냅샷 이후 커밋된 취소 의도가 누락될 수 있음을 확인했다. Claude가 종료 트랜잭션의 잠긴 요청에서 취소 의도를 다시 확인하도록 수정했다. 성공·실패 계획 후 취소와 미해결 비용·미적용 영수증·쓰기 오류의 차단을 회귀 검증했다.
- FAKE 가격 설정과 효과 요청 해시는 양수만 받는다. 기본값은 KRW 1이다. 0원 설정과 효과 시도에서 공급자 포트에 도달하지 않는 것을 검증했다.
- 기존 실행기 단독 테스트에서만 대역 빈을 우선하도록 지정했다. `ProductionPortsIT`는 실제 비용·선정 포트를 사용하며 Provider 경계만 FAKE다.
- 격리 MariaDB 11.8.2(binlog OFF)에서 `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks --no-build-cache` 실행 결과 단위 165건, 통합 200건이 통과했고 bootJar가 생성됐다. 이 중 기존 실행기 통합 31건과 실제 포트 결합 6건이 통과했다.
- 별도 MariaDB 11.8.2(binlog ON)에서 `ProductionPortsIT`, `ProductionCostIT`, `ContentUpgradeIT`, `ProductionGrantsIT` 18건이 통과했다. [결합 검증 상세](../v2/V2-01_SAFETY_HANDOFF.md)
- V7 이력 DB와 사용자 수동 복구 경로는 이 검증의 대상에서 제외했다. 실행기 기본 설정은 비활성이다.

## Codex 전달 사항(공통 계약 확인 요청, 변경 없음)

1. **재제시 계약**: 실행기는 불명·중단 효과를 복구할 때 같은 `effectKey`·`requestHash`로 `executeEffect`를 다시 호출한다. 포트는 기존 효과에 대해 재발송 없이 저장 상태(`RECEIPT` 재생 또는 `RECOVERY_REQUIRED`)를 돌려주어야 한다. `RESERVED`만 있고 발송 전이면 그때 1회 발송해도 된다. 취소·차단 후 신규 효과는 `REJECTED`로 반환해야 한다.
2. **`REJECTED` 의미**: 실행기는 발송이 없었고 같은 키로 재시도해도 결과가 같다고 보고 단계를 실패시킨다. 발송 후 거부가 가능하다면 `RECOVERY_REQUIRED`를 사용해야 한다.
3. **`WAITING`**: 단계 상태 집합에 대기 상태가 없어 `RECOVERY_REQUIRED`로 두고 재확인 주기에 재제시한다. 짧은 polling이 필요하면 상태 또는 주기 분리를 제안한다.
4. **`markReceiptApplied`**: 실행기의 fenced 트랜잭션에 참여한다. 이 트랜잭션에서는 요청 락을 이미 보유하므로 budget_control을 획득하면 안 된다. 같은 영수증의 중복 호출은 멱등이어야 한다.
5. **`readCostState`**(종료 경합 수정 후): 요청 락을 보유한 실행기 종료 트랜잭션(READ COMMITTED) 안에서 호출한다. `v2/safety` `2211cc0` 구현은 비잠금 읽기만 하고 기본 전파로 참여하므로 그대로 호환된다. 이 구현의 `REPEATABLE_READ` 지정은 참여 시 적용되지 않으며, 실행기 트랜잭션은 락 획득 뒤 최신 커밋 상태를 읽는다. 앞으로도 이 메서드에서 budget_control이나 요청 이외의 락을 얻지 않고, 모든 정산·증거 쓰기가 요청을 먼저 잠그는지 결합 검증에서 확인해야 한다.
6. **`ProductionSelectionPort.link`**: 실행기 트랜잭션(요청 락 보유)에 참여한다. 요청의 content/run/selection_hash 연결, Content PRODUCING·active 포인터 설정은 포트가 담당한다. 실행기는 이후 Run QUEUED→RUNNING과 종료 시 포인터 해제·Run 종료를 담당한다. 동일 fingerprint 재생은 기존 연결, 다른 fingerprint는 `ApiException(RESOURCE_CONFLICT)`이면 재시도 불가로 처리한다.
7. **FAKE 가격**: `PricedRequest("KRW", fake-effect-max-krw(기본 1, 양수만 허용), "SUCCESS")`. FAKE 비용 대역의 시나리오 이름과 통화가 다르면 알려 준다.
8. **설정 제안**: 비용 포트 FAKE 구현이 들어오면 local/test 프로파일에서 `ai-studio.production.executor.enabled=true`를 켜는 설정 변경은 통합 담당자가 결정한다.

## 최종 결과

[CI Run 45737242958](https://github.com/Hjinsu/ai-studio/actions/runs/45737242958) 7/7 및 비용 엔진 독립 리뷰 APPROVE를 확인했다. 사용자 승인으로 [PR #4](https://github.com/Hjinsu/ai-studio/pull/4)를 v2/integration의 `37308fbd65d7f3a4a3bf61b454faea629f53f1ca`로 병합했다. V2-01은 DONE이며 V2-02는 착수 승인 대기 중이다.

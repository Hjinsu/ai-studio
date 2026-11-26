# V2-01 비용 안전성 인수인계

2026-11-26 KST · 비용 구현 IMPLEMENTED · 담당 Codex · `v2/safety` · 공통 기준 `5cba9a822073ac0d44529c1c5dfc72e93d918a04`

## 변경 범위

- `production/cost/`에 V9 효과 저장소와 비용 포트를 연결했다. 기존 원장의 일일·월간·채널 bucket, 예약 시 환율, 지출 스위치와 요청 전체의 영상 한도를 적용한다. 예약과 효과가 함께 커밋되며 현재 영상 유형 한도가 더 낮아지면 그 한도를 예약과 발송에 적용한다.
- 발송 허가를 커밋한 호출자만 트랜잭션 밖에서 FAKE 공급자에게 전송한다. 동일 효과의 가격·해시 변경을 거부한다. 발송 후 결과 불명과 증거만 저장된 상태를 보존하며, 영수증을 다시 읽거나 저장된 증거를 정산할 때 전송하지 않는다. 초과 청구는 실제 금액을 원장에 남기고 새 효과와 정상 완료를 차단하는 요청 플래그를 고정한다.
- `budget/`은 기존 예약·확정·해제 쓰기를 제작 요청 트랜잭션에서도 사용한다. 공개 예산 경로에서 제작 예약의 정산과 조정을 차단한다.
- `production/selection/`, `topic/`, `content/`, `channel/`에서 요청의 고정 채널 설정과 실행 lease를 확인하고 Topic·Content·Revision·FAKE Run을 연결한다. 실행기가 같은 트랜잭션에서 Run 시작과 SELECT 완료를 기록한다. 동일 선정 재생은 기존 ID를 반환한다.
- V9의 세 테이블과 기존 원장을 사용했다. Migration, 운영 권한, OpenAPI, 생성 타입 또는 공통 포트 시그니처 변경은 없다.

## 자체 검증

- Java 21, MariaDB 11.8.2 loopback 격리 인스턴스(binlog OFF)에서 `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks --no-build-cache` 성공. 단위 151건, 통합 161건 통과.
- 별도 격리 인스턴스(binlog ON)에서 `gradlew.bat :backend:integrationTest --tests '*ProductionCostIT' --tests '*ContentUpgradeIT' --tests '*ProductionGrantsIT'` 성공. 비용 7건, 업그레이드 1건, 운영 권한 2건 통과.
- 빈 DB와 데이터가 들어 있는 V6 DB에서 V9을 적용했다. V5부터 순서대로 적용한 업그레이드에서 기존 원장 체크섬과 콘텐츠 revision을 확인했다. V7 이력 DB 호환과 실제 운영 배포는 미검증이다.
- 비용 검증은 2개 실행자의 동일 효과 발송 경쟁, SELECT 롤백과 재생, 선정 전후 비용 합산, 스위치 및 기존 정산 API 차단, 불명 예약 보존, 초과 청구, 미발송 취소·만료, 증거·영수증 재생과 적용 롤백을 포함한다. 스테이징된 변경에 비밀정보 패턴과 금지 경로를 검사했다.

## 실행기 연결 조건

- 완료 시 실행 트랜잭션이 `production_request` 행을 잠근 뒤 `readCostState(requestId)`를 호출해야 한다. 비용 상태 확인과 active Run 해제, Run 및 요청 종료를 같은 트랜잭션에서 처리한다. 확인과 쓰기 사이에 요청 락을 풀면 별도 정산이 들어올 수 있다. 확인한 `v2/product` 작업본의 `ProductionExecutor.finish`는 비용을 읽은 뒤 완료 트랜잭션을 시작하므로 Claude 담당 수정 및 결합 회귀 검증이 필요하다.
- 발송 전 중단의 복구는 동일 소유 단계의 기존 키, 정규화 요청 해시와 가격을 다시 제시한다. 취소 의도가 있거나 연결 bucket 기간이 끝난 미발송 효과에는 미실행 영수증이 반환된다. 정산이 끝난 영수증은 현재 lease와 해당 단계로 `markReceiptApplied`를 호출하고 단계 갱신을 같은 트랜잭션에서 커밋한다.
- 다음 작업은 Claude 실행기 완료 경합 수정, 두 구현을 연결한 전체 FAKE 경로 및 종료·복구 경합 검증, 최신 PR HEAD CI 및 별도 Codex 세션의 독립 리뷰다. 통합 담당자가 그 결과를 `STATE.md`에 반영한다.

## 실행기 보고서의 공통 계약 확인

검토 기준은 비용 `2211cc0`과 실행기 `9252f39`다.

`gradlew.bat :backend:integrationTest --tests '*ProductionCostIT' --tests '*ProductionRequestApiIT'`를 별도 loopback MariaDB 11.8.2에서 실행해 10건을 통과했다. 추가 검증은 KRW 1 예약의 최초 발송과 재제시, 영수증 적용 시각의 멱등성, 요청 락 보유 중 증거 쓰기 대기, `budget_control`이 다른 연결에서 배타 잠금된 상태의 비용 조회를 다룬다.

| 항목 | 확인 결과 |
|---|---|
| 1. 동일 효과 재제시 | 완료 효과는 영수증을 반환하고 발송됐으나 확정되지 않은 효과는 복구 대기로 둔다. 저장된 응답 증거는 원장 처리만 재생한다. 미발송 `RESERVED`는 최신 lease·예산·취소 상태를 확인하고 조건부 상태 전환에 성공한 호출자만 발송한다. |
| 2. `REJECTED` | 예약 단계에서 기존 효과가 없고 신규 예약이 거절될 때 반환된다. 발송 후 예외가 전파되면 실행기가 복구 대기로 처리한다. 기존 미발송 예약의 취소·기간 만료는 미실행 영수증으로 확정한다. |
| 3. `WAITING`과 `RECOVERY_REQUIRED` | 비용 구현은 복구가 필요한 효과를 `RECOVERY_REQUIRED`로 보고한다. `WAITING`은 현재 반환하지 않으며 실행기는 두 경우 모두 같은 효과를 주기적으로 재제시한다. |
| 4. 영수증 적용 | 현재 소유 단계 확인과 영수증 적용이 실행기 트랜잭션에 참여한다. 이미 적용된 영수증의 적용 시각은 보존된다. 요청 락만 사용한다. |
| 5. 비용 상태 조회와 잠금 | `readCostState`는 같은 READ COMMITTED 트랜잭션에 참여하고 `budget_control`을 잠그지 않는다. 증거 저장·충돌 처리와 원장·영수증 확정은 각각 `budget_control` 공유 락 → 동일 요청 행 락을 획득한다. 증거만 커밋된 상태는 미해결이다. 종료는 요청 행을 잠근 뒤 비용 상태를 검사하고 같은 트랜잭션에서 완료해야 한다. |
| 6. SELECT | 필수 트랜잭션에 참여한다. Topic·Content·Revision·FAKE Run, PRODUCING과 active 포인터, 요청 연결이 함께 롤백된다. 동일 입력은 기존 ID를 반환하며 해시 또는 선택 내용이 바뀌면 `RESOURCE_CONFLICT`다. |
| 7. FAKE 가격 | 기본 `KRW 1 / SUCCESS`는 원장에 1원으로 확정된다. 0원은 비용 포트의 양수 예약 검증에서 거절된다. 실행기 설정은 현재 0을 허용하므로 결합 환경에서는 양수를 지정한다. |
| 8. 활성화 | 실행기는 기본 비활성이며 두 포트 빈이 모두 필요하다. 격리된 결합 테스트에 한해 `ai-studio.production.executor.enabled=true`를 지정한다. 결정론적 직접 실행에는 `auto-start=false`를 함께 지정하고, 별도 worker 기동 검증에만 자동 시작을 사용한다. |

결합 검증은 테스트 대역 포트를 주입하지 않고 안전 브랜치의 두 포트 빈을 사용한다. 격리 DB에 활성 채널, 시스템 일일·월간과 해당 콘텐츠 유형 정책을 준비하고 테스트에서만 지출을 켠다. 기본가로 정상 경로를 완료하면 효과 8건과 확정액 KRW 8, SELECT 연결과 영수증 적용, active Run 해제 및 요청·Run 성공을 확인한다. 두 연결을 사용해 증거 충돌이 먼저 커밋되면 성공이 차단되고, 증거만 저장됐거나 영수증이 미적용이면 요청과 Run이 열린 상태로 유지되는지도 확인한다.

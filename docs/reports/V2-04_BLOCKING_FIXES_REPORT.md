# V2-04 독립 리뷰 BLOCKING B01·B02 수정 보고

- 작업: V2-04 독립 리뷰 HIGH 결함 B01·B02 집중 수정 (Claude Code, Codex 사용량 제한으로 위임)
- 기준: `v2/04-integration` `b9669aae508c71fec7fabeefaab7d1adade09fe0`, 작업 브랜치 `v2/04-blocking-fixes`
- 범위: 두 결함과 직접 회귀 영향만. Migration·OpenAPI·공통 계약 의미·scratch 보호 조건 변경 없음. 사용자 DB 접근·유료 호출·게시 없음.

## 1. B01 — 발송 전 비용 예약 복구 실패

**근본 원인.** IMAGE/TTS 효과는 ① 예약+item `operation_id` 연결 트랜잭션, ② 발송 허가(`permit`) 트랜잭션, ③ 공급자 호출 순서다. ①만 커밋된 뒤 중단되면 item은 `ACTIVE`+`operation_id`, operation은 `RESERVED`(미발송)다. 재시작 후 `EffectStages`는 `operation_id`가 있으면 무조건 `recover`로 보냈고, `ProductionCostService.recover`는 "증거 있음" 또는 "미발송+취소"일 때만 `permit`을 호출했다. 그 외(유효한 미발송, 기간 만료)는 실행 증명(`execution_proof`)이 없어 `RECOVERY_REQUIRED`를 반환해 영구 고착됐다(발송 0건, 예약 열림).

**수정.**
- `EffectStages.java`: `operation_id`가 있어도 item이 `ACTIVE`(실행 증명 전)이면 execute 경로로 다시 제시한다. `reserve`의 기존 `previous` 분기가 같은 효과 키·operation·요청 해시·가격을 검증해 **기존 operation을 그대로 재사용**한다(신규 operation 생성 없음). 이후 기존 `permit`이 판단한다: 유효 → 현재 lease/epoch로 `RESERVED→DISPATCHED` 조건부 갱신(단일 발송), 취소·기간 만료 → `NOT_EXECUTED` 영수증과 예약 반환, 이미 발송(`DISPATCHED`/`UNKNOWN`) → `RECOVERY_REQUIRED`(재발송 없음). `PROVEN` 이후 item은 기존대로 `recover`만 한다.
- `ProductionCostService.java`: `permit`에 `mayDispatch` 인자(기본 true)를 추가했다. `recover`는 미발송 operation 전체에 대해 `permit(mayDispatch=false)`을 호출한다. 취소·기간 만료는 같은 미발송 종료 영수증으로 닫고, 발송 가능 상태이면 발송 권한을 얻지 않고 `RECOVERY_REQUIRED`로 남겨 다음 execute 제시를 기다린다(미디어 준비 불가 상태의 recovery-only 경로에서도 기간 만료가 종료됨).
- 유지: 이미 발송되었거나 불명인 operation을 미발송으로 간주하는 경로 없음. lease·epoch·fencing(`requireStep`, `mapper.dispatch`의 owner/epoch/만료/취소/지출차단 조건), 요청·일·월 한도, 단일 dispatch, 정산 재생은 기존 코드 그대로다.

## 2. B02 — QC 마지막 시도의 합격 결과 복구 실패

**근본 원인.** QC 성공은 ① `LocalMediaAssets.publish`가 QC_REPORT·MANIFEST 저장 후 QC item을 `APPLIED`로 커밋, ② 실행기가 별도 트랜잭션에서 MP4·QC 묶음 `FINAL` 확정과 step 성공을 커밋한다. 세 번째 시도에서 ①과 ② 사이에 중단되면 step은 `RUNNING`, attempts=3이다. `StepPlanner`는 RUNNING 로컬 step을 attempts만으로 판단해 `Fail(INTERRUPTED)`를 반환했고, 정상 결과가 있는데도 요청이 실패하고 공개가 차단됐다.

**수정.**
- `StepPlanner.java`: `persisted` 판정 인자를 받는 오버로드를 추가했다. 중단된(RUNNING) step에 커밋된 결과가 있으면 취소 판정 다음에 `Recover`를 반환한다(새 시도 미계산). 결과가 없으면 기존대로 attempts 3에서 `Fail(INTERRUPTED)`. RETRY_WAIT 소진 등 다른 경로는 불변.
- `MediaFlow.java`: `persisted(requestId, stage)` — 해당 로컬 단계 item이 모두 `APPLIED`인지 읽기 전용 확인.
- `ProductionExecutor.java`: 펜싱된 snapshot 트랜잭션에서 RUNNING 로컬 미디어 step의 `persisted` 집합을 계산해 planner에 전달한다. 복구는 기존 `runMedia(recover=true)`를 그대로 쓴다: attempts 증가 0, QcStage는 APPLIED item에서 즉시 반환(재렌더·새 QC·공급자 호출 없음), 기존 `verifyQc`가 MP4·QC_REPORT·MANIFEST 길이·SHA-256을 다시 확인한 뒤에만 기존 성공 트랜잭션(`finalizeQc`+`succeedStep`, lease 펜싱)을 실행한다. 손상 시 기존 `MEDIA_QC_INTEGRITY`로 실패하고 MP4는 STAGED로 남아 비공개다. 미디어 준비 불가 상태에서는 마지막 시도를 소모하지 않도록 lease만 반환한다.

## 3. 변경 파일

| 파일 | 이유 |
|---|---|
| `backend/.../media/product/EffectStages.java` | B01 미발송 예약 item의 execute 재제시(같은 operation 재사용) |
| `backend/.../cost/ProductionCostService.java` | B01 recover의 미발송 종료(`permit` 발송 금지 모드) |
| `backend/.../execution/StepPlanner.java` | B02 영속 결과 복구와 새 실행 구분 |
| `backend/.../execution/MediaFlow.java` | B02 로컬 단계 영속 결과 확인 |
| `backend/.../execution/ProductionExecutor.java` | B02 snapshot에 영속 결과 전달, 준비 불가 시 대기 |
| `backend/src/test/.../execution/StepPlannerTest.java` | B02 planner 단위 회귀 |
| `backend/src/test/.../media/product/MediaStagesTest.java` | B01 결함 동작을 고정하던 기존 단위 테스트를 수정 의미에 맞게 분리 |
| `backend/src/test/.../execution/PlanningExecutionIT.java` | B01·B02 격리 DB 회귀(CI 실행, 미디어 작업 test double) |
| `backend/src/test/.../execution/ProductMediaIT.java` | B01·B02 실제 EffectStages·QcStage·FFmpeg 회귀(환경 게이트). 테스트 전용 FFmpeg runner에 hook과 Windows 캡처 파일 삭제 재시도 추가 |

## 4. 장애 주입 방법과 한계

- 격리 MariaDB 11.8.2(127.0.0.1:3307, 임시 데이터 디렉터리)와 실제 트랜잭션 경계를 사용했다. mock 분기 검증이 아니다.
- B01: root 트리거가 해당 단계 첫 operation의 `RESERVED→DISPATCHED` 갱신에서 `SIGNAL`하여 permit 트랜잭션 전체를 롤백한다. 커밋된 상태는 예약+바인딩뿐이다. 실행기가 남긴 park 상태를 JVM 종료가 남기는 상태(step RUNNING, `stopped-worker` lease 만료)로 바꾼 뒤 새 worker로 복구한다.
- B02: QC 1·2차 시도는 로컬 실패(test hook)로 RETRY, 3차는 실제 통과·커밋 후 트리거가 QC step 성공 갱신을 중단시킨다. 예외가 실행기 밖으로 나가 lease가 그대로 남는 것은 JVM 종료와 같은 영속 상태다. 이후 lease를 만료시키고 새 worker로 복구한다. 결과 없는 마지막 시도는 test double에서 `Error`(모든 catch 통과)를 던져 중단을 재현했다.
- 한계: 실제 OS 프로세스 강제 종료(kill)는 수행하지 않았다. 위 방법은 커밋된 DB·파일 상태가 동일한 영속 상태 기반 주입이며, 트랜잭션 도중 kill은 롤백과 동일하다는 MariaDB 원자성에 의존한다. 실제 JVM 강제 종료 후 Windows Job 자식 종료는 기존 공통 검증 범위다.

## 5. 수정 전 재현 (기준 SHA에 새 테스트만 복사, 임시 worktree)

`PlanningExecutionIT`·`ProductMediaIT` 50건 중 8건 실패:

| 테스트 | 기준 SHA 결과 |
|---|---|
| ProductMediaIT B01 IMAGE / TTS 재개 | 요청 RECOVERY_REQUIRED, 해당 step `MEDIA_EFFECT_PENDING` 고착 |
| ProductMediaIT B01 기간 만료 | RECOVERY_REQUIRED 고착(종료 영수증 없음) |
| PlanningExecutionIT B01 기간 만료(미디어 준비 불가) | RECOVERY_REQUIRED 고착 |
| ProductMediaIT·PlanningExecutionIT B02 마지막 시도 결과 복구 | 요청 FAILED, QC `INTERRUPTED` attempts 3 |
| PlanningExecutionIT B02 손상 QC_REPORT / MANIFEST | `INTERRUPTED`로 실패(손상 검사 미도달, 공개는 차단) |

B01 취소·이미 발송, B02 결과 없음, PlanningExecutionIT의 B01 정상 재개·기간 만료(준비 가능)는 기준에서도 통과하는 계약 회귀 테스트다(이 test double은 product `EffectStages` 라우팅을 거치지 않는다).

## 6. 수정 후 집중 테스트

| 요구 | 위치 |
|---|---|
| B01-1~5 IMAGE/TTS 주입, 새 lease·worker, 같은 operation·키, 발송 정확히 1건 | `ProductMediaIT#productEffectReservedBeforeDispatchResumesOnTheSameOperationAfterRestart`, `PlanningExecutionIT#mediaReservationCommittedBeforeDispatchResumesOnTheSameOperationAfterRestart` |
| B01-6·8·9 기간 만료 발송 0, 미발송 영수증 1, 예약 RELEASED·잔여 0 | `ProductMediaIT#productImageReservationIsClosedWithoutDispatchWhenItsBudgetPeriodEndsBeforeRestart`, `PlanningExecutionIT#undispatchedMediaReservationIsClosedWithoutDispatchWhenItsBudgetPeriodEnds`(준비 가능/불가) |
| B01-7 취소 발송 0 | `PlanningExecutionIT#undispatchedMediaReservationIsClosedWithoutDispatchWhenTheRequestIsCancelled` |
| B01-10 이미 발송된 operation 재발송 없음 | `PlanningExecutionIT#dispatchedMediaOperationWithoutEvidenceIsNeverResentAfterRestart` |
| B01-11 stale worker 발송 불가 | 위 재개 테스트 두 곳(만료 lease owner의 `execute` 거부, 공급자 미도달) |
| B01-12 중복 정산 없음 | 모든 B01 테스트의 `production:settle:<op>` 1건 |
| B02-1~9 세 번째 시도 PASS·저장·APPLIED 후 주입, 해시 재검증, 네 번째 시도 없음, 재렌더 없음, 재생·다운로드 | `ProductMediaIT#productQcPassCommittedOnTheLastAttemptIsFinalizedAfterRestartWithoutRerender`(복구 중 FFmpeg 프로세스 0, RENDER attempts 불변, Range 206, 다운로드 SHA 일치, QC 보고서 PASS), `PlanningExecutionIT#qcResultCommittedOnTheLastAttemptIsFinalizedAfterRestartWithoutAFourthAttempt` |
| B02-10 손상 QC_REPORT/MANIFEST 공개 차단 | `PlanningExecutionIT#damagedQcResultOfTheLastAttemptKeepsPublicationBlocked` |
| B02-11 결과 없는 마지막 시도 기존 실패 | `PlanningExecutionIT#lastQcAttemptStoppedBeforeItsResultStillFailsAndPublishesNothing` |
| B02-12 stale worker·중복 확정 방지 | B02 복구 테스트(이전 lease `drive` → LeaseLost, 종료 후 재claim 없음, MP4 FINAL 1건) |
| planner 단위 | `StepPlannerTest#storedResultOfTheLastAttemptIsRecoveredWithoutAFourthAttempt` |

## 7. 재현 명령 (Codex 집중 검증용)

격리 MariaDB는 [로컬 테스트 가이드 §2](../guides/LOCAL_TEST_GUIDE.md)로 띄운다. 비밀번호는 임시값.

```bash
export AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<임시>
# CI 동등(FFmpeg 불필요): B01·B02 계약 회귀 + planner 단위
./gradlew.bat :backend:test --tests "*StepPlannerTest" :backend:integrationTest --tests "*PlanningExecutionIT"
# 실제 product 단계(FFmpeg 9.1, 권리 확인된 fixture, 글꼴 필요)
AI_STUDIO_TEST_FFMPEG_DIR=<ffmpeg bin> AI_STUDIO_TEST_FIXTURE_ROOT=<root>/fixtures \
AI_STUDIO_TEST_FONT=<root>/downloads/font/NotoSansKR-Bold.otf \
  ./gradlew.bat :backend:integrationTest --tests "*ProductMediaIT"
```

수정 전 재현: `git worktree add --detach <tmp> b9669aae508c71fec7fabeefaab7d1adade09fe0` 후 이 브랜치의 `PlanningExecutionIT.java`·`ProductMediaIT.java`만 복사해 같은 명령을 실행한다(`StepPlannerTest`는 새 오버로드를 쓰므로 제외).

## 8. 회귀 결과와 미검증

- `gradlew.bat --no-build-cache --continue :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks`(격리 MariaDB 11.8.2, FFmpeg 9.1 gyan full_build, 권리 확인된 `ifnova-v204-1` fixture, Noto Sans KR): **BUILD SUCCESSFUL**. 단위 331건(실패 0, 건너뜀 1: Windows의 `LinuxMediaProcessTest`), 통합 277건(실패 0, 건너뜀 1: 기존 환경 게이트 `liveEdgeConnectsTheFrontendToTheActualServer`), bootJar 생성. 기존 두 소재 실제 MP4 제작 IT(`producesAQcPassedShort...`)도 함께 통과.
- 기존 단위 테스트 `MediaStagesTest#itemsWithAnOperationAreOnlyRecovered`는 B01의 결함 동작(ACTIVE+operation → recover만)을 고정하고 있어, `PROVEN` item은 recover만(`itemsPastTheExecutionProofAreOnlyRecovered`), `ACTIVE` 바인딩 item은 공유 port에 재제시(`boundItemsBeforeTheExecutionProofArePresentedAgainToTheSharedPort`)로 나눠 갱신했다.
- `git diff --check` 통과. 변경 파일 Gitleaks(dir) no leaks. 저장소 전체 dir 스캔의 1건은 미변경 `BudgetMapper.java`의 SQL `subject_key` 기존 오탐이다.
- 프런트엔드·OpenAPI는 변경이 없어 재실행하지 않았다.
- 미검증: 실제 OS 수준 JVM kill 경계(§4 한계), Linux 실행(기존 안전 차단 상태), 실제 유료 공급자. 기존 NON_BLOCKING 리뷰 항목은 범위 밖이다.

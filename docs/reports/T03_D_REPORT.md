# T03-D 중간 구현 보고서

## 기준과 소유 범위

- 2026-11-26 KST. `origin/t03/integration`의 PR #3 병합 SHA `dab7013c4557ba87d4440dfc86417344bb2c4e1d`에서 `t03/d-budget` 및 별도 `ai-studio-t03-d` worktree를 만들었다. 작업은 로컬 미커밋 상태다.
- [C/D 공통 인터페이스](../plans/t03-final/T03_CD_INTERFACE_CONTRACT.md)와 `backend/src/main/java/com/aistudio/operation/contract/`의 세 Java 선언을 작성했다. Codex가 공통 파일을 담당한다. C 브랜치 HEAD도 같은 기준 SHA다. C 작업 트리의 미커밋 V7은 읽기 전용으로 대조했으며 Job/Attempt 참조 키와 생성 순서는 일치했다. C adapter와 V7의 검증 가능한 확정 커밋은 아직 없다. 따라서 인터페이스 선언은 C 대조 대상으로 제출하며 공통 배포 SHA는 미확정이다.
- C 제공 요구는 Job의 channel/content/run 소유권, `automation_job(id)`, `automation_job(run_id,id)`, `job_attempt(job_id,id)`, 현재 attempt 연계용 `job_attempt(job_id,attempt_no)`, lease·취소·fail-fast 및 독립 P12 구현이다. C worktree와 V7은 배정 범위 밖이다.

## 구현 및 검증

- `ReservationPolicies.java`에서 SYSTEM DAY/MONTH 필수, 기존 채널 기간 정책, 적용 가능한 전역 및 채널별 CONTENT_TYPE LIFETIME을 선택한다. ID 정순으로 반환하고 필수 정책 누락을 차단한다. 동결 ID의 집합 비교는 한도 revision 개정을 허용하고 정책 교체를 거절한다. 기존 `BudgetReservationService.java`는 동일한 SYSTEM 검증을 공유한다. 콘텐츠 예약 TX의 연결은 후속 작업이다.
- `V8__create_content_budget_contract.draft.sql`은 기존 v0 예약을 유지하면서 콘텐츠 예약 버전과 소유권, 정책당 콘텐츠별 bucket UNIQUE와 UTC sentinel, 동결 정책, 초기 정책 세대, 선택 증거와 기대 bucket을 정의한다. 네 증거 테이블에 UPDATE/DELETE 금지 트리거를 정의했다. 초기 배치는 V8 미적용 상태에서 체크섬 등록 및 binary collation 규칙에 걸렸다. 아직 적용 실측을 하지 않은 V8을 승인된 마이그레이션으로 등록하지 않고 설계 폴더에 격리했다. V7 합류 후 실제 적용 검증과 함께 V1~V6 값은 유지한 채 정확 비교 컬럼 및 V8 체크섬을 등록해야 한다.
- `ReservationPoliciesTest.java`는 정책 선택, 시스템 및 LIFETIME 누락, 동결 집합 변경을 검증한다. 격리 MariaDB 11.8.2의 별도 포트에서 `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks --no-build-cache`로 기존 V1~V6 통합 153건, 단위 153건 및 JAR 빌드에 성공했다. 마지막 Java 선언과 동결 집합 테스트를 추가한 후 `gradlew.bat :backend:test :backend:bootJar`를 재실행해 단위 154건, 실패·오류·스킵 0건과 JAR 빌드를 확인했다.
- 제품 OpenAPI, 생성 타입, V1~V6 및 기존 작업 트리 변경 없음. V8 적용·성능 측정·CI는 미실행이다.

## 후속 구현 및 차단 조건

- D의 다음 작업은 정책 변경과 동결 증거의 원자적 기록, 콘텐츠 예약의 기대/실제 링크와 history 세대 검증, 관리자 영속 정산 및 기존 쓰기 API 전환이다. 지금 작성한 순수 선택 로직과 스키마만으로는 동결·예산 집행이 활성화되지 않는다.
- 실제 V7과 C의 Job/Attempt 키 및 공통 선언 대조가 끝나야 operation·intent·dispatch·증거·receipt FK, TX4, TX7/P12, Q0/R10을 연결할 수 있다. 제한된 공통 SHA 배포는 별도 커밋 승인에 따른다.
- 실제 V7을 확보한 후 C와 별도의 포트를 쓰는 임시 MariaDB에서 신규 DB 및 V5/V6/V7 업그레이드, 권한·불변 트리거, 두 커넥션 락 경합과 커밋 경계 장애 주입을 수행한다. 사용자 개발 DB와 임시 호환 테이블은 사용하지 않는다.
- 지출 허가·회수 경합, 응답 유실 복구, 만료 Worker 증거, 정합성 검사 세대, CR-02, budgetSummary, 운영 최소 권한 및 1k/10k/100k 성능은 후속 검증 대상이다. T03-D 상태는 진행 중이다.

## 중간 검토 후 공통 계약 배포 준비

- ChatGPT CONDITIONAL_APPROVE를 반영했다. 공통 선언 및 계약 문서에 CD-G01~05를 명시하고, 미확인 비용 조회를 NONE과 구분했다. C 미커밋 V7의 worker 선행 생성, Job 소유권 키 및 Attempt 대체 키는 일치한다. C의 고정 V7 커밋과 QueueExecutionPort 구현은 확인되지 않았다.
- 공통 파일 네 개의 선택 스테이징·검사·cherry-pick 절차를 [계약 문서](../plans/t03-final/T03_CD_INTERFACE_CONTRACT.md#공통-커밋-전달-절차)에 기록했다. 별도 사용자 커밋 승인 대기 중이며 stage·commit·push는 수행하지 않았다. C 작업 트리는 읽기 전용으로 확인했다.
- 독립 비용 작업으로 v1 기대/실제 링크의 양방향 차이와 양쪽에서 동시에 누락된 정책을 찾는 ReservationBucketEvidence를 추가했다. 정책 세대 기반 증거를 읽는 DB 정합성 경로와 콘텐츠 예약·관리 정산·기존 쓰기 API 전환은 아직 연결 전이다.
- java 21로 gradlew.bat :backend:test :backend:bootJar 재실행 결과 단위 157건 성공, 실패·오류·스킵 0건. git diff --check 및 공통 파일 gitleaks 검사 통과. 이번 단계 DB와 CI는 미실행이며 V8은 초안 상태다. T03-D는 진행 중이다.

## 공통 계약 로컬 커밋

- 사용자 승인 범위의 Java 인터페이스 3개와 계약 문서만 `6cd0c4e2c7618f90249f670f000938113c37d456`에 커밋했다. staged diff 및 gitleaks 검사에 통과했다. 커밋 당시 별도 비용 코드·V8 초안·보고서는 바이트 단위로 보존했다.
- 같은 저장소의 C 담당자는 위 SHA를 `git show --stat`과 `git ls-tree -r`로 검사하고 작업 트리의 공통 경로 및 staged 변경을 확인한 뒤 자기 worktree에서 cherry-pick한다. 푸시는 실행하지 않았다.
- 이어서 비용 정책 선택에 예약 세대 당시의 마지막 스냅샷을 재구성하는 로직을 추가했다. 그 이후의 정책 비활성화·신규 활성화가 과거 예약에 소급되지 않음을 단위 테스트로 확인했다. 최종 `gradlew.bat :backend:test :backend:bootJar` 성공, 단위 158건, 실패·오류·스킵 0건.
- C의 선택 적용 및 검증 가능한 V7 커밋은 아직 없다. V8 적용과 실제 TX4/P12 통합은 대기한다. 이번 작업에서 DB 및 CI는 실행하지 않았다.

# Claude Code 인수 요청

T03-C 담당 Claude Code에게 요청한다. D의 공통 계약 커밋 6cd0c4e2c7618f90249f670f000938113c37d456을 동일 저장소의 C worktree에 선택 적용해 주세요. 적용 전 공통 네 경로 및 staged 변경을 확인해 주세요. 충돌 시 기존 변경을 보존하고 중단해 주세요.

| 파일 | Git blob SHA |
|---|---|
| OperationCostPort.java | 992dacbb8c110fa99fdfb32ee1b24ee6d417a8eb |
| QueueExecutionPort.java | 40837350c7757a267ef86ff3acc1e417f29fe9f2 |
| OperationLifecyclePort.java | c5958982bd0a02d7f18e77c2ac0241445c2c83ca |
| T03_CD_INTERFACE_CONTRACT.md | c7ad4a556bdadf3d08777edb5eb1f91de23b77a7 |

C 담당자가 git cherry-pick 후 git ls-tree -r HEAD로 대상 네 blob을 대조해 주세요. 적용한 C 커밋과 검증 가능한 V7 기준을 회신해 주세요.

## 독립 비용 구현 후속

- 활성 Claude 세션으로 직접 전달할 수 있는 채널을 확인하지 못해 위에 선택 적용·blob 검증 요청을 남겼다. C HEAD는 여전히 dab7013이며 선정 SHA 또는 검증 가능한 V7 기준을 받지 못했다.
- ContentReservationDecision은 선택된 잠금 행에서 동결 LIFETIME 집합, 정책 세대, 적용 revision·한도·기간·bucket ID 및 선택 지문을 계산한다. 기간·콘텐츠 소유권·현재 한도 검증을 통과한 행만 계획에 포함한다. DB reservation의 원자적 기록과 관리자 정산·정합성 검사 실행 경로는 아직 연결 전이다.
- gradlew.bat :backend:test :backend:bootJar 성공. 단위 160건, 실패·오류·스킵 0건. git diff --check 통과. DB·CI 미실행. 계약 커밋과 작업 트리 소유권을 유지했다.

## 관리자 정산 독립 구현

- SettlementIntentDecision에 M1 동일 키 재생·충돌, 미완결 guard, 예약 버전과 직전 영수증 확인, 미확정 큐 비용의 수동 정산 차단을 추가했다. M2의 완료 요청은 예약의 현재 상태가 바뀌어도 저장된 영수증을 재생한다.
- V8 초안에 관리자 정산 요청·guard, 검사 실행·상세 기록 및 불변 보호 트리거를 추가했다. 애플리케이션 Migration 리소스에는 배치하지 않았고 SQL 적용, 운영 권한 검증, M1/M2의 원장·영수증 원자 저장 및 기존 API 전환은 미검증이다.
- gradlew.bat :backend:test :backend:bootJar 성공. 단위 163건, 실패·오류·스킵 0건. git diff --check 통과. C 작업 트리와 공통 커밋 6cd0c4e를 유지했다. C 구현 보고서 수신 후 V7 고정 및 C/D Adapter 통합을 진행한다.

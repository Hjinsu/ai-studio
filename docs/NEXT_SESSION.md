# 다음 작업일 인수인계

2026-11-26 KST · 목적은 개인용 AI 영상 자동 제작 및 수익 창출 가능성 검증이다. 기존 승인 기준은 [STATE.md](../STATE.md), [TASKS.md](../TASKS.md), [DECISIONS.md](../DECISIONS.md)를 따른다. 이번 WIP는 작업 보존용이다.

## 상태

| 단계 | 현재 상태 |
|---|---|
| T01 | DONE. 프로젝트 부트스트랩, MariaDB/Flyway, CI |
| T02 | DONE. 인증·채널·비용 원장 |
| T03 | A 최종 설계 승인. B DONE, PR #3 병합. C/D 조건부 병렬 진행, E/F BLOCKED |
| T04 | TODO. FAKE Provider, 조사·대본·스토리보드 |
| T05 | TODO. 이미지·TTS·자막·로컬 렌더링 |
| T06 | TODO. 검수·승인·관리 UI |
| T07 | TODO. 실제 Provider 소액 실측에 별도 승인 필요 |
| T08 | TODO. OAuth·업로드·예약에 별도 승인 필요 |
| T09 | TODO. 성과 분석·단일 채널 실험 |
| T10 | TODO. 트렌드·멀티채널 확장 |

## Git 기준

| 브랜치 | 종료 시점 기준 |
|---|---|
| `origin/main` | `47ef69731a599c570846c1482dcabf8ead8fe2e4` |
| `origin/t03/integration` | `dab7013c4557ba87d4440dfc86417344bb2c4e1d` |
| `origin/t03/b-domain` | `65bde456439c7f62997a9f7cdda20dfe82f3e4bf` |
| `t03/c-queue` | 집 PC 로컬 HEAD `dab7013c4557ba87d4440dfc86417344bb2c4e1d`. Claude의 미커밋 작업이 보인다. 원격 브랜치 저장 및 커밋 SHA 미확인. |
| `origin/t03/d-budget` | 이 문서를 포함한 `wip: checkpoint T03-D budget safety development` 커밋. 전체 SHA는 아래 명령에서 확인한다. |
| C/D 공통 계약 | `6cd0c4e2c7618f90249f670f000938113c37d456`. D의 조상에 포함. C 반영·blob 검증은 미확인. |

## 구현과 검증

- **C** Claude의 로컬 중간 보고서는 V7 큐 스키마·참조 키와 상태 전이·파이프라인의 일부를 확인하는 근거다. 그 보고서 기재 기준 단위 169건, 격리 MariaDB 통합 163건씩(binlog OFF/ON) 통과. Codex가 재실행하거나 최신 C 트리 전체와 일치한다고 확인한 결과는 아니다. 정확한 C 범위·최종 실측은 Claude의 최종 보고서와 저장 SHA를 받아 확인한다.
- **D** 정책 선택·과거 세대 재구성·LIFETIME 동결 검사, 잠긴 Bucket에서 예약 스냅샷 계산, 기대/실제 링크 비교, 관리자 정산 검증 및 영수증 재생 로직이 있다. 최신 D 단위 163건과 bootJar 통과. 이전 D 격리 MariaDB 통합 153건은 V1~V6 회귀 검증이며 이후 D 변경까지 검증한 결과가 아니다. V8, TX4/P12, 최신 WIP CI는 미검증이다. [D 중간 보고](reports/T03_D_REPORT.md)
- **기존 CI** B 종료 CI `45639897532` 7/7 성공. 병합 트리는 검증된 HEAD와 동일했다. C/D 검증을 대신하지 않는다.
- **Migration** V1~V6 변경 없음. C의 V7은 집 PC Claude worktree의 실제 SQL 파일이며 원격 검증 기준 미확정. V8은 설계 폴더의 SQL 초안으로 Flyway 위치에 없다. D는 V8을 어느 DB에도 적용하지 않았다. 집·회사 개발 DB의 실제 schema history는 이번 종료에서 조회하지 않았다.
- **보호 조건** 개발 DB 3306을 테스트 또는 임의 적용에 쓰지 않는다. 테스트는 Testcontainers 또는 별도 포트·데이터 폴더의 loopback 임시 MariaDB를 사용한다. V7/V8 적용 검증은 고정된 C 기준으로 격리 DB에서 수행한다. 기존 Migration·원장 불변성, 지출 차단, 실제 유료 호출·업로드의 별도 승인 조건을 유지한다. [로컬 테스트 가이드](guides/LOCAL_TEST_GUIDE.md)

## 다음 순서

1. 회사 PC에서 D WIP를 가져와 계약 SHA와 브랜치를 확인한다. Claude에게 C 저장 SHA와 최종 구현 보고서를 받는다. 집 PC의 C 미커밋 변경은 Git fetch로 전달되지 않는다.
2. Claude가 C에서 공통 커밋만 선택 적용하고 네 파일의 blob을 대조한다. C의 V7 순서, Job/Attempt 키와 어댑터 계약을 대조한다. [공통 계약](plans/t03-final/T03_CD_INTERFACE_CONTRACT.md)
3. D의 동결 및 정책 history 영속화, 콘텐츠 예약 트랜잭션, 관리 정산 원장·receipt 원자 저장, 기존 API 전환, 세대별 정합성 검사와 CR-02를 연결한다.
4. 검증 가능한 C 기준을 확보한 뒤 격리 DB에서 V7/V8과 업그레이드·권한·두 커넥션 락·장애 복구를 검증하고 TX4/P12를 연결한다. R10의 비용·receipt 종료 게이트도 함께 검사한다.
5. [개인용 MVP 제안](../AI_STUDIO_PERSONAL_MVP_PLAN.md)을 사용자와 검토한다. 제품 범위 축소는 승인 후 반영한다.

## 회사 PC 재개 명령

기존 저장소 루트에서 수행한다. 로컬 동명 브랜치 또는 worktree가 이미 있으면 그 상태를 먼저 검사하고 그대로 재사용한다.

```powershell
git fetch origin
git worktree list
git ls-remote --heads origin main t03/integration t03/c-queue t03/d-budget
git worktree add -b t03/d-budget ../ai-studio-t03-d origin/t03/d-budget
git -C ../ai-studio-t03-d status -sb
git -C ../ai-studio-t03-d log -2 --oneline
git -C ../ai-studio-t03-d merge-base --is-ancestor 6cd0c4e2c7618f90249f670f000938113c37d456 HEAD
```

C 저장 및 공유가 확인된 뒤 C 담당자만 필요에 따라 아래를 실행한다.

```powershell
git fetch origin
git worktree add -b t03/c-queue ../ai-studio-t03-c origin/t03/c-queue
```

Java 21 환경에서 D의 초기 재검증은 `gradlew.bat :backend:test :backend:bootJar`로 수행한다. 기존 업무 DB·개발 DB 비밀번호, .env, API Key 또는 DB 덤프를 Git으로 옮기지 않는다.

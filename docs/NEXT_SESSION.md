# 회사 PC 다음 세션 — AI Studio v2

2026-11-26 KST · V2-01·V2-02 DONE · V2-03 HOLD, F05 수치 예외 제거 및 별도 독립 리뷰 대기.

## 기준과 보존

- 재개 기준은 이 종료 기록이 반영된 최신 `origin/v2/integration`이다. V2-02 제품 병합 기준은 `8ca054dc489d55e763a0f91790366df282550b27`이며 승인 SHA `9acab98483539402ee61e9695a3d3ccf10b66f00`과 전체 Git 트리가 일치한다.
- 기존 원격 `main` `47ef69731a599c570846c1482dcabf8ead8fe2e4`, `t03/integration` `dab7013c4557ba87d4440dfc86417344bb2c4e1d`, C `1e888767a61407a7e9176df0e362df9d6143f1b2`, D `3a976e1fbb1a5c8fbda50257b12643ff6cfbca3a`를 보존한다. C/D 병합이나 공통 계약 cherry-pick은 v2 선행 작업이 아니다.
- 집 PC의 기존 D 미커밋 MVP 문서와 로컬 integration의 미추적 리뷰는 원래 worktree에 별도 보존되어 있다. v2 문서는 새 브랜치에서 가져온다.
- [승인된 기획](v2/README.md) · [품질·수익화 요구사항](v2/05_CONTENT_QUALITY_AND_MONETIZATION.md) · [현재 배정](../TASKS.md) · [기존 코드 검토](v2/V2-00_REPOSITORY_REVIEW.md)

## 재개 명령

기존 저장소에서 다음을 실행한다.

```powershell
git fetch origin
git status --short --branch
git worktree list
git branch -avv
git ls-remote --heads origin v2/integration
git show --no-patch --format='%H %s' origin/v2/integration
```

동명 로컬 브랜치/worktree와 대상 경로가 없을 때만 생성한다. 기존 worktree가 있으면 해당 경로의 상태와 HEAD를 확인하고 재사용한다.

```powershell
git worktree add --track -b v2/integration ../ai-studio-v2 origin/v2/integration
git -C ../ai-studio-v2 status --short --branch
```

## 다음 작업

**V2-03 자료 조사·대본·장면 구성**은 `v2/review-v2-03`의 별도 `ai-studio-v2-review-03` worktree에서 이어간다. F05 수치 예외를 제거했다. 다음 작업은 Draft PR #6 최신 HEAD 및 동일 SHA의 CI를 확인하고, 구현 세션과 분리된 읽기 전용 독립 리뷰에서 수치 검수와 구버전 영수증 재생을 확인하는 것이다. HOLD 해제와 병합은 독립 리뷰 APPROVE 및 사용자 승인 후 진행한다. 미디어 프로필은 V2-04 별도 승인 범위다. [결합 검증 보고서](reports/V2-03_INTEGRATION_REPORT.md) · [승인 설계](v2/V2-03_IMPLEMENTATION_PLAN.md)

```powershell
git fetch origin
git rev-parse origin/v2/review-v2-03
gh pr view v2/review-v2-03 --json headRefOid,isDraft,statusCheckRollup
```

## 환경 및 검증 상태

- Gradle 캐시의 JDK 21.0.13을 사용한다. 회사 PC의 Node 24.21.0과 MariaDB 11.8.2 서비스는 확인했다. FFmpeg·FFprobe 준비는 V2-04에서 수행한다. 회사 정책이 허용하는 환경에서 환경변수는 `.env.example`로 재설정한다. 비밀값·DB 덤프·미디어는 Git에 넣지 않는다.
- V1~V6는 기준 커밋 원본이다. C V7은 보고상 격리 DB에만 적용됐고 D V8은 초안이다. 집·회사 사용자 DB의 실제 이력 및 기존 백업 여부는 미확인이다. 이번에 DB 접근이나 백업을 수행하지 않았다.
- V2-01 백엔드 단위 165건·격리 통합 200건, FAKE 11단계와 [GitHub CI 7/7](https://github.com/Hjinsu/ai-studio/actions/runs/45737242958)을 확인했다. V9 사용자 DB 적용과 실제 유료 호출·YouTube 게시는 별도 승인 대상이며 실행기 기본값은 비활성이다.
- V2-02 [CI 7/7](https://github.com/Hjinsu/ai-studio/actions/runs/45758360558) 및 격리 MariaDB 독립 회귀 17건 통과. HOLD 해소와 후속 개선은 [종료 기록](../STATE.md#v2-02--최종-승인-및-종료-2026-11-26-kst-done)을 참조한다.

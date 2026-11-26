# 회사 PC 다음 세션 — AI Studio v2

2026-11-26 KST · V2-00 사용자 승인 완료 · V2-01 설계 및 착수 승인 완료.

## 기준과 보존

- 분기 원점 `dab7013c4557ba87d4440dfc86417344bb2c4e1d`. v2의 개발 기준은 `origin/v2/integration`의 **docs: establish AI Studio v2 development baseline** 커밋이다. 아래 명령으로 전체 SHA를 조회한다.
- 기존 원격 `main` `47ef69731a599c570846c1482dcabf8ead8fe2e4`, `t03/integration` `dab7013c4557ba87d4440dfc86417344bb2c4e1d`, C `1e888767a61407a7e9176df0e362df9d6143f1b2`, D `3a976e1fbb1a5c8fbda50257b12643ff6cfbca3a`를 보존한다. C/D 병합이나 공통 계약 cherry-pick은 v2 선행 작업이 아니다.
- 집 PC의 기존 D 미커밋 MVP 문서와 로컬 integration의 미추적 리뷰는 원래 worktree에 별도 보존되어 있다. v2 문서는 새 브랜치에서 가져온다.
- [승인된 기획](v2/README.md) · [품질·수익화 요구사항](v2/05_CONTENT_QUALITY_AND_MONETIZATION.md) · [V2-01 배정](../TASKS.md) · [기존 코드 검토](v2/V2-00_REPOSITORY_REVIEW.md)

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

검증된 공통 기준 v2/common 커밋을 기준으로 별도 worktree의 `v2/product`(Claude)와 `v2/safety`(Codex)를 사용할 수 있다. Claude는 고정 단계 실행·무효과 테스트·취소·재시작 복구를, Codex는 소재 선정 전 제작 요청 ID·동일 제작 한도·단일 dispatch·불명 결과·정산/receipt 및 공유 DB/API를 맡는다. V9을 사용한다. V7 이력 DB는 별도 호환 검증 대상이다. [승인된 공통 계약](v2/V2-01_IMPLEMENTATION_PLAN.md) 제품 목표는 채널 설정 후 소재 입력 없이 실제 IFNOVA 영어 Shorts(40~60초, 1080×1920)와 게시 정보·자동 품질 검사 결과·출처·비용·manifest를 완성하는 것이다. FAKE는 회귀 검증에 사용한다.

병렬 작업 재개 시 `v2/common`은 공통 계약 기준으로 사용하고 `v2/product`와 `v2/safety`는 각각 이미 배정된 worktree에서 작업한다. 담당과 금지 경로는 [Claude 착수 지시](v2/V2-01_IMPLEMENTATION_PLAN.md#claude-착수-지시)와 구현 계획을 따른다.

## 환경 및 검증 상태

- Gradle 캐시의 JDK 21.0.13을 사용한다. 회사 PC의 Node 24.21.0과 MariaDB 11.8.2 서비스는 확인했다. FFmpeg·FFprobe 준비는 V2-04에서 수행한다. 회사 정책이 허용하는 환경에서 환경변수는 `.env.example`로 재설정한다. 비밀값·DB 덤프·미디어는 Git에 넣지 않는다.
- V1~V6는 기준 커밋 원본이다. C V7은 보고상 격리 DB에만 적용됐고 D V8은 초안이다. 집·회사 사용자 DB의 실제 이력 및 기존 백업 여부는 미확인이다. 이번에 DB 접근이나 백업을 수행하지 않았다.
- V2-00은 문서 전용이며 제품 테스트·CI 결과 확인은 수행하지 않았다. V2-01에서 [격리 테스트 가이드](guides/LOCAL_TEST_GUIDE.md)에 따라 단위·빌드 및 격리 통합 검증을 시작한다. 실제 유료 호출, 비공개 업로드 및 공개는 각각 승인 범위를 확인한다.

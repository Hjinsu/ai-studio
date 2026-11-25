# NEXT_SESSION.md v2 갱신안 (전환 전 이력)

현재 재개 절차는 [확정 NEXT_SESSION](../NEXT_SESSION.md)을 따른다.

2026-11-26 KST · 회사 PC용 · 현재 [기존 인수인계](../NEXT_SESSION.md)는 로컬 MVP 작업 기록으로 보존한다. v2 문서/분기 및 V2-01 착수는 사용자 승인 범위를 확인한다.

## 보존 기준

| 참조 | 확인된 원격 전체 SHA |
|---|---|
| `origin/main` | `47ef69731a599c570846c1482dcabf8ead8fe2e4` |
| `origin/t03/integration` · 분기 권고 | `dab7013c4557ba87d4440dfc86417344bb2c4e1d` |
| `origin/t03/c-queue` · 제품 WIP + 중단 보고 | `1e888767a61407a7e9176df0e362df9d6143f1b2` |
| `origin/t03/d-budget` · WIP | `3a976e1fbb1a5c8fbda50257b12643ff6cfbca3a` |
| 공통 계약 · D에 포함, C 미반영 | `6cd0c4e2c7618f90249f670f000938113c37d456` |

V2-00은 저장소 검토를 제출했다. V2-01부터 승인 대기이며 나머지는 [v2 로드맵](03_ROADMAP.md) 기준이다. C/D 제품 WIP는 원격에 있다. 집 PC D의 기존 MVP 편집·미추적 문서와 새 v2 패키지/검토안, integration worktree의 미추적 B 리뷰 2개는 로컬에 있다. 가져오기 전 **문서만 승인된 매체로 별도 복사**하거나, 사용자 승인 후 **승인된 v2 문서만 지정 새 브랜치에 명시 경로로 stage/commit/push**한다. 이전 MVP 문서는 별도 보존한다. `git fetch`로 로컬 문서는 전송되지 않는다.

## 재개 순서

1. 회사 정책상 허용된 환경의 기존 저장소에서 원격과 로컬 수정/동명 브랜치/worktree를 확인한다.

```powershell
git fetch origin
git status --short --branch
git worktree list
git branch -avv
git ls-remote --heads origin main t03/integration t03/c-queue t03/d-budget 'v2/*'
git show --no-patch --oneline dab7013c4557ba87d4440dfc86417344bb2c4e1d
```

2. 승인된 `origin/v2/integration`과 전달 문서가 있으면 사용자 지정 SHA를 확인하고 그 브랜치의 기존 clean worktree를 사용한다. 원격이 있고 로컬 브랜치가 없으면 `git worktree add -b v2/integration ../ai-studio-v2 origin/v2/integration`을 사용한다. 새 분기만 승인되었고 동명 참조 및 경로가 없다면 다음을 실행한다.

```powershell
git worktree add -b v2/integration ../ai-studio-v2 dab7013c4557ba87d4440dfc86417344bb2c4e1d
git -C ../ai-studio-v2 status --short --branch
```

3. [v2 패키지](README.md)와 [V2-00 검토](V2-00_REPOSITORY_REVIEW.md)에 따라 승인된 문서 전환을 반영한다. 문서 커밋이 승인되면 SHA를 기록한다. 병렬 착수 승인 후에는 기존 참조/경로가 없는 경우 `git worktree add -b v2/product ../ai-studio-v2-product v2/integration`, `git worktree add -b v2/safety ../ai-studio-v2-safety v2/integration`으로 각각 만든다. 전환 문서가 들어 있는 승인 SHA인지 먼저 확인한다.
4. **V2-01 첫 작업**은 Claude와 Codex가 소재 전 영속 제작 요청 ID, 비용 상한/귀속, Topic 확정 후 Run 연결 및 단일 dispatch/receipt 테스트 계약을 확정하는 것이다. Claude는 고정 단계 실행과 재시작·취소의 무효과 경로를, Codex는 비용·영속 복구와 공용 계약을 배정받는다. 이전 C에 공통 계약 cherry-pick은 v2 선행 작업이 아니다.

## 환경·DB 및 검증

- Git·JDK 21·프로젝트 고정 Node/MariaDB·FFmpeg/FFprobe를 확인한다. 환경변수는 `.env.example`과 [로컬 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md) 기준으로 새로 설정한다. 비밀은 로컬에만 두고 `.env`, API/OAuth 키, DB dump, 미디어, IDE 설정은 문서 전달 대상에서 제외한다. 승인된 기존 지출·게시 상태도 장비별로 확인한다.
- 기준은 V1~V6이다. C V7은 보고상 임시 격리 DB에서만 적용했고 D V8은 초안이다. 집/회사 개발 DB의 실제 Flyway 이력 및 백업 존재 여부 미확인. V2-00에서 DB/파일 백업을 수행하지 않았다. DB와 산출물 이관은 별도 승인 후 검증한다. 기존 개발/업무 DB를 연결해 `:backend:bootRun`으로 확인하면 자동 Flyway가 실행될 수 있으므로 승인 전에는 실행하지 않는다.
- 기준 재검증은 Java 21에서 `gradlew.bat :backend:test :backend:bootJar`, Testcontainers 또는 별도 loopback DB에서 `gradlew.bat :backend:integrationTest --rerun-tasks --no-build-cache`를 실행한다. 프론트/계약 검증은 가이드에 따른다.
- 과거 보고값은 B 단위 151·격리 통합 153·PR 계약 통합 CI 7/7, C WIP 단위 169·격리 통합 163씩(binlog ON/OFF), D WIP 단위 163 및 JAR이다. D의 이전 격리 통합 153은 V1~V6 회귀다. V2-00 제품 테스트·CI 현행 조회는 미실행이다.

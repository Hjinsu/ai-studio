# V2-05 집 PC 재개 절차

2026-11-27 KST · 기준 `v2/integration` `e51e64971a9e16251b38f86e23f7c778e7eb11eb` (V2-04 DONE) · 브랜치 `v2/05-design`

## 현재 상태

- V2-04는 모두 DONE이다(STATE.md, TASKS.md). V2-05 구현은 **시작하지 않았다**.
- V2-05 착수, 설계 확정, 실제 유료 API 호출·지출, YouTube 게시에는 각각 사용자 승인이 필요하다. 요구사항은 [V2-05 IFNOVA 콘텐츠](V2-05_IFNOVA_CONTENT.md)에 있다. 영상당 제작 예산 목표는 3,000원이며, 공식 가격을 확인한 뒤 다시 검토한다.
- 첫 작업 후보는 V2-05 설계 문서(공급자 비교, 한국어 TTS, IMAGE_TO_VIDEO/TEXT_TO_VIDEO/절차 애니메이션 스키마, 비용 경계)다. 공식 API 문서와 가격은 연동 직전에 다시 확인한다.

## 1. 코드 받기 (PowerShell, 저장소 루트)

```powershell
git fetch origin
git status --short --branch          # 기존 로컬 변경이 있으면 먼저 확인한다(덮어쓰지 않는다)
git worktree add ..\ai-studio-v2-05-design v2/05-design
Set-Location ..\ai-studio-v2-05-design
git log --oneline -2                 # 이 문서 커밋과 e51e649가 보여야 한다
```

같은 이름의 로컬 브랜치가 이미 있으면 `git switch v2/05-design` 후 `git pull --ff-only`를 실행한다.

## 2. Git 밖에서 준비할 것

| 항목 | 필요한 경우 | 방법 |
|---|---|---|
| JDK 21, Node(lockfile 기준), MariaDB 11.8 | 빌드·테스트 | 기존 설치 사용 |
| V2-04 인수 자산 폴더 `AI-Studio-V2-04-Acceptance` | 실제 미디어 테스트·영상 제작 | 회사 PC `C:\Users\PC-94\Downloads\AI-Studio-V2-04-Acceptance\`를 통째로 복사한 뒤, `EXTERNAL_ASSETS_SHA256.txt`와 대조한다 |
| FFmpeg 9.1 gyan full_build | 실제 미디어 테스트·영상 제작 | `winget install Gyan.FFmpeg` 후 해시 대조([제품 보고서 §3](../reports/V2-04_PRODUCT_REPORT.md)) |
| 격리 MariaDB(127.0.0.1:3307) | 통합 테스트·로컬 실행 | [로컬 테스트 가이드 §2](../guides/LOCAL_TEST_GUIDE.md). 임시 비밀번호는 저장소에 기록하지 않는다 |

비밀키, OAuth 토큰, 생성 영상, DB 덤프는 Git에 넣지 않는다.

## 3. 바로 실행

```bash
# 프런트 의존성 (대문자 드라이브 경로에서)
cd frontend && npm ci && cd ..

# 단위 테스트 (DB 불필요)
./gradlew.bat :backend:test

# 통합 테스트 (격리 DB)
AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<임시> \
  ./gradlew.bat :backend:integrationTest

# 실제 FFmpeg 미디어 테스트까지 (인수 자산 폴더를 <root>로)
AI_STUDIO_TEST_FFMPEG_DIR=<ffmpeg bin> AI_STUDIO_TEST_FIXTURE_ROOT=<root>/fixtures \
AI_STUDIO_TEST_FONT=<root>/downloads/font/NotoSansKR-Bold.otf \
  ./gradlew.bat :backend:integrationTest --tests "*ProductMediaIT"
```

앱 실행(백엔드+Vite)은 [로컬 테스트 가이드 §4](../guides/LOCAL_TEST_GUIDE.md)를 따른다. 미디어 기능을 켠 실행 인수와 scratch 볼륨은 [제품 보고서 §6](../reports/V2-04_PRODUCT_REPORT.md)를 따른다. 업무 DB(3306)는 테스트 대상으로 쓰지 않는다.

## 4. 유지되는 제한

- 미검증: Linux 실제 배포 렌더링, OS 수준 JVM 강제 종료. Linux 미디어 실행은 안전 차단 상태다.
- 실제 유료 API 호출, 지출 허용, YouTube 업로드는 승인되지 않았다.
- `main`은 변경하지 않는다. 기존 Migration은 수정하지 않고 새 V 파일만 추가한다.

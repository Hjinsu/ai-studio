# V2-04-PRODUCT 진행 보고 · 체크포인트 (Claude Code)

- 상태 **IN_PROGRESS — 체크포인트** (IMPLEMENTED 아님) · 2026-11-27 KST
- 기준 공통 SHA `d7bec5f9aadcc423d7b2a987ac31d810367b4604` · 브랜치 `v2/04-product` · worktree `../ai-studio-v2-04-product`
- 범위: [착수 지시서](../v2/V2-04_CLAUDE_HANDOFF.md)의 Claude 소유 경로. 공통 계약·Codex 소유 파일, migration, OpenAPI, 빌드 설정은 수정하지 않았다. 공통 scratch 보호 정책(전용 ≤32 GiB 볼륨)은 우회·완화하지 않았다.
- 남은 필수 항목: 실제 앱(보호 실행기) E2E와 실제 앱 브라우저 검증, 전체 integrationTest 완주, 사용자 청취·권리 확인 → 이후 IMPLEMENTED 보고.

## 1. 완료 / 미완료

| 구분 | 항목 |
|---|---|
| 완료(구현+자체 테스트) | `production/media/product` 전 단계: FAKE 이미지 공급·정규화, 실제 낭독 처리(48 kHz PCM·sample 타임라인), ASS/SRT·강조·Pop/Fade, 카메라 8 preset·PUNCH_IN, CUT/FADE 전환, BGM·SFX·sidechain ducking·2-pass loudnorm, FFmpeg 렌더러, 기술 QC 32항목, METADATA·manifest. 프런트 미디어 미리보기(재생·seek·다운로드·SRT·QC 요약, 로딩·빈·오류·404·세션 만료, 라이트·다크·모바일). fixture 생성기 |
| 완료(검증 범위 한정) | 실제 MP4 두 편: 직접 FFmpeg 파이프라인 테스트와 `ProductMediaIT`(실제 executor·비용 엔진·저장·HTTP, **FFmpeg 실행기만 테스트 전용 직접 실행**)로 제작·QC PASS |
| **NOT_TESTED** | 공통 `ProtectedMediaProcessRunner`(Windows Job Object + 전용 scratch 볼륨) 경로의 실제 bootJar 앱 E2E. 실제 앱 로그인 후 브라우저 재생·seek·다운로드(현재 브라우저 검증은 API 대역 Playwright). 이 PC에는 ≤32 GiB 전용 볼륨이 없고 VHDX 생성(관리자)은 수행하지 않기로 함 |
| **미완료** | 전체 `:backend:integrationTest` 완주(중단 요청으로 실행 도중 종료 — §2). 낭독 사람 청취·권리 사용자 확인. Linux(공통 계약상 차단, Codex V2-04-SAFETY) |

## 2. 테스트 결과 (실제 실행)

| 명령 | 결과 |
|---|---|
| `gradlew.bat :backend:test` (FFmpeg 환경변수 지정) | **324건 통과**, 실패·건너뜀 0 (제품 단위 26 + 실제 FFmpeg 파이프라인 2 포함) |
| `gradlew.bat :backend:integrationTest :backend:bootJar` (격리 MariaDB 11.8 임시 3307) | **미완주** — 세션 종료 요청으로 Gradle 중단. 이 세션에서 완료된 통합 테스트는 `ProductMediaIT` 2/2뿐. 공통 IT 회귀는 다음 세션에서 재실행 필요 |
| `ProductMediaIT` | 2/2 통과: 두 소재 11단계 각 1회 시도 SUCCEEDED, IMAGE/TTS operation = 장면×2(추가 발송 0), HEAD 200·Range 206(closed/suffix)·416·attachment·401·404 |
| frontend lint / build(typecheck 포함) / Vitest | 통과 / 통과 / 102건 통과(신규 5) |
| Playwright Edge `e2e/media.spec.ts` (API 대역 + 실제 MP4 바이트, 동일 Range 규칙) | 3/3: 두 MP4 재생(1080×1920, 40–60 s), 30 s seek 후 readyState≥2, 206 요청, 다운로드 파일명·바이트 일치, 다크·모바일 가로 넘침 0. **실제 백엔드 경로 아님** |
| Gitleaks(변경 경로), `git diff --check` | 커밋 시점 결과를 커밋 메시지에 기록 |

## 3. 인수 영상 (Git 밖, 보존)

`C:\Users\rlead\Downloads\AI-Studio-V2-04-Acceptance\output\` — `ProductMediaIT` 산출(실제 executor/DB/HTTP, 직접 FFmpeg)

| 파일 | SHA-256 | 측정 |
|---|---|---|
| `it-neutron-star/neutron-star.mp4` 20,732,242 B, 5장면 | `6c2d9c22abe2bbe0af70e1bf62b58093a19b324906ea0a1d2de65c8368577e8b` | 1512 fr 50.40 s, 낭독 50.372 s, −15.8 LUFS, −1.9 dBTP, linear, QC PASS |
| `it-venus-day/venus-day.mp4` 16,801,622 B, 3장면 | `20be054d32f0722221a2a29edee68afd5cae655786157acff4d303cbd128534f` | 1384 fr 46.13 s, 낭독 46.115 s, −15.9 LUFS, −1.8 dBTP, linear, QC PASS |

각 폴더: `contact-sheet.png`, `ffprobe.json`, `qc-report.json`, `manifest.json`, SRT, `request.txt`. `output/screens/`(라이트·다크 모바일 캡처), `output/SHA256SUMS.txt`. FFmpeg `9.1-full_build-www.gyan.dev` (ffmpeg.exe `3ab4a9c404504ebff6662d6a48661db952f88ee7fb7d5949a711cad7a763b2cf`, ffprobe.exe `ff181a5b52664aae7a9f673c3a718440706b33511e8a3e38064ff4f21f1a7bed`).

## 4. Git 외부 자산 (루트 `C:\Users\rlead\Downloads\AI-Studio-V2-04-Acceptance\`, 약 375 MB)

전체 SHA-256: `EXTERNAL_ASSETS_SHA256.txt` · 권리 근거: `ASSETS_AND_RIGHTS.md`

| 경로 | 내용 | SHA-256 / 근거 |
|---|---|---|
| `downloads/piper/piper_windows_amd64.zip`, `LICENSE.md` | Piper 2023.11.14-2 (github.com/rhasspy/piper releases), MIT | zip `f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea`, LICENSE `4cd71dece7037f1d6d93cce7570c57ab75ea9ac566fd4990be2f3ab08d15b47f` |
| `tools/piper/` | 위 zip 압축 해제본(piper.exe 등) | piper.exe `96f3da3811151580073e40bb4dd20eb0fb8115f5f5f76e2fb54282b3edfa5c1f` |
| `downloads/voice/en_US-ljspeech-high.onnx`(+`.json`, `MODEL_CARD`) | huggingface.co/rhasspy/piper-voices `en/en_US/ljspeech/high/`. 데이터셋·모델 public domain(MODEL_CARD, brycebeattie.com/files/tts) | onnx `5d4f08ba6a2a48c44592eed3ce56bf85e9de3dd4e20df90541ae68a8310c029a`, json `7e1f4634af596d83cca997fb7a931ba80b70f8a316a2655ee69c55365e0ace14`, MODEL_CARD `289f7421072d689a3d91f6c632f486af46f9b8b04417536104a4ea667b8a394b` |
| `downloads/font/NotoSansKR-Bold.otf`, `LICENSE` | github.com/notofonts/noto-cjk `Sans/SubsetOTF/KR/NotoSansKR-Bold.otf`, SIL OFL 1.1 | otf `5a6ceb287ed2fc6cfc6213144ebea68cbd94b20fc9eb873d8486493bf02d9bda`, LICENSE `6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2` |
| `fixtures/ifnova-v204-1/catalog.json` | 카탈로그(파일 해시·권리 고정) | `9de0064f0d8c5e43ac37a00cd0254e8c6dc2b27735115853cd9002ed9402158c` |
| `fixtures/ifnova-v204-1/images/` | 장면 이미지 8장(Java2D 자체 제작) | `EXTERNAL_ASSETS_SHA256.txt` |
| `fixtures/ifnova-v204-1/audio/neutron-star-{1..5}.wav`, `venus-day-{1..3}.wav` | Piper 낭독(cue 단위 합성) | 동 파일 |
| `fixtures/ifnova-v204-1/audio/bgm-pizzicato.wav`, `sfx-{sting,whoosh,boing}.wav` | 자체 합성 음악·효과음 | 동 파일 |
| `output/` | 인수 MP4 두 편과 증거 | §3 |
| `work/` | 중간 산출(Piper cue 캐시, 파이프라인 테스트 출력). 재생성 가능, 보존 불필요 | — |

격리 DB 데이터·임시 비밀번호 파일은 삭제했다. Git에는 생성기·cue 정의·문서만 있다.

## 5. 다른 PC에서 재개 (정확한 순서)

1. `git fetch` → `v2/04-product` 체크포인트 커밋을 worktree `../ai-studio-v2-04-product`에 체크아웃. AGENTS.md·이 보고서·STATE.md 확인.
2. **외부 자산 복원(권장: 복사)**: 이 PC의 `AI-Studio-V2-04-Acceptance` 폴더 전체를 같은 구조로 복사하고 `sha256sum -c`로 `EXTERNAL_ASSETS_SHA256.txt` 대조. 재생성 대안: `downloads/`의 원본을 위 URL에서 받아 해시 대조 → zip을 `tools/`에 해제 → `backend/src/test/media-fixtures/README.md`의 `GenerateImages.java`·`generate-catalog.mjs` 실행. 재생성본은 Piper/Java2D 환경 차이로 해시가 달라질 수 있으므로 새 해시를 기록하고 인수 MP4를 다시 만든다.
3. FFmpeg 9.1 gyan full_build를 설치하고 위 해시와 대조(다르면 설정값 갱신·재검증).
4. `cd frontend && npm ci` (lockfile 그대로).
5. 격리 MariaDB 3307 기동(`docs/guides/LOCAL_TEST_GUIDE.md` §2) 후:
   `AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<임시> AI_STUDIO_TEST_FFMPEG_DIR=<ffmpeg bin> AI_STUDIO_TEST_FIXTURE_ROOT=<root>/fixtures AI_STUDIO_TEST_FONT=<root>/downloads/font/NotoSansKR-Bold.otf AI_STUDIO_TEST_MEDIA_OUT=<root>/output ./gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` — **미완주였던 전체 IT 완주**.
6. 회사 PC 실제 앱 E2E(§6) → Playwright 실제 앱 경로 검증 → 결과를 이 보고서에 기록 후 IMPLEMENTED 보고.

## 6. 회사 PC 실제 앱 E2E 준비 (관리자 PowerShell, 이 PC에서는 미수행)

공통 실행기 준비 조건: scratch는 Workspace와 **다른** 볼륨, 볼륨 총용량 ≤32 GiB, 여유 ≥5 GiB+예약, 짧은 ASCII 실경로.

```powershell
# 16 GiB 확장형 VHDX 생성·연결·포맷·S: 할당 (관리자)
@"
create vdisk file="C:\aistudio\scratch.vhdx" maximum=16384 type=expandable
select vdisk file="C:\aistudio\scratch.vhdx"
attach vdisk
create partition primary
format fs=ntfs quick label=AISCRATCH
assign letter=S
"@ | Set-Content -Encoding ascii $env:TEMP\aistudio-vhdx.txt
New-Item -ItemType Directory -Force C:\aistudio | Out-Null
diskpart /s $env:TEMP\aistudio-vhdx.txt
New-Item -ItemType Directory -Force S:\s | Out-Null
# 재부팅 후 재연결: select vdisk file="C:\aistudio\scratch.vhdx" / attach vdisk  (diskpart)
```

서비스 계정만 쓰기 가능하도록 ACL을 제한한다. 앱 설정(bootJar 실행 인수 또는 Git 밖 설정 파일; 비밀값은 기록하지 않음):

```
--ai-studio.production.planning-enabled=true            (+ 기존 executor 활성 설정)
--ai-studio.production.media.enabled=true
--ai-studio.production.media.storage-root=<Workspace 실경로, scratch와 다른 볼륨>
--ai-studio.production.media.scratch-root=S:\s
--ai-studio.production.media.fixture-catalog=ifnova-v204-1
--ai-studio.production.media.product.fixture-root=<root>\fixtures
--ai-studio.production.media.voice-id=piper-en_US-ljspeech-high-1
--ai-studio.production.media.ffmpeg-path=<ffmpeg.exe 절대경로>   --ai-studio.production.media.ffmpeg-sha256=<해시>
--ai-studio.production.media.ffprobe-path=<ffprobe.exe 절대경로> --ai-studio.production.media.ffprobe-sha256=<해시>
--ai-studio.production.media.font-path=<root>\downloads\font\NotoSansKR-Bold.otf
--ai-studio.production.media.font-sha256=5a6ceb287ed2fc6cfc6213144ebea68cbd94b20fc9eb873d8486493bf02d9bda
--ai-studio.production.media.font-family=Noto Sans KR
```

순서: 격리 DB에 `ai_studio` 생성 → bootJar 실행(위 환경) → 관리자 생성(LOCAL_TEST_GUIDE §4) → 영어(`en`) 채널·예산 정책·지출 허용 → API로 `mediaEnabled:true` SHORTS 요청(직접 선택, neutron-star·venus-day 후보 선택) → 11단계 완료 확인 → 브라우저 로그인 후 상세 화면에서 재생·seek·다운로드, 라이트·다크·모바일 확인 → MP4 해시·QC·manifest 기록. 준비 실패 시 실행기 로그(`Media execution preparation failed`)를 확인하고 보호 조건을 완화하지 않는다.

## 7. 통합 담당자(Codex) 조율 요청

- 신규 제품 설정 `ai-studio.production.media.product.fixture-root`, `product.fake-price-krw`(기본 1, 모의 KRW)의 공통 설정 문서 등록.
- `font-family`와 카탈로그 글꼴 family 일치 필요. 계약 변경 요구 없음.
- 리뷰 확인 요청: 효과 콜백 내 예기치 못한 예외를 EXECUTED_FAILED(빈 payload)로 정산하는 처리.

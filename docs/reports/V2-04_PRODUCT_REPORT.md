# V2-04-PRODUCT 진행 보고 (Claude Code)

- 상태 **IN_PROGRESS — 기술 인수 완료, 낭독·자막·배경음악·효과음 사용자 판정 대기** (IMPLEMENTED 아님. `f3d6ac0`의 IMPLEMENTED 기록 정정) · 2026-11-27 KST · 회사 PC 재개 결과는 [§0](#0-회사-pc-재개-결과-2026-11-27)
- 기준 공통 SHA `d7bec5f9aadcc423d7b2a987ac31d810367b4604` · 브랜치 `v2/04-product` · worktree `../ai-studio-v2-04-product`
- 범위: [착수 지시서](../v2/V2-04_CLAUDE_HANDOFF.md)의 Claude 소유 경로. 공통 계약·Codex 소유 파일, migration, OpenAPI, 빌드 설정은 수정하지 않았다. 공통 scratch 보호 정책(전용 ≤32 GiB 볼륨)은 우회·완화하지 않았다.
- 남은 필수 항목: 낭독·자막·배경음악·효과음 각각의 사용자 합격 판정(§0.6). 권리 근거와 정지 이미지 중심 표현은 사용자 확인 완료. 후속: Codex scratch 예약 결함(§0.5), V2-05 IMAGE_TO_VIDEO·TEXT_TO_VIDEO 검토, V2-06 AI 콘텐츠 고지·게시 정책.

## 0. 회사 PC 재개 결과 (2026-11-27)

회사 PC(Windows 11, 사용자 `PC-94`), 기준 HEAD `0920437036e85182bb807be4a7b85425da291b50`, 외부 자산 루트 `C:\Users\PC-94\Downloads\AI-Studio-V2-04-Acceptance\`. 제품 코드는 수정하지 않았다. 추가 파일은 실제 앱 인수 스크립트 `frontend/e2e/live-media.mjs`뿐이다(`npm test`에 포함되지 않음).

### 0.1 환경·자산

| 항목 | 결과 | 근거 |
|---|---|---|
| Java 21 | PASS | Gradle toolchain Temurin 21.0.13(`~/.gradle/jdks`). PATH의 Zulu 17은 Gradle 실행용으로만 사용 |
| FFmpeg/ffprobe 9.1 gyan full_build (winget) | PASS | `3ab4a9c4…cb7c2cf` / `ff181a5b…1f1a7bed`. 집 PC 기록과 일치 |
| Node.js / npm.cmd | PASS | v24.21.0 / 11.19.0. `npm ci` lockfile 그대로, 실행 정책 변경 없음 |
| Gitleaks | PASS | 8.30.1, exe `17157e2e…78fcb7c`(스파이크 기록과 일치) |
| 외부 자산 SHA-256 | PASS | `EXTERNAL_ASSETS_SHA256.txt` 39/39(fixtures·downloads 29 + `output/` 10). 스파이크 `SHA256SUMS.txt` 30/30, 절대경로 3건(ffmpeg·ffprobe·gitleaks)은 회사 PC 경로에서 같은 해시로 대조 |
| Piper·음성 모델 권리 | PASS(문서 대조) | Piper MIT `LICENSE.md`, `en_US-ljspeech-high` MODEL_CARD "Dataset LJ Speech, License: public domain". 해시는 위 대조에 포함 |
| 이미지·BGM·SFX·글꼴 권리 | PASS(문서 대조) | `ASSETS_AND_RIGHTS.md`: 이미지·음악·효과음 자체 제작, Noto Sans KR Bold OFL 1.1. QC `RIGHTS_COMPLETE` PASS(neutron 15건, venus 10건). **사용자 최종 확인 대기** |

### 0.2 격리 환경

- Scratch: 사용자가 관리자 PowerShell로 `tools\create-scratch-vhdx.ps1`(외부 자산 루트) 실행. `C:\aistudio\scratch.vhdx` 16 GiB 확장형, NTFS `AISCRATCH`, `S:`(총 17,177,767,936 B), `scratch-root=S:\s`. Workspace(`storage-root`)는 C:의 `…\AI-Studio-V2-04-Acceptance\e2e-workspace`. 32 GiB 상한·5 GiB 하한 보호 조건은 그대로 두었다. 재부팅 후 `Mount-DiskImage -ImagePath C:\aistudio\scratch.vhdx`(관리자) 필요.
- MariaDB: 기존 3306(업무 DB)은 접속하지 않았다. 설치본 11.8.2로 임시 인스턴스 127.0.0.1:3307을 별도 데이터 폴더(세션 임시 폴더)에 실행했고, 완료 후 종료하고 데이터·임시 비밀번호를 삭제했다. 3306 설정과 `.env`는 재사용하지 않았다.
- 포트: 8080은 다른 프로젝트(IntelliJ)가 사용 중이라 건드리지 않고, 앱 18080·Vite 5180을 썼다. 종료 후 18080/5180/3307 비어 있음 확인.

### 0.3 전체 테스트 (실제 실행)

| 명령 | 결과 |
|---|---|
| `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` (격리 3307, FFmpeg·fixture·font 환경변수) | **PASS** BUILD SUCCESSFUL 7m55s. test 39 suites **324/324**, integrationTest 27 suites **259 통과, 실패 0, 건너뜀 1**(`PlanningExecutionIT.liveEdgeConnectsTheFrontendToTheActualServer`: 기존 환경 게이트 테스트, V2-04 무관). bootJar 생성 |
| frontend `npm run lint` / `npm run typecheck` / `npm test` / `npm run build` | **PASS** / **PASS** / **102/102** / **PASS** |
| `ProductMediaIT` 산출(`output-pc94-it/`) | neutron `16f7d2da…90c4c6`, venus `fcf8d4d8…eb6cb33`: 아래 실제 앱 MP4와 **바이트 동일**(결정적 렌더) |
| Playwright API 대역 `e2e/media.spec.ts` | 이번 세션 재실행 안 함(실제 앱 경로로 대체 검증, 아래) |
| Gitleaks `live-media.mjs`, `output-pc94-e2e/` | no leaks |

### 0.4 실제 앱 E2E (보호 실행기, bootJar + Vite + Edge) — PASS

`node frontend/e2e/live-media.mjs`(환경: `LIVE_URL`, `LIVE_PASSWORD_FILE`, `LIVE_OUT`, 선택 `LIVE_TOPICS`, `LIVE_REUSE=slug:id`). 앱 설정은 §6 목록과 같다(+`--server.port=18080 --ai-studio.production.executor.enabled=true`).

| 단계 | neutron-star (요청 3) | venus-day (요청 5) |
|---|---|---|
| 로그인(UI) | PASS | PASS |
| 채널·예산·지출 허용(브라우저 세션 API, 모의 KRW) | PASS | PASS |
| 소재 발굴 `mediaEnabled:true` MANUAL 10후보 → 소재 선택(UI 대화상자) | PASS(요청 1·2는 후보 미포함으로 취소) | PASS |
| 조사·대본·장면·이미지·음성·자막·렌더·METADATA·QC | 11단계 모두 SUCCEEDED, 각 1회 시도, 76 s | 11단계 모두 SUCCEEDED, 각 1회 시도, 60 s |
| 보호 실행기 사용 | `S:\s\r3-i*-a1` attempt 디렉터리 생성 확인 | `S:\s\r5-…` |
| 기술 QC | PASS 32/32, 50.40 s·1512 fr, 낭독 50.372 s, −15.8 LUFS, −1.9 dBTP, LRA 3.9, linear | PASS 32/32, 46.13 s·1384 fr, 낭독 46.115 s, −15.9 LUFS, −1.8 dBTP, LRA 3.3, linear |
| 미디어 확정·목록(`/media`) | MP4·SRT·QC_REPORT·MANIFEST 등 공개 | 동일 |
| 브라우저 재생 | 1080×1920, 50.4 s, 1.09 s 재생, error 없음 | 1080×1920, 46.13 s, 1.03 s 재생 |
| seek 30 s | 30.96 s, readyState 4, 206 응답 | 30.96 s, readyState 4, 206 |
| 장면 이미지 decode / 장면 낭독 재생 | 5장 1080 px / 0.56 s 재생 | 3장 / 0.56 s |
| 영상 다운로드(버튼) | `asset-28.mp4`, SHA = 목록 SHA | `asset-65.mp4`, SHA = 목록 SHA |
| 자막 다운로드(버튼)·SRT 보기 | `asset-27.srt`, `-->` 포함 | `asset-64.srt` |
| 라이트 / 다크+모바일 390 px 가로 넘침 | 캡처 / 넘침 0 | 캡처 / 넘침 0 |

산출물(Git 밖) `C:\Users\PC-94\Downloads\AI-Studio-V2-04-Acceptance\output-pc94-e2e\` (`SHA256SUMS.txt` 포함):

| 파일 | 바이트 | SHA-256 |
|---|---|---|
| `neutron-star\neutron-star.mp4` | 20,744,105 | `16f7d2da0713288b715b6964a0ed3c91c4ea0bd42121698dfbcaaf445590c4c6` |
| `neutron-star\neutron-star.srt` | | `198b8a8da7286632de8671bd0ef625ebfb6f1796ec9e56f3d21a78071cc628fa` (집 PC와 동일) |
| `venus-day\venus-day.mp4` | 16,795,925 | `fcf8d4d8d1092d12fd6193a65e2e70b9730fc80683cc6abf6a90805e8eb6cb33` |
| `venus-day\venus-day.srt` | | `b16eba110c34af5c3c1d6f66804e6048f6724d8feefa33134dd42e725b294eca` |

각 폴더: `contact-sheet.png`(5 s 간격 10컷), `ffprobe.json`, `qc-qc_report-*.json`, `qc-manifest-*.json`, `media.json`, `request-detail.json`, `live-result.json`, `browser-light.png`, `browser-dark-mobile.png`. `logs\`: 실행 로그, 앱 경고, 정리 전 scratch 목록. MP4 해시는 집 PC 산출(`6c2d9c22…`, `20be054d…`)과 다르다. SRT·QC 측정값은 같고, 같은 PC에서는 IT·실제 앱 출력이 바이트 동일하다. 다른 PC 간 해시 고정은 요구사항이 아니다.

참고: 다크·모바일 전체 페이지 캡처의 장면 썸네일은 lazy 로딩 전 상태로 찍혔다. decode는 라이트 화면에서 검증했다.

### 0.5 발견 결함 — 통합 담당자(Codex) 조율 필요, 제품 코드 수정 없음

**보호 scratch 예약이 완료된 attempt 디렉터리를 24시간 동안 계속 1 GiB씩 계산한다.** `ProtectedMediaProcessRunner.prepare`는 `여유공간 ≥ 5 GiB + (기존 r*-i*-a* 디렉터리 수 + 1) × 1 GiB`를 요구한다. `MediaCleanup.RETENTION = 24h`라 성공한 요청의 디렉터리도 하루 동안 남는다. 16 GiB 볼륨에서는 약 10개까지만 허용되는데 5장면 요청 1건이 8개를 남긴다. 그래서 두 번째 요청(요청 4)의 TTS가 `MEDIA_SCRATCH_UNAVAILABLE`("Media scratch reservation exceeds capacity")로 3회 시도 후 **FAILED**됐다(`logs\app-warnings.log`, `live-run-request4-scratch-fail.log`). 32 GiB 상한 볼륨에서도 하루 약 3건이 한계다. `v2/04-safety`(`59f22c8`)도 같은 규칙이다.
- 인수 진행 방식: 보호 조건은 바꾸지 않았다. 종료(SUCCEEDED/FAILED)되고 lease가 0인 요청 3·4의 scratch 디렉터리만 수동 삭제했다(reaper가 24h 후 하는 동작과 같음). 이후 venus-day(요청 5)를 새로 제작했고, neutron-star는 이미 성공한 요청 3으로 브라우저 검증을 했다.
- 제안(승인 필요): 종료된 요청·성공 attempt의 scratch를 즉시 reap하거나, 예약을 실행 중 attempt만 계산. 공통 계약 변경이므로 Codex·사용자 결정 사항이다.

### 0.6 기술 QC와 사용자 확인 범위 (2026-11-27 KST 정정)

자동 기술 QC와 사람 확인을 구분해 기록한다. 자동 검수는 사람 검토를 대체하지 않는다(AGENTS.md).

| 항목 | 자동 기술 QC (§0.4) | 사용자 확인 |
|---|---|---|
| 낭독 | PASS: 원문 매핑, 권리, 해시, 48 kHz 타임라인, 무음·clipping, −15.8/−15.9 LUFS, −1.9/−1.8 dBTP | **미확인**. 명시적 청취 합격 판정 없음 |
| 자막 | PASS: SRT/ASS coverage·시간, 글리프 bbox, 번인 | **미확인**. 명시적 판정 없음 |
| 배경음악 | PASS: 권리, ducking, 통합 음량 | **미확인**. 명시적 판정 없음 |
| 효과음 | PASS: 권리, 믹스 | **미확인**. 명시적 판정 없음 |
| 정지 이미지 중심 영상 표현 | PASS: 해상도, 장면 경계, 카메라·전환 | **확인·수용**: V2-04 기술 구현 범위로 수용. 동영상 생성(IMAGE_TO_VIDEO·TEXT_TO_VIDEO) 지원은 V2-05에서 검토 |
| 권리 근거 `ASSETS_AND_RIGHTS.md` | PASS: QC `RIGHTS_COMPLETE` | **확인**: Piper LJSpeech-high Public Domain 표기, 자체 생성 이미지·배경음악·효과음·대본, OFL 글꼴, 외부 설치 FFmpeg의 현재 사용 방식이 기술 인수용으로 적절 |
| AI 콘텐츠 고지·실제 게시 정책 | 범위 밖 | V2-06 후속 요구사항 |
| Scratch 예약 누적 결함(§0.5) | 미해결 | Codex 담당. 제품 쪽에서 수정하지 않음 |

**상태 판단: IN_PROGRESS 유지.** [구현 계획](../v2/V2-04_IMPLEMENTATION_PLAN.md) §필수 인수는 "사람이 확인한 발화 표본"을 증거로 요구하고, 이번 재개 지시는 사용자 확인 완료를 IMPLEMENTED의 조건으로 정했다. 낭독·자막·배경음악·효과음에 대한 명시적인 사용자 합격 판정이 아직 없으므로 조건이 충족되지 않았다. 직전 커밋 `f3d6ac0`의 IMPLEMENTED 기록은 이 판정이 있었던 것으로 잘못 기록한 것이므로 정정한다.

**IMPLEMENTED 전환 조건**: 사용자가 두 MP4(§0.4)를 듣고 낭독·자막·배경음악·효과음 각각에 대해 합격 또는 불합격을 판정하고, 그 결과를 이 절에 기록한다. 제품 코드·테스트 재실행은 필요하지 않다. 실제 게시와 유료 API 호출은 승인되지 않았다.

### 0.7 재현 (회사 PC)

1. (재부팅 후) 관리자 `Mount-DiskImage -ImagePath C:\aistudio\scratch.vhdx` → `S:\s` 확인. 이전 요청의 `S:\s\r*` 디렉터리가 10개 이상이면 §0.5 제약에 걸린다.
2. `LOCAL_TEST_GUIDE.md` §2로 3307 임시 MariaDB를 띄우고 `ai_studio` DB를 만든다.
3. 관리자 계정: 대화형 콘솔에서 `java -jar backend\build\libs\backend-0.1.0-SNAPSHOT.jar admin create --login admin`(ADR-031). 이번 세션은 비대화형이라 CLI가 종료 코드 2로 거부했다(정상 동작). 그래서 격리 DB에만 앱과 같은 BCrypt(12) 해시로 `admin_user` 1행을 직접 넣었다. 로그인 자체는 실제 UI로 검증했다.
4. bootJar를 §6 설정과 `--server.port=18080 --ai-studio.production.executor.enabled=true`로 실행한다. `AI_STUDIO_BACKEND_URL=http://127.0.0.1:18080 npx vite --host 127.0.0.1 --port 5180`.
5. `cd frontend; $env:LIVE_URL='http://127.0.0.1:5180'; $env:LIVE_PASSWORD_FILE=<비밀번호 파일>; $env:LIVE_OUT=<Git 밖 폴더>; node e2e/live-media.mjs`.

아래 §1~§7은 집 PC 체크포인트 기록이다(이력).

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

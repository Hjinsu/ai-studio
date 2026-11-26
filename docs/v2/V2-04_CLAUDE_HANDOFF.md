# V2-04 Claude Code 구현 착수 지시서

`v2/04-product` / `../ai-studio-v2-04-product`. Codex `v2/04-safety` / `../ai-studio-v2-04-safety`와 공통 검증 SHA를 공유한다. 기준은 `origin/v2/04-common`의 공통 계약 커밋을 사용한다. 환경과 테스트 DB는 워크트리별로 설정한다.

| 소유 | 수정 범위 |
|---|---|
| Codex | `backend/src/main/java/com/aistudio/production/{contract,cost,execution,planning,api}/`의 미디어 연결부, 신규 `production/media/{storage,process}/`, 신규 Flyway migration, OpenAPI·생성 타입·공유 빌드/설정, 공통 복구·HTTP·OS 안전 테스트와 최종 통합 |
| Claude Code | `backend/src/main/java/com/aistudio/production/media/product/` 및 대응 테스트·fixture 카탈로그, `frontend/src/pages/ProductionRequestDetailPage.tsx`와 신규 미디어 컴포넌트, `frontend/src/api/production.ts`의 미디어 소비부 및 제품 테스트 |
| 변경 조율 | 포트·스키마·공통 설정·실행기 보완은 Codex에게 요청한다. Claude는 공통 파일을 수정하는 대신 제품 브랜치 보고서에 필요한 변경과 근거를 기록한다. |

## 구현 순서

1. [D01~D03](V2-04_IMPLEMENTATION_PLAN.md)에 따라 두 과학 소재의 서로 다른 원문·실제 낭독·정렬·이미지를 카탈로그에 연결한다. 공식 정적 Noto Sans Bold 계열의 영어·한글 두 줄과 권리를 확인한다. SAPI·Arial·무음/비프는 기술 또는 실패 표본이다. 인수 미디어는 Git 밖에서 보관한다.
2. 이미지 fit+pad·확대/크롭, 48 kHz sample clock, ASS/SRT 시간 양자화와 bbox를 구현한다. FAKE 비용 경계는 공통 포트를 통과하고 원문 불일치·권리 불명·미검증 정렬은 차단한다.
3. 포트로 허용 카메라·전환·SFX·ducking·강조 프리셋을 적용한다. 출력 종료 뒤 전체 디코딩·PTS/프레임·샘플·AAC 음량·동기·권리·manifest와 파일 검증을 수행한다. 공통 저장소의 최종 채택을 우회하지 않는다.
4. 공통 HTTP를 기존 세션·UI 토큰으로 연결한다. 이미지·음성·SRT·QC·MP4를 제공하고 완료 파일 재생·seek·다운로드를 검증한다. 로딩·빈 상태·오류·권한·세션 만료 및 라이트·다크·모바일을 포함한다.
5. `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 및 Linux wrapper, 실제 격리 MariaDB·FFmpeg 브라우저 경로, frontend lint/typecheck/test/build와 OpenAPI 생성 검사를 실행한다. 두 MP4의 QC·해시·권리 증거, 실패 표본, 미검증 사항을 보고한다. [공통 계약](V2-04_COMMON_CONTRACT.md), [격리 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md)

V2-04는 FAKE/로컬 기술 인수 단계다. V2-05 공급자·단일 음성·콘텐츠 QC, V2-06 게시, V2-07 관리 UI는 각 단계의 승인 후 시작한다.

고정 HEAD에서 통합 테스트 후 독립 리뷰를 한 차례 수행한다. 최초 담당자가 차단 결함을 수정하고 집중 회귀를 수행한다. 데이터 손실, 중복 과금, 보안·소유권, 핵심 기능·비용/복구 계약 및 필수 인수 실패는 병합 차단이다. 비차단 개선은 후속으로 남긴다. 최신 PR HEAD CI·필요한 사용자 검증·ChatGPT 최종 검토와 사용자 승인으로 종료한다.

## 공통 코드 사용

- 제품 코드는 `production/media/product`에 `MediaStageWork` Spring bean을 제공한다. context의 `items`는 SCENES/SCRIPT 해시와 PROFILE 해시로 동결된 순서다. IMAGE/TTS에서 아이템별로 `MediaEffectPort.execute` 또는 `recover`를 사용하고 RECEIPT는 `apply`한다. 복구에서 동일 item을 재사용한다. context의 취소 신호를 확인하고 recovery-only 경로는 공통 실행기가 정산한다.
- SUBTITLE·RENDER·METADATA·QC는 `MediaAssets.publish`로 역할 묶음을 남긴다. 후속 단계는 `inputs/open`으로 해시 검증된 바이트를 읽는다. QC가 MP4 후보 및 최종 리포트/manifest를 확인하면 공통 실행기가 QC 성공 트랜잭션에서 공개 상태를 확정한다.
- payload 권리에 source, provider, license, evidenceRef, verifiedBy, verifiedAt(UTC), commercialUse, publish, redistribution, conditions를 제공한다. lineage에 입력 및 파생 원본 해시를 연결한다. 허용 MIME·역할과 16 MiB(영상 100 MiB) 크기 제한을 준수한다.
- `MediaProcessRunner.prepare`로 받은 scratch에 정해진 ASCII 파일명으로 입력을 복사한다. `run`에는 tool/용도 및 개별 인수와 context의 취소 신호를 전달한다. Windows 준비에는 SHA-256이 고정된 외부 ffmpeg/ffprobe, 정적 Noto Sans Bold 계열 글꼴, 짧은 ASCII scratch의 전용 제한 볼륨 및 별도 Workspace가 필요하다. Linux에서는 보호 런처 미검증 상태이므로 미디어 실행이 차단된다.
- 실제 기능을 검증하려면 기존 planning/executor와 `ai-studio.production.media.enabled`를 구성하고 요청 생성에서 `mediaEnabled: true`를 명시한다. 제품은 단일 음성으로 성립해야 한다. 고정 마스코트·레퍼런스 생성·일관성 QC·Blender는 BACKLOG다.

공통 준비 속성은 `ai-studio.production.media.`의 `enabled`, `storage-root`, `scratch-root`, `fixture-catalog`, `voice-id`, `ffmpeg-path`, `ffmpeg-sha256`, `ffprobe-path`, `ffprobe-sha256`, `font-path`, `font-sha256`, `font-family`다. 설정값은 작업 환경에서 주입한다. Workspace에 쓸 수 있는 서비스 계정 전용 경로와 작업용 전용 볼륨을 준비한다. Windows scratch 볼륨의 총 용량 상한은 32 GiB이며 5 GiB 여유 하한과 attempt 예약을 적용한다.

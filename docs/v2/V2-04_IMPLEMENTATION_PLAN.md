# V2-04 미디어 제작 엔진 상세 구현 계획

- 상태 **최종 설계 · 구현 착수 전** · 2026-11-26 KST · 작업 ID **V2-04 상세 설계 (D01~D10)** · 작성 Codex
- 설계 브랜치 `v2/04-design`, worktree `../ai-studio-v2-04-design`, 설계 기준 `80b9fa596e3bd9bfc316892181a04342cfd70cc1`. PR #6은 열린 Draft (`42cbbcfd2762306e3cd6ec7dced8c6b864325cda`), 원격 통합 `c4832b7eca2cbf89893bce92fa2b980263cf695e`. [공통 계약](V2-04_COMMON_CONTRACT.md), [Claude 착수 지시서](V2-04_CLAUDE_HANDOFF.md), [V2-05 요구사항](V2-05_IFNOVA_CONTENT.md)을 함께 적용한다.
- 공급자·소재 발굴 문서 06·07은 `docs/v2-provider-discovery`의 `9c07d0eace479ff745016638880cc36b42740da4`에서 참고했다. 두 문서는 PROPOSED다. V2-04는 로컬 미디어를 검증하고 실제 공급자 선정·가격·검색 API 검증은 V2-05에서 승인 후 진행한다.
- 목표는 확정된 V2-03 SCENES와 SCRIPT로부터 FAKE 이미지·로컬 음성을 사용해 실제 재생 가능한 Shorts MP4, 기술 QC 결과와 계보를 만드는 것이다. 실제 공급자와 콘텐츠 품질 평가는 V2-05, YouTube 게시용 메타데이터와 게시 계약은 V2-06 범위다. YouTube 게시, AI 영상 생성, 롱폼 렌더링, 영상·타임라인 편집기는 후속 범위다.
- 착수 조건은 V2-03 DONE 및 최종 통합 SHA 대조, 별도 V2-04 공통 계약 구현 승인이다.

## 저장소 조사 및 통합 경계

| 기준 SHA에서 확인 | V2-04 적용 |
|---|---|
| `ProductionStage`, `StepPlanner`, `ProductionExecutor`, `ExecutionMapper`는 고정 11단계·세대 1·요청 단위 lease/epoch를 사용한다. `FakeStageWork`의 IMAGE/TTS/METADATA/QC는 대역 효과, SUBTITLE/RENDER는 대역 참조다. | 기존 단계 수·순서·요청 lease·최대 두 worker 유지. IMAGE/TTS 내부에서만 여러 효과를 순차 실행한다. SUBTITLE/RENDER/METADATA/QC는 로컬 처리한다. |
| `ProductionPlanning`, `PlanningExecutionService`, `PlanningResultMapper` 및 V12 `production_stage_result`는 SCRIPT segment ID·원문과 SCENES 순서·segment ID·imagePrompt·direction·transition을 제공한다. V12는 operation 단위이며 트리거가 RESEARCH/SCRIPT/SCENES만 허용한다. | 같은 요청/run/revision에 적용된 성공 결과만 읽고 저장 해시·검수 차단 여부를 검증한다. V12에 미디어를 넣지 않는다. |
| V9 `production_request`는 config ID, run, 요청 한도와 lease를 가진다. `production_step`은 단계 참조 하나, `provider_operation`은 효과별 예약·불변 증거·정산·적용을 보존하며 동일 step에 여러 operation을 매핑할 수 있다. `ProductionCostService.reserve`의 현재 키 검사는 step 자체의 target에 묶여 있다. | 현재 요청·step·비용 원장을 사용한다. 미디어용으로 검증된 하위 작업만 키 검사에 추가하고, 한 단계 완료와 개별 영수증 적용을 분리한다. |
| V4/V6의 channel config, content revision, run은 존재한다. [기술 문서의 파일 및 부분 재생성](02_TECHNICAL.md#파일-및-부분-재생성)의 Workspace는 파일 계약이며 기준 트리에는 미디어 자산/manifest 테이블이나 Workspace 운영 구현, FFmpeg 실행기가 없다. | Workspace 계약을 구현하고 V9·V12·V4·V6 원본은 수정하지 않는다. 미디어 파일/입력 레코드만 승인된 신규 migration 한 개로 추가한다. |
| `ProductionRequestController`의 요청 상세·planning 등은 `CurrentActor`와 요청 소유권을 사용한다. `ProductionRequestDetailPage.tsx`에는 세션 범위 쿼리, 상태 폴링, FAKE 완료 안내가 있다. | 상세 화면과 인증을 재사용하고, 미디어 조회와 파일 스트리밍을 추가한다. |

승인된 `v2-media-1`은 기본 비활성이다. 운영 기능 플래그와 제작 요청의 명시적 미디어 선택이 모두 활성화된 신규 지원 SHORTS에만 버전을 동결한다. 요청 생성과 worker claim의 준비 조건을 공유한다. 미디어 모드가 명시적으로 활성화됐으나 준비되지 않으면 새 요청에서 기존 선행조건 오류를 반환하고, 이미 저장된 미디어 요청은 claim 대상에서 제외한다. 운영 UI에는 준비 실패 원인을 표시한다. 미해결 효과와 미적용 영수증은 복구 전용 claim으로 처리한다. 증거 조회·정산·취소 및 파일 복구는 각 작업 자체의 전제 조건만 적용한다. FFmpeg 준비 실패 중에는 신규 발송과 로컬 렌더링을 차단한다. 기존 버전과 LONGFORM은 기존 라우팅을 유지하며 기존 요청을 자동 전환하지 않는다. 복구 시에도 저장된 버전을 적용한다. 기술 준비 상태와 별개로 이미 진행된 비용 효과는 증거 조회·정산 복구가 가능해야 하므로, 미디어 전제 조건 실패를 정산 완료/취소의 전제 조건과 분리한다.

파이프라인 문자열을 추가할 때 `ProductionDiscovery.supportsPipeline`, `ExecutionMapper`의 claim/분기, `ProductionRequestService` 생성, `PlanningResultMapper` snapshot 및 `ProductionCostService`의 planning reserve/reconcile 조건을 함께 점검한다. 재사용 단계의 기존 입력/해시 형식과 검수는 유지한다. SCENES 성공 후 IMAGE 시작 전에 미디어 설정과 대상 목록을 한 트랜잭션에서 확정한다. 요청 생성 시 미디어 설정을 고정하는 방법은 D09에 명시한다. QC 통과 후 기존 `SUCCEEDED`를 쓰고 FAKE 기술 결과로 표시한다. 별도 REVIEW_READY 상태 추가는 후속 승인으로 남긴다.

## D01. IMAGE

- 입력은 성공한 SCENES의 장면 ID·순서·`imagePrompt` 원문, 시각 스타일, 이미지 프로필이다. 기존 ScenesReview의 구조 검증(1~20장)을 통과한 장면 목록을 모두 사용한다. 8~12장은 권장값으로만 표시한다. 구현 시 전체 byte/픽셀 상한을 두어 장면 수가 늘어도 자원 사용을 제한한다.
- 내부 `ImageProvider.generate(ImageInput)`는 버전화된 요청에 대해 제한된 바이트 스트림과 포맷·출처·사용권·공급자 참조를 반환한다. FAKE는 요청 해시로 매핑되는 권리 확인된 JPEG/PNG fixture와 오류 fixture를 사용한다. fixture 카탈로그 버전과 실제 파일 SHA-256을 고정한다. fixture 모드도 비용 경로를 통과하는 테스트를 둔다.
- 장면별 정규 UTF-8 JSON에 요청/run/revision, SCENES 본문 해시, scene ID, 원문, 설정 및 provider/fixture 버전을 저장하고 SHA-256을 계산한다. 결과는 별도 파일 SHA-256으로 연결한다. 단계 순서와 하위 효과 키는 표시명에 의존하지 않는다.
- 수신 상한은 파일당 16 MiB, 최대 16 MP다. magic bytes, 실제 이미지 디코딩, 단일 프레임 JPEG/PNG와 orientation 정규화 후의 픽셀 크기를 검사한다. 최소 720×1280, 권장 1080×1920 이상이다. 장면 등급·입력 목표·출력 종횡비는 구조화된 프로필과 item에 고정한다. 화면비가 다르면 종횡비를 유지한 맞춤 및 패딩을 기본으로 하고, 안전 영역 내 허용된 크롭만 적용한다. 늘이기와 무제한 확대를 차단한다. 변환 후 1080×1920, 유효 이미지 영역·확대율·잘림을 검사하고 확대 화질은 대표 fixture를 시각 확인한다. 유효 배율은 (1080 / 1080px 캔버스에 대응하는 유효 원본 폭) × 최대 zoom이다. 유효 원본 폭은 원본 폭에 캔버스 폭/fit 후 콘텐츠 폭을 곱한다. 1.5배 초과 경고, 2배 초과 차단, 원본 내용 크롭은 축당 10% 이하이다. 비율 불일치 기본은 fit+pad이며 과도한 검은 여백을 QC 경고로 남긴다. 원본 및 변환본의 SHA-256과 변환 설정을 보존한다. 실패 시 D06에 따라 현재 장면을 차단하며 앞서 적용된 장면은 해시 검증 후 재사용한다.

## D02. TTS

| 생성 단위 | 발화·비용·복구 |
|---|---|
| 문장/segment | cue 근거와 재생성 단위가 세밀하다. 호출 수·최소 과금 및 문장 경계 이질감이 커질 수 있다. |
| 장면 | 자연스러운 장면 내 발화와 장면별 복구를 함께 확보한다. 장면당 호출과 장면 안의 정렬이 필요하다. |
| 전체 | 문맥과 억양 연속성이 좋고 호출 수가 적다. 부분 변경·복구 시 전체 호출 및 장면 정렬 비용이 발생할 수 있다. |

**승인** 초기 장면 단위. SCRIPT의 segment ID와 원문을 SCENES 순서에 따라 조회한다. 장면 내 결합 구분자와 원문 해시를 고정하고 SCENES가 파생한 narration과 일치하는지 검사한다. 누락·중복·순서 불일치는 생성 전 차단한다. `SpeechProvider.synthesize(SpeechInput)`의 입력에는 원문, 언어, provider, 모델, Voice ID, 속도, 스타일, format 버전이 들어간다. 출력은 제한된 오디오 및 선택적 시간표와 출처다. FAKE는 SCRIPT fixture 원문과 매핑되는 권리 확인된 실제 낭독 WAV와 시간표를 사용한다. 임의 원문에 다른 대본 오디오를 붙이지 않도록 fixture 부재는 선행조건 실패로 처리한다. 동일 요청의 모든 장면에서 동결된 모델·Voice ID·속도·스타일을 적용한다. 향후 전체 대본 생성은 입력 범위와 장면 정렬을 별도로 버전화해 추가할 수 있다. 실제 공급자의 비용과 자연스러움은 V2-05 표본에서 세 방식으로 비교한다.

오디오는 PCM 48 kHz로 정규화해 디코딩된 sample count를 측정한다. `ffprobe`의 스트림 정보와 대조하며 추정 대본 길이는 표시용이다. 시간표는 segment ID, 오디오 기준 시작/끝, 기준 단위 및 출처를 저장한다. scene 경계는 누적 샘플에서 프레임으로 반올림하고 최종 영상은 마지막 오디오까지 덮도록 올림한다. 장면 전환은 앞뒤 영상 핸들에만 겹치며 음성을 잘라내거나 앞당기지 않는다. 초기 인수 SHORTS 프로필은 실제 발화 40~60초를 정상 범위로 사용한다. 동결된 허용 구간 밖이면 QC에서 기술 차단하고 완료 파일로 제공하지 않는다. 원문 절단, 시간 맞춤용 무리한 음성 변형, 무음 채우기로 통과시키지 않는다. 자동 유료 재생성과 대본 재작성은 V2-05 범위다.

## D03. SUBTITLE

- 버전화된 정렬 JSON을 기준으로 SRT(검토·다운로드)와 ASS(번인·스타일)를 작성한다. cue는 원래 segment ID와 원문 slice의 경계를 참조한다. 줄바꿈은 표시 메타데이터로 취급한다. 중복 공백 축약·문장 재구성·번역을 하지 않는다.
- 출처는 검증된 실제 정렬(단어/segment 정밀도 별도), 검증된 로컬 fixture 시간표, 추정으로 구분한다. provider 타임스탬프는 실제 오디오와 단위·순서·범위가 확인될 때만 채택한다. 초기 FAKE 실재 싱크 인수는 원문에 맞는 fixture 시간표로 확인한다. 추정만 있으면 진단 표시는 가능하나 싱크 통과를 부여하지 않는다. word timing이 없으면 segment 강조를 사용한다.
- 샘플을 최근접 프레임으로 양자화한다. ASS centisecond는 floor(frame×100/30), SRT ms는 round(frame×1000/30)이다. 시작 포함·끝 제외, 12프레임 미만은 경고다. 내보낸 cue도 양의 길이, 단조 증가·비겹침, 실제 음성 범위, 원문 coverage를 재검사한다. 너무 짧은 cue는 원문과 시간을 지키며 병합하거나 QC에 기록한다.
- 안전 영역은 1080×1920 기준 좌우 10%, 상단 12%, 하단 20% 여백의 초기값이다. 실제 플레이어·폰트의 글리프 경계로 검사한다. 글꼴 파일·크기·색상·강조는 검증된 채널 프로필 값으로 제한하고 ASS 제어 문자는 데이터로 이스케이프한다. 공식 정적 Noto Sans Bold 계열 76px, 외곽선 5~6px, 최대 두 줄을 적용한다. bbox에 맞춰 크기를 낮추고 여전히 벗어나면 차단한다. 영어·한글 두 줄과 폴백 차단을 검증한다. 역슬래시 뒤 U+2060을 삽입하고 중괄호 앞에 역슬래시를 붙인다. 줄바꿈 및 제한된 색·크기 강조와 Pop/Fade는 코드에서 조립한다.

## D04. RENDER

1. D02의 샘플 기준 타임라인 및 확정 자산 해시를 검증한다. RENDER 입력 해시에 자산 순서, 믹싱·전환·출력 설정, 자막·폰트 해시, FFmpeg 빌드 및 템플릿 버전을 포함한다.
2. 최종 해상도는 1080×1920으로 고정한다. 30 FPS CFR, H.264 High, yuv420p, BT.709 limited range, AAC-LC stereo 48 kHz, faststart MP4를 적용한다. 출력 scale/format/setparams 및 인코더에 BT.709 limited를 고정한다. 정확한 9:16 캔버스에서 zoompan을 적용한다. STATIC, ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN, PUNCH_IN을 지원한다. PAN은 zoom 1.1 이상에서 크롭 한도를 만족하는 이동으로 제한한다. 모든 클립과 concat 출력에 settb=1/30을 적용하고 FADE 기본값은 15프레임이다. SCENES의 장면별 direction을 정지·확대·축소·좌우/상하 이동 preset에, transition을 전환 enum에 매핑한다. 장면마다 입력에 맞는 preset을 선택하며 전 장면에 같은 움직임을 강제하지 않는다. 화면비는 D09의 구조화된 출력 프로필에서 읽으며 프롬프트 내 화면비 문구는 필수 입력이나 검증 조건에 포함하지 않는다. 허용 전환은 cut·fade부터 시작하며 짧은 씬은 cut으로 고정한다. 장면 핸들로 겹침을 채워 최종 프레임 수를 보존한다. 첫 검증 프로필은 실제 내레이션 40~60초를 적용한다.
3. 내레이션을 기준으로 선택적 BGM/SFX의 시작·끝·gain을 제한하고 fade, mix, 정규화를 적용한다. 스테레오 믹스에서 내레이션 우선 ducking과 SFX 동기화 후 2-pass loudnorm I=-16, TP=-1.5, LRA=11을 적용하고 normalization_type을 기록한다. 최종 AAC 디코딩 후 -18~-14 LUFS, true peak ≤ -1 dBTP를 확인한다. 허가된 음원이 없으면 내레이션만 사용한다. ASS 번인과 QC에서 자막 시간표 및 출력 영상은 동일 manifest 계보로 묶는다.
4. [공통 프로세스 포트](V2-04_COMMON_CONTRACT.md#프로세스와-자원)에 절대경로 실행 파일과 코드가 조립한 개별 인수를 전달한다. 셸, AI 명령·필터·경로, 네트워크 입력을 사용하지 않는다. 원문의 direction/transition은 허용 enum으로만 매핑하고 미지원은 기본 preset 및 진단을 기록한다. 필터 그래프는 고정 템플릿과 범위 검사된 수치로 만든다. 전용 scratch 작업 디렉터리 아래 ASCII 별칭을 사용해 ASS/concat 내부 경로 파서와 Windows 드라이브 문자 문제를 줄인다. 원래 Workspace의 공백·한글 경로는 Java 파일 API 및 개별 인수로 취급한다.
5. stdin 차단, stdout/stderr 동시 소비와 크기 제한, probe/render별 wall-clock timeout, 취소·lease loss 감시를 적용한다. 중단 시 자식과 부모에 종료 요청 후 제한 시간 내 강제 종료하고 종료를 확인한다. Windows Job Object 및 Linux 격리의 실제 종료를 구현 인수에서 검증한다. 고아 프로세스·잠금이 남으면 해당 scratch를 격리하며 stale worker의 결과는 채택할 수 없다.
6. 시작 시 검증할 항목은 FFmpeg/ffprobe 실행·버전 일치, H.264/AAC 인코더, 필수 `scale/pad/zoompan/fps/xfade/subtitles(or ass)/amix/loudnorm/silencedetect` 등과 libass 폰트 실제 렌더링이다. 작은 encode/decode smoke로 기능을 확인한다. 회사 PC에서 Arial·맑은 고딕 파일명을 확인했다. 집 PC PowerShell에서는 `Get-Command`와 `where.exe`로 ffmpeg/ffprobe를 찾지 못했다. 설치 경로, 버전·인코더·필터·실제 글리프는 미검증이다. 실행 파일 위치·지원 빌드·폰트·배포 라이선스는 구현 시 고정한다.

## D05. 미디어 저장 및 장애 경계

**승인된 스키마 범위** 아래 두 테이블과 신규 migration 한 개. 버전은 V2-03 최종 통합 트리에서 다음 가용 번호로 확정한다. 다음 두 레코드를 분리해 하위 효과의 입력 동결과 파일 확정 상태를 유지한다. [공통 계약](V2-04_COMMON_CONTRACT.md)에 컬럼·제약·상태를 명시한다.

| 테이블 후보 | 계약 |
|---|---|
| `production_media_item` | 복합 request/step FK, stage 내부 target, generation, 정규 입력 JSON/hash, 순서, operation FK(nullable, 유일), 불변 공급자 응답/비용 증거 참조 및 파일 확보 상태, 적용 asset 또는 결과 bundle 참조. `(request_id,step_id,target_key,generation)` 유일. 요청 스냅샷은 예약된 PROFILE 항목으로 요청당 한 건 저장. 입력과 기대 대상 집합 불변. |
| `production_media_asset` | 서버 내부 asset ID, item FK, 역할, 상대 경로 유일, MIME/byte 길이/SHA-256, 입력·부모 해시/권리, 파일 확정 상태·작성 epoch 및 UTC 시각. 같은 item·역할의 채택 파일 유일. 오디오 시간표, SRT/ASS, MP4, QC, manifest를 역할로 기록한다. |

DB 메타데이터는 소유자 조회 및 복구의 기준이다. 바이너리는 DB에 저장하지 않는다. Workspace는 `workspace/{channelId}/{contentId}/{runId}/`와 문서의 inputs/assets/audio/subtitles/render/manifest 구조를 사용한다. PROFILE은 content/run 연결 전 DB에만 존재할 수 있다. manifest는 schema version, 요청/channel config/revision/run, SCRIPT/SCENES 참조, item/asset ID·해시·바이트, 시간표, 실행기·도구 설정, 권리, 효과/정산 ID, 제작 모드, QC 링크를 포함한다. METADATA가 미디어 목록과 기술 메타데이터를 만들고 QC가 이를 검증해 최종 manifest를 확정한다. YouTube용 제목·설명·태그·게시 설정과 합성 표시 판단은 V2-06 게시 계약에 둔다. manifest 본문에 자기 해시를 넣지 않고 DB에 해시를 저장한다. 미래 승인 대상으로 조회되는 MP4/manifest는 확정된 파일이어야 한다.

**파일 확정 프로토콜**

1. 발송 허가 뒤 반환된 응답의 실행 여부, 공급자 참조, 금액·통화와 증명, 예상 결과 식별자를 operation에 연결한다. 확정 비용 증거는 operation에 연결된 item에 바이너리 저장을 시작하기 전에 짧은 DB 트랜잭션에서 불변으로 보존한다. 신뢰할 수 있는 증거만 수용하며 금액이나 실행 여부가 불명인 경우 기존 조사·예약 보호로 이동한다. 응답 반환부터 증거 커밋 사이에 프로세스가 종료되면 공급자 조회 등 승인된 증거 경로로 조사한다.
2. 같은 볼륨의 per-attempt scratch에 쓰고 크기 제한, 포맷 검사, SHA-256 및 파일 flush를 끝낸다. 현재 lease 아래 예상 해시·크기·경로·item/epoch를 포함한 staging 의도를 기록한 뒤 서버 생성 경로로 원자 이동한다. 기존 파일은 덮어쓰지 않고 같은 해시만 재사용한다. 원자 이동 실패 시 staging을 유지한다. 복구자는 현재 lease에서 경로·해시를 다시 검증해 이동 또는 확정을 재개한다. 파일 I/O와 검증은 DB 잠금 밖에서 수행하고 채택 시 epoch와 staging 참조를 재검사한다.
3. 완전한 결과 bundle을 확정할 수 있으면 자산 참조와 기존 비용 엔진의 불변 `RESULT_ADOPTED` 증거를 같은 DB 트랜잭션으로 기록한다. 확정 전 일시 저장·이동 실패는 파일 복구 대상으로 분류한다. 파일 쓰기 실패, 형식 오류 또는 자산 유실이 복구 불가로 판정되면 보존한 비용 증거에서 `EXECUTED_FAILED`를 확정하고 비용을 정산한다. 복구 결정은 FFmpeg 준비와 독립이며 무한정 대기하지 않도록 공통 계약의 복구 기한을 적용한다.
4. 최종 비용 증거 커밋 후 별도 트랜잭션에서 기존 엔진으로 정산한다. 영수증 재생은 D06에 따르고, 정산 이후 파일이 유실되면 기존 영수증을 보존하며 파일 복구 또는 기술 차단으로 처리한다. 발송된 효과는 같은 해시의 신뢰 가능한 파일로만 복구한다. DB 의도가 없는 파일은 유예 후 격리한다. 로컬 산출물은 동일 입력 재계산이 가능하며 오래된 worker의 scratch와 활성 파일을 분리한다.

파일 경로는 숫자 ID·서버 생성 이름에서만 조립한다. root real path와 containment를 확인하고 경로 구성요소의 symlink/junction/reparse point를 거부한다. root는 서비스 계정 전용 쓰기 권한, scratch/final은 외부 임의 쓰기 불가로 구성하고 파일 open에서 재검사한다. 정적 디렉터리 노출은 금지한다. 디스크는 요청별·전체 제한, scratch 예상 사용량 예약과 실제 쓰기 상한, 여유 공간 하한을 함께 적용한다. 두 worker의 예약 합을 반영하고 공통 계약의 제한값이 강제되는지 OS별 smoke에서 검증한다. 영수증/manifest/복구/승인에서 참조되는 자산을 보존한다. 사용이 끝난 APPLIED attempt의 임시 복사본은 Workspace 자산을 검증한 뒤 바로 정리하며, 실패·취소·중단 후 잔존 파일은 [공통 계약의 보존 기준](V2-04_COMMON_CONTRACT.md#상태와-장애-복구)을 적용한다. DB와 Workspace를 함께 백업·복원하고 복원 검증에서 파일 해시를 대조한다.

## D06. 비용 보호 및 재시작

- IMAGE와 TTS는 장면당 하나씩의 유료 논리 효과를 FAKE로 시뮬레이션한다. 키는 `EffectKey.forInput(requestId, stage, serverTarget, generation)`을 재사용하되 `serverTarget`을 해당 step의 확정 media item에서 읽는다. 정규 입력 해시와 기존 가격/통화/최대액 해시를 함께 검증한다. 기존 step 소유자 검사를 유지하고 request, pipeline, IMAGE/TTS stage, item, generation, 해시 일치가 확인된 미디어 경로만 허용한다. 하위 효과별 비용 예약과 기존 요청·일·월 한도 확인, dispatch 허가, 정산, 불명 격리는 기존 비용 엔진에 둔다.
- 기존 `ProductionCostPort.executeEffect`는 일반 FAKE 요청이고 파일 콜백 계약이 없다. `executePlanning`의 패턴대로 미디어 전용 제한된 진입점과 증거 저장 분기를 추가한다. `ProductionCostService`의 공통 예약/발송 허가 트랜잭션, `readCostState`, 증거 저장 후 분리된 정산, 영수증 재생 및 `markReceiptApplied`를 재사용한다. 공급자 호출과 파일 I/O는 DB 트랜잭션 밖에서 수행한다. FAKE 가격은 테스트 설정의 명시적 모의 KRW이며 UI와 manifest에도 모의 비용임을 표기한다.
- 하위 작업은 하나씩 예약·발송·기록·정산·적용한다. 같은 요청에 미해결 다른 operation이 있을 때 dispatch를 막는 현행 규칙과 일치한다. `runMedia` 는 Execute와 Recover 경로에 모두 연결한다. `RESULT_ADOPTED` 영수증마다 파일 및 작업을 검증해 item 적용 표시와 `markReceiptApplied`를 현재 lease 트랜잭션에서 처리한다. 모든 기대 item이 적용·정상일 때만 단일 상위 step을 성공시킨다. 기존 `applyReceipt`의 즉시 step 성공 분기를 장면 처리에 직접 사용하면 중간 결과로 종료되므로 전용 적용이 필요하다.

| 장애·경합 | 복구 시 기대 동작 |
|---|---|
| 예약 후 발송 전 재시작/취소 | 같은 키로 예약을 재사용한다. 취소·발송 기간 만료는 기존 미발송 영수증 경로를 쓴다. |
| 발송 허가 후 타임아웃, 결과 증거 없음 | UNKNOWN/복구 필요 및 예약 보존. 기존 reconcile로 확인하고 자동 재호출·대체 호출·세대 증가를 막는다. |
| 공급자 반환 후 형식 오류 | 확정 비용이 있으면 EXECUTED_FAILED 증거로 정산하고 기술 오류를 기록한다. 비용 자체가 불명이면 복구 상태를 유지한다. |
| 공급자 실행·비용 확정 후 scratch 쓰기 실패 | D05에서 커밋한 비용 증거를 유지한다. 동일 결과 복구를 시도하고 복구 불가 판정 시 실행 실패로 정산한다. |
| staging 이후 이동·파일 확정 장애 | 동일 staging과 해시로 재검증한다. 실행 여부와 비용 증거는 유지한다. |
| 최종 비용 증거 커밋 후 정산 장애 | 동일 불변 증거로 기존 정산을 재생한다. FFmpeg 준비 및 자산의 후속 손상과 독립적으로 처리한다. |
| 정산 후 적용 장애 | 새 lease는 같은 영수증과 파일을 검증해 트랜잭션으로 적용한다. 적용 직후 종료돼도 다음 worker가 같은 장면을 재발송하지 않는다. |
| 사용자 취소·stale lease | 신규 발송과 최종 산출물 채택을 차단한다. 발송된 결과 증거와 정산은 보존하고 기존 취소 상태 전이에서 필요한 영수증을 처리한다. 성공 자산 채택이나 신규 발송 없이 비용을 정리한다. stale worker의 stage/item 적용 권한은 없고, 발송과 결합된 늦은 증거는 기존 운영 키·소유권과 일치할 때만 수용한다. 동일 증거의 재수신은 멱등으로 처리하고 상충하는 증거는 기존 지출 차단·조사 경로로 넘긴다. |
| 파일 누락·손상 | 같은 해시의 백업/fixture가 있으면 폐쇄된 경로로 복원한다. 유료 자산을 복원할 수 없으면 보호 상태로 남기고 자동 결제하지 않는다. 새로운 유료 효과는 별도 승인된 재생성 계약에서만 다룬다. |
| FFmpeg 실패 또는 재시작 | 비용 포트를 호출하지 않는다. 확정 자산·입력 해시를 다시 검사하고 기존 local attempt/backoff(최대 세 번) 내에서 일시 오류만 재시도한다. 프로필·권리·무결성 오류는 직접 차단한다. |

## D07. 기술 QC

- ffprobe JSON과 영상·음성 전체 디코딩을 사용한다. 기대 스트림·코덱, 1080×1920, CFR 30, 실제 재생 시간·PTS, decode 오류, 바이트 길이·SHA-256을 검사한다. 첫 Shorts 표본은 디코딩된 내레이션 샘플로 40~60초를 검사한다. 영상은 마지막 샘플까지 덮어야 하며 컨테이너 길이는 아래 프레임·AAC 허용 오차로 별도 검사한다. AAC priming과 frame rounding은 decode 결과 기준으로 다루고 디코딩 프레임 수는 계획값, 오디오 샘플 수는 프레임×1600과 일치해야 한다. 컨테이너 스트림 길이 차 최대 1프레임, 동기 계측 최대 1프레임 + AAC 1패킷이다. 장면 내부 무음은 1.2초부터 경고, 2초부터 차단한다.
- 무음·비프음 fixture는 정상 내레이션 인수에서 제외한다. SCRIPT에 대응하는 실제 낭독의 원문 매핑·권리·파일 해시와 사람이 확인한 발화 표본을 증거로 남긴다. 디코딩한 오디오의 loudness/peak, 장시간 무음, clipping을 검사한다. 장면 coverage는 manifest의 장면별 frame 구간과 단일 ordered timeline으로 교차 확인하고 식별 가능한 이미지 fixture로 실제 경계 프레임을 표본 검사한다. 자막은 alignment·SRT·ASS의 원문 coverage/시간과 번인 시각 표본을 검증한다. 미디어 누락·손상, 권리 불명, 누락/겹친 cue, 실제 정렬 증거 누락은 기술 통과를 막는다.
- 차단과 권고, 측정값, 대상, 검사 버전 및 제작 모드를 저장한다. 배경음·효과음·폰트·fixture는 파일 해시와 출처/권리 근거가 있어야 하며 상업 이용 범위가 불명확한 항목은 사용하지 않는다. 기술 PASS는 로컬 재생 가능과 계약 충족을 뜻한다. V2-05 실제 공급자 및 인간 검수 전 수익화·콘텐츠 품질 검증 상태는 미검증이다.

## D08. 제작 상세 UI 및 HTTP

- 기존 상세 페이지에 장면 이미지, 내레이션 audio, 큐 및 SRT 조회, QC, MP4 video·다운로드를 추가한다. 로딩·미생성/구버전·오류·권한·세션 만료에 기존 컴포넌트와 세션 쿼리 패턴을 사용한다. 최종 버튼은 확정되고 QC가 통과한 파일에만 활성화한다. 라이트·다크와 모바일을 UI 토큰으로 지원한다.
- API는 `GET /api/v1/production-requests/{id}/media` 및 `GET/HEAD /api/v1/production-requests/{id}/media/{assetId}`다. 목록에는 공개 가능한 ID, 타입·길이·hash·단계·모드·QC만 포함한다. 모든 요청에서 인증 후 요청 소유권과 asset의 request/run 연결·확정 상태를 검증한다. 미인증 401, 타 소유자/존재하지 않는 대상 404로 기존 계약을 따른다. 손상은 관리 경로와 UI에 안정된 오류로 노출하고 파일 경로·실행 로그 원문을 반환하지 않는다.
- 기존 세션 쿠키와 같은 origin의 미디어 URL을 사용한다. 비디오·오디오 seek를 위해 단일 byte Range와 open-ended/suffix Range를 지원하고 적합한 `206 Content-Range`/길이 및 범위 불가 `416 bytes */length`를 반환한다. 다중 Range는 전체 200으로 처리한다. 인증·권한 확인은 HEAD/Range에도 동일하다. `Content-Type`, `nosniff`, 비공개 캐시 정책, 서버 생성 파일명을 쓰고, 다운로드에는 attachment를 제공한다. 정적 경로 우회, 전체 파일 메모리 적재 및 URL 토큰 노출을 막는다. 큰 파일의 Range별 무결성 검사 비용은 파일 보호/확정 단계 전체 해시와 제공 중 크기·파일 식별 확인, 재검증 정책을 실제 seek 테스트에서 검증한다. 외부 수정이 감지되면 제공을 중단한다.

## D09. 채널 확장

- V4의 불변 config ID와 기존 언어, 분야/전략, audience, narration/visual style, SHORTS 범위를 그대로 읽는다. 음성·속도, 이미지/음성 provider, subtitle style, 출력 preset, 권리 승인 자산 목록은 신규 설정이다. 승인된 채널별 미디어 프로필은 설정에서 채널 ID에 매핑한다. 요청 생성 트랜잭션에서 공급자·모델·Voice ID·속도·스타일과 버전이 있는 자막·출력 프로필을 정규 JSON 및 SHA-256으로 PROFILE item에 동결한다. PROFILE item은 기존 IMAGE root step 생성 후 동일 트랜잭션에서 삽입한다. 설정 변경은 이후 생성된 요청에 반영된다. V2-04는 신규 설정 관리 UI와 채널 테이블을 추가하지 않는다. PROFILE은 단일 음성과 [콘텐츠 설정](V2-05_IFNOVA_CONTENT.md)을 수용한다. 캐릭터 목록은 비어 있어도 정상 제작하며 캐릭터 기능은 BACKLOG다. 관리 UI는 V2-07 범위다.
- 허용 provider는 FAKE/로컬 fixture만 등록한다. IFNOVA 영어 Shorts는 표본 채널이다. 두 과학 소재 외에 독립 분야·언어 또는 별도 voice/style 프로필이 동일 애플리케이션으로 선택되는 테스트를 둔다. 한 요청의 프로필을 동결한 뒤 설정을 변경하여 기존 요청과 이후 요청의 라우팅 및 해시가 각자 유지되는지 확인한다. 영어 fixture만 준비된 경우 타 언어는 명시적 fixture 준비 오류를 내며 호환 설정의 동결/라우팅은 별도 표본으로 검증한다.

## D10. 구현 인수 기준

| 인수 항목 | 실행 및 증거 |
|---|---|
| 실제 MP4 2편 | 서로 다른 과학 소재 두 개를 실제 DISCOVER→QC에서 제작. FAKE 장면 이미지와 해당 SCRIPT의 로컬 낭독 사용. 실제 FFmpeg 버전·빌드 옵션·manifest·SHA-256과 ffprobe JSON/decode 결과를 기록. 두 낭독은 각각의 SCRIPT 원문과 대응하고 사용권을 확인한다. 무음·비프음은 실패 표본으로 쓴다. 두 파일 모두 1080×1920·30 FPS·40~60초, H.264/AAC, 샘플/자막 시간표 및 장면 경계 확인. |
| 복구와 비용 | 실제 비용 포트와 격리 DB에 장애 주입. 예약·발송·응답·비용 증거 커밋·scratch 저장·원자 이동·파일 확정·정산·적용 각 경계에서 종료 후 재실행. 각 장면의 dispatch/예약/원장/영수증 건수와 동일 해시를 대조. 파일 저장 실패 뒤 비용 정산, 파일 확정 실패와 실행 불명 구분, FFmpeg 미준비 상태의 정산 복구를 별도로 확인한다. 두 worker·lease 만료·취소·예산 부족·타임아웃·정산 충돌·파일 손상 및 FFmpeg 재시도에서 추가 유료 효과가 생기지 않아야 한다. |
| 저장·프로세스 안전 | Windows 한글·공백 root와 Linux, traversal/링크·junction, 승인되지 않은 입력/필터, 초과 크기·디스크 한도, scratch orphan, 시간 제한·취소·자식 종료, 누락/손상 재검사를 확인. |
| Migration·회귀 | [격리 테스트 가이드](../guides/LOCAL_TEST_GUIDE.md)에 따라 빈 MariaDB와 최종 기준 migration까지 적용한 기존 요청 DB에서 신규 migration 적용. 기존 요청·정산 자료 보존, 새 단계 실행 및 V2-01~03 회귀, 기존 LONGFORM 경로 확인. `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 및 Linux wrapper 대응. |
| 브라우저와 계약 | 생성 OpenAPI 타입, 프론트 lint/typecheck/build/Vitest, 실제 백엔드+DB+FFmpeg로 브라우저 로그인 후 이미지·audio·자막·MP4 재생/seek/download. 200/206/416/401/404, 타 소유자 ID 조합·세션 만료를 확인하고 라이트·다크·모바일에서 화면을 검증한다. |
| 최종 인수 | 샘플은 Git 밖에 두고 기록만 보존한다. 최신 PR HEAD CI, 독립 리뷰, 사용자 검증 및 승인으로 종료한다. 구현 검증을 완료한 시점의 상태는 IMPLEMENTED다. |

## 개발 분담과 인수인계

Codex는 공통 계약·migration·저장·비용 복구·프로세스·API를 고정한다. Claude Code는 [착수 지시서](V2-04_CLAUDE_HANDOFF.md)에 따라 같은 고정 SHA에서 영상 처리와 미리보기 UI를 구현한다.

- R01 → D01·D07·D10, R02 → D02·D09, R03 → D01·D04·D09, R04 → D02·D07, R05 → D05·D06·D10, R06 → D03·D09·D10, R07 → 통합 경계·D02·D04·D05·D07·D08·D10.
- 스파이크 PASS는 Windows 렌더·QC 표본과 Job Object 종료 모델에 해당한다. JNA·Linux·권리 확인된 실제 낭독을 포함한 MP4 두 편은 구현 인수에서 검증한다.
- V2-03 DONE 뒤 PR #6의 관측 HEAD와 최종 통합 SHA 사이 discovery/planning·실행·비용·migration·채널 snapshot·API/UI 차이를 대조한다. 승인 계약 충돌은 DECISIONS.md에 제안하고 영향을 받는 구현을 보류한다.

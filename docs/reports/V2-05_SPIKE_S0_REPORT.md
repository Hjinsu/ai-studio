# V2-05 S0 공급자 스파이크 무과금 준비 보고서 (최종 v4)

**IMPLEMENTED (S0 문서·로컬 준비)** · 2026-11-27 · 담당 Claude Code(S2~S4 미디어 스파이크) · 브랜치 `v2/05-spike` · 기준 `4539848`(설계 SHA `5d6fa55`) · [인수인계](../v2/V2-05_SPIKE_CLAUDE_HANDOFF.md) · [스파이크 계약](../v2/V2-05_PROVIDER_SPIKE.md)

S0에서 보낸 유료 호출과 생성 요청(이미지·동영상·음성)은 모두 0건이다. 공급자 API에 보낸 요청도 0건이다. 결제·충전, 제품 코드, DB·migration은 건드리지 않았다. Codex의 외부 AI Worker 브랜치와 파일도 수정하지 않았다. 원문 근거와 상태(VERIFIED / NOT_VERIFIED / UNKNOWN)는 작업 폴더의 `S0/sources.md`에 있다.

**승인되지 않은 것**: 유료 API 호출, 생성 요청, 결제, 충전, 제품 구현, DB·migration 변경. 이 보고서를 제출한 뒤 사용자 승인을 기다린다.

## 0. 사용자 결정 (누적, 최종)

| 항목 | 결정 |
|---|---|
| 역할 | Claude는 S2~S4 미디어 스파이크를 맡는다. Codex는 외부 AI Worker 검증을 별도로 진행한다(Claude는 손대지 않음). **S1 대본 비교는 보류** |
| 공급자 | 이번 스파이크는 **Gemini API와 fal만** 쓴다. OpenAI, Anthropic, Runway, ElevenLabs, Google Cloud TTS는 대체 후보로 보류한다. 자동 충전과 자동 공급자 전환은 금지한다 |
| S2 TTS | `gemini-3.8-flash-tts`, Puck, ko-KR, single speaker. 7회(A, B, C 전체, C 장면별 4). 사용자 지정 영어 연출 프롬프트에 장면별 조정을 붙인다 |
| S3 이미지 | Nano Banana 2 Lite(`gemini-3.1-flash-lite-image`)와 `fal-ai/flux/schnell` 비교. 고정 캐릭터는 강제하지 않는다 |
| S4 동영상 | `wan/v2.6/image-to-video/flash`, `wan/v2.6/text-to-video`. **실제 피사체 움직임이 핵심**이며 단순 확대·축소는 불합격이다. 해상도, 길이, 과금 단위를 호출 전에 검증한다. Kling 등 대체 모델은 조사만 하고, 호출하려면 별도 승인을 받는다 |
| 자막 | IFNOVA 자체 자막 OFF. 영상 내 삽입, SRT·ASS, STT, forced alignment, 자막 정렬 평가를 모두 제외한다. 실제 음성 길이 측정과 장면 타이밍 동기화는 유지한다. V2-04 자막 구현은 보존한다 |
| 최종 영상 | 하루 1~2편, 한국어 과학 코미디, 40~60초, **1080×1920 30fps**. 자연스러운 내레이션, 실제 AI 동영상, 정지 이미지, 자체 애니메이션, BGM·SFX. 장면 수, 미디어 수, 비율, 길이는 고정하지 않고 동적으로 정한다. **영상당 최대 3,000원** |
| 실험 상한 | 전체 **3,500원** 유지(이전 결정) |
| 비용 계약 | 예상 청구액과 최대 청구액을 구분한다. 출력 상한이 과금에 적용된다는 근거가 없으면 보장이라고 표현하지 않는다. 초과 후 중단은 사전 제한으로 보지 않는다. 합리적으로 제한할 수 없는 호출은 보류한다 |

## 1. 작업 폴더 (저장소 밖)

`C:\Users\rlead\AI-Studio-V2-05-Spike\` — 생성 미디어와 결제 증빙은 이 폴더에만 둔다.

```
S0/ sources.md · call-plan.json(schema v4) · preflight.ps1 · preflight-*.json · calls.jsonl · evaluation.md(v4) · manifest.json · report.md
    samples/ tts-samples.md · tts-samples.json · image-prompts.md · motion-prompts.md · script-topics.md · s1-script-prompt.md(S1 보류, 보존)
S2/ S3/ S4/  실행 결과(블라인드 대응표 blind-key.json 포함) · S1/ 보류
```

`calls.jsonl` 형식: `{"callId","submittedAtUtc","queueMs","processingMs","completedAtUtc","provider","model","requestId","status","usage","billedUsd","priceTableEntry","evidenceRef","outputFiles":[{"path","sha256","bytes","durationMs","width","height","fps"}],"notes"}`. 키, 서명 URL, 결제 개인정보는 기록하지 않는다.

## 2. API 키와 계정 준비 상태

| 공급자 | 환경변수 | 키 상태 | 결제 | 권한 |
|---|---|---|---|---|
| Google AI Studio (Gemini API) | `GEMINI_API_KEY` | 사용자가 발급 완료라고 보고. **Claude 프로세스에서는 미설정(False)** | **선불, 최소 $5**(최대 $5,000). "Set up billing"으로 유료 등급 전환 | AI Studio의 auth key는 기본적으로 Gemini API로 제한된다. 텍스트·이미지·TTS를 키 하나로 쓸 수 있다고 판단한다(명시 문장은 없음) |
| fal | `FAL_KEY` | 사용자가 발급 완료라고 보고. **Claude 프로세스에서는 미설정(False)** | 선불 크레딧. 최소 충전액 NOT_VERIFIED. 잔액이 임계값 아래로 떨어지면 계정이 잠김 | **`API` 프리셋**이면 모든 Model API를 쓸 수 있고 pricing API도 쓸 수 있다. 엔드포인트별 허용 목록 기능은 문서에서 찾지 못했다. 그래서 허용 모델 제한은 실행 스크립트의 허용 목록으로 건다 |

- 키는 사용자 PowerShell 세션 환경변수로만 전달한다. Claude 프로세스는 사용자 세션 변수를 물려받지 않는다. 그래서 확인과 실행은 **사용자 세션에서 `S0/preflight.ps1`을 돌리거나**, 사용자가 같은 세션에서 Claude를 실행하는 방식으로 한다.
- `preflight.ps1`:
  - 기본은 오프라인으로 돌며 키 설정 여부만 출력한다(값은 출력하지 않음).
  - `-Online`을 주면 생성 없이 다음을 조회한다:
    - Gemini `models.get` 2건: inputTokenLimit, outputTokenLimit, 지원 메서드
    - fal 단가 조회 1건: 계정에 적용되는 unit_price, unit, currency
  - 키는 헤더로만 보내고 URL에 넣지 않는다.
  - Claude 환경에서 오프라인 모드는 실행해 봤고(두 키 모두 False), 문법 검사 오류는 0건이다.
- 자동 충전은 쓰지 않는다. Gemini 선불과 fal 충전은 사용자가 직접 하고, 소액만 충전한다.
- 최소 충전액은 Gemini $5(약 9,257원)와 fal 최소액(미확인)이다. 남은 크레딧은 환불되지 않는다.

## 3. 비용 산정 원칙

- **최대 청구액**: 문서화된 하드 한도(모델 출력 한도)나 고정 단가(MP, 초)로만 산정한다.
  - 환율 1,650 KRW/USD × 부가세 1.10 × 카드 수수료 1.02 = **1,851.3 KRW/USD**
  - 원 단위 올림
  - 크레딧·무료량을 차감하기 전 정가
- **실패 비용**:
  - 실패한 호출도 최대액이 청구된다고 가정한다.
  - 문서에는 Gemini 4xx/5xx 미과금, fal 5xx 미과금이라고 되어 있지만 여유분으로만 취급한다.
  - fal 422는 과금될 수 있다.
  - fal에서 진행 중 작업을 취소하면 과금되는지는 UNKNOWN이다.
- **재생성 비용**: 자동 재생성은 금지한다. 재생성은 건별로 승인받고 같은 총 상한에서 차감한다. 남는 여유분은 6절에 표시했다.
- **예상 청구액**: 정상 동작과 추정 길이·토큰을 가정한 값이다. 승인이나 예약에는 쓰지 않는다.
- **가격표 적용 기간**:
  - 항목마다 `effectiveFromUtc`/`effectiveToUtc`, 공급자 기간 문구, 시간대 명시 여부, 출처를 기록한다(`call-plan.json` `priceTable`).
  - Gemini TTS의 2027-01-01 인상은 시간대 표기가 없다. 그래서 UTC+14 기준 **2026-12-31T10:00Z**부터 새 단가를 적용하고, 경계 전후 24시간은 높은 단가로 예약한다.
  - 2026 단가로 받은 승인은 **KST 2026-12-30 19:00 전에 끝나는 호출**에만 유효하다.
  - 항목은 추가만 하고 덮어쓰지 않는다.

## 4. 호출 계획 (S2~S4)

| 호출 ID | 모델 | 고정 파라미터 | 최대 근거 | 최대(2026 / 2027) | 예상 | 상태 |
|---|---|---|---|---:|---:|---|
| S2-GEMTTS-CAPTEST | gemini-3.8-flash-tts · Puck | TTS-B, max_output_tokens 250 | 모델 출력 한도 16,384토큰 | 275 / 549 | 5 | **승인 요청 가능**(출력 제한 적용 검증) |
| S2-GEMTTS-A, B | 같음 | max_output_tokens 1,250, 입력 ≤1,500 | 같음 | 275 / 549 ×2 | 11, 10 | HOLD(6절) |
| S2-GEMTTS-CFULL | 같음 | 장면 4개를 part로 나눠 part별 style, max_output_tokens 2,500 | 같음 | 275 / 549 | 16 | HOLD |
| S2-GEMTTS-C1~C4 | 같음 | 장면별, max_output_tokens 750 | 같음 | 275 / 549 ×4 | 4~5 | HOLD |
| S3-NANO-1~3 | gemini-3.1-flash-lite-image | 9:16, 1024px, minimal, 입력 ≤1,000 | 모델 출력 한도 4,096토큰 × 이미지 단가 $30/1M | 228 ×3 | 64 ×3 | 승인 요청 가능 |
| S3-FLUX-1~3 | fal-ai/flux/schnell | 720×1280(1MP), 1장, 4 steps | 고정 단가 $0.003/MP | 6 ×3 | 6 ×3 | 승인 요청 가능 |
| S4-WANI2V-ROTATE, FALL | wan/v2.6/image-to-video/flash | 5s, **720p**, 무음, prompt expansion off, multi_shots off | 고정 단가 $0.05/s | 463 ×2 | 463 ×2 | 승인 요청 가능 |
| S4-WANT2V-FALL | wan/v2.6/text-to-video | 5s, **720p**, 9:16, prompt expansion off, multi_shots off | 고정 단가 $0.10/s | 926 | 926 | 승인 요청 가능 |
| S1 6회, S4 Runway 2회 | — | — | — | — | — | 보류(사용자 결정) |

- 호출 전 검증: 모델 ID, `resolution: "720p"`(Wan 기본값은 1080p), `duration: "5"`, 화면비(T2V는 파라미터, I2V는 9:16 시작 이미지), fal pricing API의 `unit`·`unit_price`를 확인한다. 하나라도 계획과 다르면 실행 스크립트가 발송을 거부한다.
- 시작 이미지: S3 결과에서 IMG-2와 IMG-3을 사용자가 모델과 관계없이 채택한다. 채택한 파일의 SHA-256을 S4 입력으로 고정한다.
- 소요 시간: 제출, 큐 대기, 처리, 완료 시각을 ms 단위로 기록한다(fal 큐 대기는 과금되지 않음).

## 5. 총 호출 수와 최대 실험비

| 구분 | 호출 | 최대(2026) | 최대(2027) | 예상(2026 / 2027) |
|---|---:|---:|---:|---:|
| S2 CAPTEST | 1 | 275 | 549 | 5 / 10 |
| S3 (Nano 3 + FLUX 3) | 6 | 701 | 701 | 207 / 207 |
| S4 (Wan 3) | 3 | 1,852 | 1,852 | 1,852 / 1,852 |
| **지금 승인 요청 가능한 합계** | **10** | **2,827** | **3,101** | 2,063 / 2,067 |
| S2 본 실험 7회, 보수적 최대 | 7 | 1,921 | 3,842 | 53 / 105 |
| 계획 전체 17회를 모두 보수적으로 | 17 | 4,747 | 6,942 | 2,115 / 2,173 |

- 실험 상한 3,500원에서 S3·S4(2,553원)를 빼면 S2에 쓸 수 있는 금액은 **948원**이다.
- S2 7회의 보수적 최대(1,921원, 2027년 3,842원)는 이 금액을 넘는다. 그래서 S2 본 실험은 **HOLD**다.
- S4의 예상치는 최대치와 같다. 고정 단가이기 때문이다. 무음 할인이 적용되면 실제로는 더 낮을 수 있다.

## 6. S2 보류 근거와 선택안

Gemini TTS에서 `max_output_tokens`가 오디오 출력을 제한한다는 공식 근거가 없다. 그래서 호출당 최대액은 모델 출력 한도 16,384토큰(오디오 약 655초 분량) 기준으로 275원(2027년 549원)이다. 7회의 예상 비용은 약 53원이지만 원칙상 예상치로 승인할 수 없다.

| 선택안 | 내용 | 실험 총계(2026 / 2027) | 재생성 여유 |
|---|---|---:|---:|
| **A. 출력 제한 검증 후 진행(권장)** | ① CAPTEST 1회로 오디오가 250토큰(약 10초)에서 잘리는지, 청구 출력이 ≤250인지 확인한다. ② 사용자가 그 결과를 근거로 인정하면 7회를 상한 기준 최대(144 / 287원)로 다시 승인한다. 한 번의 관측이므로 공식 보장은 아니다 | **2,971 / 3,388** | 529 / 112 |
| B. 총 상한 상향 | 7회를 보수적 최대로 승인 | 4,747 / 6,942 | — |
| C. 공식 근거를 기다림 | 문서 갱신이나 Google 답변을 받은 뒤 진행 | — | — |
| D. 부분 실행 | 948원 안에서 보수적으로 3회(2027년에는 1회) | 3,378 / 3,101 | — |

A는 2026 단가와 2027 단가 모두에서 3,500원 안에 들어간다. 다만 2027 단가에서는 재생성 여유가 112원뿐이다.

## 7. 공급자별 미확인 사항

- **Gemini TTS**
  - 출력 상한이 오디오에 적용되는지 확인되지 않았다(6절).
  - 스타일 필드 이름(`speech_metadata.style`)은 요약본에서만 확인했다.
  - GA 여부가 표기되어 있지 않다.
  - 가격 변경의 시간대가 표기되어 있지 않다.
- **Nano Banana 2 Lite**
  - 최대액 근거는 문서상 출력 한도 4,096토큰이다. 한도를 넘겨 과금하지 않는다는 명시 문구는 없다. 사전 점검에서 outputTokenLimit을 확인한다.
  - 9:16 이미지의 토큰 수가 공개되어 있지 않다.
  - thinking을 끌 수 없고 minimal만 지정할 수 있다.
  - 2027년 가격이 바뀌는지 표기가 없다.
- **fal**
  - pricing API 조회가 과금되는지 문서에 없다.
  - 진행 중 작업 취소 시 과금이 UNKNOWN이고, 422는 과금될 수 있다.
  - Wan 무음 할인율이 문서 안에서 서로 충돌한다.
  - I2V 출력이 시작 이미지의 9:16 비율을 따르는지 확인하지 못했다.
  - 최소 충전액을 확인하지 못했다.
  - 엔드포인트별 키 제한 기능이 없다.
- **공통**
  - 제출 멱등성과 응답을 잃은 뒤의 복구는 UNKNOWN이다.
  - 한국 계정의 부가세 적용과 계정별 실제 단가는 사전 점검과 콘솔에서 확인한다.

## 8. 실험 표본과 평가 기준 (`S0/evaluation.md` v4)

- **S2**: TTS-A(전문용어), TTS-B(숫자·단위·약어), TTS-C 코미디 대본(전체 1회, 장면별 4회).
  - 평가 항목: 대화체 자연스러움, 능청 유머, 감정·억양, 농담·반전 타이밍, 전문용어 발음(오독 0이 합격), 장면 사이 목소리 일관성(LUFS 포함), 실제 음성 길이, 청구 비용
  - 전체 합성과 장면별 합성을 비교한다.
- **S3**: 같은 STYLE 블록을 쓴 세로 프롬프트 3개를 블라인드로 평가한다.
  - 평가 항목: 프롬프트 이해도, 과학적 표현 정확도, 시각적 유머, 그림체 일관성, 세로형 적합성, I2V 시작 이미지 적합성, 비용·소요 시간
- **S4**: ROTATE(행성이 회전하다 멈추고 머그가 관성으로 이탈), FALL(망치와 깃털이 동시에 착지한 뒤 어깨 으쓱).
  - 평가 항목: 피사체 움직임, 물리적 자연스러움, 프롬프트 반영, I2V 일관성, 형태 왜곡·오류, 시각적 유머, 세로형 적합성, 비용·소요 시간
  - 카메라만 움직이거나 확대·축소만 한 결과는 불합격이다.
- **Wan 품질이 부족할 때의 대체 후보**(조사만 함):
  - Kling 3.0 Pro I2V: $0.112/s(오디오 끔), 5s 약 1,037원, 3~15s, 화면비는 시작 이미지를 따름
  - Kling 2.5 Turbo Pro: 5s $0.35(약 648원)
  - Kling v4: 단가 미확인
  - 호출하려면 별도 승인이 필요하다.

## 9. 유료 호출 승인 요청 항목

| 번호 | 항목 | 최대 | 비고 |
|---|---|---:|---|
| R1 | 사용자 세션에서 `preflight.ps1 -Online` 실행(생성 없음) | 0원(예상) | Gemini models.get 2건, fal pricing 1건. fal 쪽 과금 여부는 문서에 없음 |
| R2 | Gemini 선불 충전(최소 $5)과 fal 충전. 사용자가 직접 하고 자동 충전은 끈다 | 충전액 | 실험 소비와는 별개 |
| R3 | S3 6회(Nano 3, FLUX 3) | 701원 | R1에서 Nano outputTokenLimit 4,096과 FLUX 단가를 확인하는 것이 조건 |
| R4 | S4 3회(Wan I2V 2, T2V 1) | 1,852원 | R1에서 Wan 단가와 단위를 확인하는 것이 조건. R3에서 시작 이미지를 채택한 뒤 진행 |
| R5 | S2 CAPTEST 1회 | 275원(2027년 549원) | 선택안 A의 1단계 |
| R6 | S2 본 실험 7회 | A 선택 시 144원(2027년 287원) | 6절의 선택안 A~D 중 사용자가 고른다. CAPTEST 결과를 본 뒤 결정 |

R3+R4+R5의 최대 합계는 2,827원(2027년 3,101원)이다. R6까지 선택안 A로 넣으면 2,971원(2027년 3,388원)이다. 모두 3,500원 안이다.

## 10. Codex 설계 인계 사항

공통 설계·계약·migration은 Codex 담당이다. 아래는 제안이며 반영하려면 사용자 승인이 필요하다.

| ID | 변경 | 제안 |
|---|---|---|
| C01 | TTS를 채널 설정으로 | 채널 프로필 불변 설정에 `ttsProvider`, `ttsModel`, `ttsVoice`, `ttsLanguage`, `ttsSpeakerMode`, `ttsStyleBase`, `ttsFallbackProviders`(비활성)를 둔다. IFNOVA는 gemini-3.8-flash-tts / Puck / ko-KR / single. 하드코딩하지 않는다 |
| C02 | 장면별 음성 연출 | SCENES에 `voiceStyleAdjustment`와 허용 인라인 태그(`<short pause>`, `<long pause>`, `<sigh>`, `<laugh>`, `<gasp>`) 목록을 둔다. 허용 목록 밖의 태그는 걸러낸다 |
| C03 | 자막 채널 토글 | `subtitlesEnabled`(IFNOVA 기본 false). false일 때의 처리: ① SRT·ASS 생성은 `SKIPPED`(사유: CHANNEL_SUBTITLES_DISABLED)로 기록한다. ② 렌더에 자막 필터를 넣지 않는다. ③ QC의 자막 항목은 해당 없음으로 처리한다. ④ manifest에 subtitles=OFF를 남긴다. ⑤ 재시작 복구 때도 SKIP을 유지한다. V2-04 코드와 계약은 보존한다. STT와 forced alignment는 범위에서 뺀다. YouTube 자동 자막·번역·더빙은 V2-06이다. 영향 문서: V2-05_IFNOVA_CONTENT의 자막 관련 문구 |
| C04 | **예약은 문서화된 하드 한도·고정 단가만** | 근거 없는 상한으로는 예약하지 않는다. 운영 영향(보수적 예약 기준): TTS 1회 275원(2027년 549원), Nano 이미지 1장 228원, FLUX 1장 6원, Wan I2V 5s 720p 463원(1080p 695원), Wan T2V 5s 720p 926원. 장면마다 TTS를 따로 부르면 6장면에 1,650원(2027년 3,294원)이 되어 3,000원 상한을 위협한다. **TTS 호출 수 최소화**(대본 전체 1회 합성)를 검토하고, S2 CFULL과 C1~C4 비교 결과로 결정한다. 예시: TTS 1회(549) + FLUX 4장(24) + Nano 2장(456) + Wan I2V 5s 3개(1,389) = 2,418원 |
| C05 | 가격표 적용 기간 | `effectiveFromUtc`/`effectiveToUtc`, `statedPeriod`, `tzStated`, 출처, 확인 시각을 둔다. 시간대가 없으면 UTC+14 경계를 쓰고 경계 전후 24시간은 높은 단가로 예약한다. 항목은 추가만 하고, 영수증에 적용한 항목 ID를 남긴다 |
| C06 | 공급자 범위 | 1차 조합은 Gemini(TTS, 이미지)와 fal(FLUX, Wan)이다. OpenAI·Anthropic·Runway·ElevenLabs·Cloud TTS는 보류한다. S1 대본 공급자는 Codex의 외부 AI Worker 결과에 따라 정한다 |
| C07 | Gemini 선불 | Gemini 유료 등급은 선불(최소 $5)이다. 크레딧 잔액을 비용 경계 확인 대상에 넣는다. `GEMINI_API_KEY` 하나(auth key)를 TTS와 이미지에 공통으로 쓴다 |
| C08 | 비용·규격을 바꾸는 기본값 차단 | Wan의 `resolution`(기본 1080p), T2V의 `aspect_ratio`(기본 16:9)·`multi_shots`(기본 true), `enable_prompt_expansion`(기본 true), I2V의 `generate_audio`(기본 true)는 어댑터에서 반드시 명시하게 한다. 빠지면 발송을 거부한다. 발송 전에 fal pricing API로 단위와 단가를 대조한다 |
| C09 | 문서 갱신 | V2-05_PROVIDER_SPIKE, V2-05_SPIKE_CLAUDE_HANDOFF, TASKS, STATE, DECISIONS를 이 보고서 기준으로 갱신한다(통합 담당 Codex). TASKS 제안: `V2-05-SPIKE | Claude Code | S0 IMPLEMENTED, S2~S4 사용자 승인 대기, S1 HOLD` |
| C10 | 최종 출력 규격 | 1080×1920 CFR 30fps다. Wan 720p 클립은 업스케일과 30fps 변환이 필요하다. S5 무과금 렌더 검증에서 업스케일 품질을 1080p 직접 생성(+50% 비용)과 비교할 근거로 쓴다 |
| C11 | 사전 점검 | 어댑터를 시작할 때 Gemini models.get(출력 한도)과 fal pricing API(단가·단위)로 승인 가격표와 대조한다. 다르면 해당 공급자의 발송을 차단한다 |
| C12 | 대체 동영상 후보 | Wan 품질이 부족하면 fal Kling(v3 Pro, v2.5 Turbo Pro, v4)을 별도 승인 뒤 비교한다. 비용은 8절에 있다 |

## 11. 검증과 비밀정보

- 호출 계획은 BigInt 정수 연산 스크립트로 생성했다(`call-plan.json` schema v4). 2026·2027 단가, 최대·예상, 선택안별 총계를 모두 계산했다. 표본 글자 수와 입력 토큰 추정도 스크립트로 냈다. `preflight.ps1`은 오프라인 실행과 파서 검사를 통과했다. 해시는 `manifest.json`에 있다.
- 비밀정보 검사: 키 패턴과 개인 이메일 grep에서 일치하는 것이 없었다. 이 보고서 자체에 패턴 이름이 적힌 줄은 예외다. 저장소 변경은 이 보고서 하나다. 키, 생성 미디어, 결제 증빙은 없다.

## 12. 다음 작업

1. 사용자가 9절의 R1~R6을 결정한다.
2. 승인 후 사용자 세션에서 `preflight.ps1 -Online`을 실행하고, 결과를 `call-plan.json`과 대조한다. 다르면 보고하고 보류한다.
3. 실행 순서는 `call-plan.json` `executionOrder`를 따른다.
   1. S2 CAPTEST → 사용자 판단 → S2 7회
   2. S3 6회 → 시작 이미지 채택
   3. S4 3회
4. 모두 재시도 없이 순차로 실행한다. 호출마다 원장, manifest, 평가표를 채운다.
5. 다른 PC에서 재개할 때는 작업 폴더를 통째로 복사하고 해시를 대조한다.

# V2-05 S0 공급자 스파이크 무과금 준비 보고서 (최종)

**IMPLEMENTED (S0 문서·로컬 준비)** · 2026-11-27 · 담당 Claude Code · 브랜치 `v2/05-spike` · 기준 `4539848`(설계 SHA `5d6fa55`) · [인수인계](../v2/V2-05_SPIKE_CLAUDE_HANDOFF.md) · [스파이크 계약](../v2/V2-05_PROVIDER_SPIKE.md)

S0에서 보낸 유료 호출, 생성 요청, 테스트 요청은 모두 0건이다. 계정 생성·결제·충전도 하지 않았고, 제품 코드·DB·migration은 건드리지 않았다. 공개 문서만 읽었다. 원문 근거와 상태(VERIFIED / NOT_VERIFIED / UNKNOWN)는 작업 폴더의 `S0/sources.md`에 있다.

**현재 승인되지 않은 것**: 유료 API 호출, 결제, 충전, 제품 코드 구현, DB·migration 변경. 보고를 마치면 사용자 승인을 기다린다.

## 0. 사용자 결정 누적 (2026-11-27)

| 항목 | 결정 |
|---|---|
| 실험비 상한 | 전체 **3,500원** 유지. S1 150원, S3 300원, S4 3,000원. S2는 개별 상한 없이 총 상한의 잔여분 안에서 배정 |
| 운영 영상당 상한 | **3,000원** 유지 |
| T2V 비교 | 유지(S4-WANT2V-FALL) |
| S1 Anthropic 후보 | `claude-haiku-5-5`(Haiku 4.5 대체) |
| S3 Gemini 이미지(Nano Lite) | 최대 청구액을 입증할 수 없어 **HOLD** |
| fal Wan 해상도 | 호출 계획에 `resolution: "720p"` 명시 |
| **TTS 공급자·음성** | **Gemini `gemini-3.8-flash-tts`, 음성 Puck, ko-KR, single speaker.** Chirp 3 HD와 ElevenLabs는 대체 후보로 보류. 오디션 없음. 모델과 음성은 채널 설정으로 관리(하드코딩 금지) |
| **S2 실험** | Puck으로 7회(A 1, B 1, C 전체 1, C 장면별 4). 전체 합성과 장면별 합성의 품질을 비교 |
| **기본 음성 연출 프롬프트** | 사용자 지정 STYLE-BASE에 장면별 조정을 덧붙임(동적) |
| **자막** | **IFNOVA는 자막 없이 제작.** 자체 자막·SRT·FFmpeg 자막 삽입 비활성. STT·forced alignment 검토 제외. S2 평가에서 자막 정렬 제거, 관련 API 비용 제외. V2-04 자막 구현은 남기고 채널별 토글로 관리(IFNOVA 기본 OFF). YouTube 자동 자막·번역·더빙은 V2-06 검토 대상 |
| 비용 계약 | 최대 청구액과 예상 청구액을 구분한다. 입력·출력·세금·환율·수수료·실패·재생성을 포함한다. 2027-01-01 가격 인상을 반영한다. **"초과 시 중단"은 최대 비용 보장으로 표현하지 않는다.** 최대액을 보수적으로 확정할 수 없으면 해당 호출을 보류한다 |

## 1. 작업 폴더 (저장소 밖)

`C:\Users\rlead\AI-Studio-V2-05-Spike\` — OneDrive 밖에 두었다. 생성 미디어와 청구 증빙은 이 폴더에만 두고 Git에 넣지 않는다.

```
S0/ sources.md · call-plan.json(v3) · calls.jsonl(빈 원장) · evaluation.md(빈 평가표) · manifest.json · report.md(이 보고서 사본)
    samples/ script-topics.md · s1-script-prompt.md · tts-samples.md(v3) · tts-samples.json · image-prompts.md · motion-prompts.md
S1/ S2/ S3/ S4/  calls.jsonl(빈 원장). 실행 결과는 각 폴더에 저장
```

`calls.jsonl`의 한 줄 형식은 `{"callId","sentAtUtc","provider","model","requestIdOrJobId","status":"SUCCEEDED|FAILED|TIMEOUT|CANCELLED|UNKNOWN","usage","billedUsd","priceTableEntry","evidenceRef","outputFiles":[{"path","sha256","bytes","durationMs"}],"notes"}`다. 키, 토큰, 서명 URL, 결제 개인정보는 기록하지 않는다.

## 2. 필요한 API 계정과 환경변수 (최종)

| 공급자 | 실험 | 콘솔 | 결제 | 환경변수 |
|---|---|---|---|---|
| Google AI Studio (Gemini API) | S1 텍스트, S2 TTS | https://aistudio.google.com/apikey | **선불, 최소 $5**(최대 $5,000). API keys 또는 Projects 화면의 "Set up billing" | `GEMINI_API_KEY` |
| OpenAI API | S1 | https://platform.openai.com/ | 선불, 최소 $5(기본 $10) | `OPENAI_API_KEY` |
| Anthropic (Claude Console) | S1 | https://platform.claude.com/ | 선불, 최소액 NOT_VERIFIED | `ANTHROPIC_API_KEY` |
| fal | S3, S4 | https://fal.ai/dashboard | 선불, 최소액 NOT_VERIFIED | `FAL_KEY` |
| Runway Developer | S4 | https://dev.runway.com/ | 선불, 최소 $10 | `RUNWAYML_API_SECRET` |

- **필요 없어진 것**: Google Cloud Text-to-Speech(Chirp 3 HD)와 ADC(`GOOGLE_CLOUD_PROJECT`). Chirp은 보류 대상이고 Gemini API 키로는 쓸 수 없는 별도 API다. ElevenLabs 계정도 필요 없다.
- **Gemini 키 하나로 텍스트·이미지·TTS를 모두 쓸 수 있는지**:
  - 모든 Gemini API 키는 Cloud 프로젝트 하나에 묶인다.
  - 2026-05-28 이후 AI Studio에서 만든 키는 auth key이며, 기본적으로 Generative Language API(Gemini API)로 제한된다.
  - 세 모델(텍스트, 이미지, TTS)은 모두 이 API에 속하고 문서에 모델별 키 제한이 없다. 그래서 **공통으로 쓸 수 있다고 판단**한다.
  - 다만 "키 하나로 모든 모델"이라는 명시 문장은 찾지 못했다(NOT_VERIFIED). 첫 호출에서 확인한다.
  - 무제한 standard key는 Gemini API가 거부하므로 AI Studio에서 새로 만든 키를 쓴다.
- **확인된 최소 충전액 합계**: $20(Gemini $5 + OpenAI $5 + Runway $10), 약 37,026원(부가세·수수료 포함 환산). 여기에 Anthropic과 fal의 최소액이 더해진다. 크레딧은 환불되지 않고 대개 1년 안에 만료된다. 실험 소비액(선택안 A 기준 최대 3,286원)과 카드 결제액은 다르다.

### 키 주입 (PowerShell 세션 환경변수만 사용)

```powershell
$s = Read-Host -AsSecureString "GEMINI_API_KEY"
$env:GEMINI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
Remove-Variable s
[bool]$env:GEMINI_API_KEY   # True만 확인. 값은 출력하지 않는다
```

- 키는 비밀번호 관리자에 보관한다. `setx`나 `.env` 파일, 채팅 입력, 로그는 쓰지 않는다.
- 모든 계정에서 자동 충전을 끈다.
- OpenAI 프로젝트 예산과 Anthropic 워크스페이스 한도를 최소로 설정한다.

## 3. 비용 산정 원칙

- **최대 청구액**: 문서화된 하드 한도(최대 토큰, 출력 한도)나 고정 단가(초, MP, 크레딧)로만 산정한다. 근거가 없거나 예산을 넘으면 HOLD한다. "초과 시 중단" 규칙은 안전 장치로만 두고 최대액 근거로 쓰지 않는다.
- **포함 항목**:
  - 입력 토큰과 출력(오디오 포함)
  - 환율 1,650 KRW/USD
  - 부가세 1.10배, 카드 수수료 1.02배 → **1,851.3 KRW/USD**
  - 원 단위 올림
  - 크레딧과 무료량은 차감하기 전 정가
- **실패 비용**:
  - 실패한 호출도 최대액이 청구된다고 가정한다.
  - Gemini 4xx/5xx와 fal 5xx는 과금되지 않는다고 문서에 있다. 이 차이는 여유분으로만 취급한다.
  - fal 422는 과금될 수 있다.
  - Runway는 RUNNING 상태에서 취소하면 환불되지 않고, FAILED 과금 여부는 NOT_VERIFIED다.
- **재생성 비용**:
  - 자동 재생성은 금지한다.
  - 재생성은 건마다 별도 승인을 받고 같은 총 상한(3,500원)에서 차감한다. 남는 여유분은 6절에 표시했다.
- **예상 청구액**: 정상 동작을 가정한 추정치다. 승인·예약에는 쓰지 않는다.

## 4. 가격표 적용 기간 관리

- 가격표는 항목마다 다음을 기록한다(`call-plan.json` `priceTable`):
  - `sku`, `unit`, `price`, `currency`
  - `effectiveFromUtc`, `effectiveToUtc`
  - 공급자가 쓴 기간 문구(`statedPeriod`), 시간대 명시 여부(`tzStated`)
  - 출처 URL, 확인 시각
- Gemini 3.8 Flash TTS의 공개 문구:
  - 입력 $0.50, 출력 $9.00/1M "through December 31, 2026"
  - 입력 $1.00, 출력 $18.00/1M "starting January 1, 2027"
  - 시간대 표기는 없다.
- **보수적 경계**: 가장 이른 시간대(UTC+14)의 2027-01-01 00:00, 곧 **2026-12-31T10:00:00Z**부터 새 단가를 적용한다.
  - 호출 시각이 경계 전후 24시간 안이면 높은 단가로 예약한다.
  - 따라서 2026 단가로 받은 승인은 **2026-12-30T10:00:00Z(KST 2026-12-30 19:00) 전에 발송·완료되는 호출**에만 유효하다.
- 호출 직전에 가격 페이지를 다시 확인한다. 바뀌었으면 새 항목을 추가하고 이전 항목은 닫는다(덮어쓰지 않음). 원장에는 실제로 적용한 가격표 항목을 남긴다.

## 5. 실험별 호출 목록과 최대 청구액

| 호출 ID | 모델·SKU | 고정 파라미터 | 최대 근거 | 최대 원(2026 / 2027) | 상태 |
|---|---|---|---|---:|---|
| S1-GEM-A/B | gemini-3.1-flash-lite | 입력 ≤6k, max_output_tokens 2000(thought 포함), minimal | 문서: 출력 상한에 thought 포함 | 9 / 9 ×2 | 계정 준비 후 |
| S1-OAI-A/B | gpt-5.4-mini-2026-03-17 | 입력 ≤6k, max_output_tokens 2000(reasoning 포함), effort low | 문서: reasoning 포함 | 25 / 25 ×2 | 계정 준비 후 |
| S1-ANT-A/B | claude-haiku-5-5 | 입력 ≤6k, max_tokens 2000, effort low | 문서: "hard cap on total output ... thinking combined" | 3 / 3 ×2 | 계정 준비 후 |
| S2-GEMTTS-A, B, CFULL, C1~C4 (7회) | gemini-3.8-flash-tts · Puck | 입력 ≤1,500, max_output_tokens는 설정하지만 오디오 적용 근거 없음 | 문서상 출력 한도 16,384토큰 | 275 / 549 ×7 | **HOLD**(6절) |
| S2-GEMTTS-CAPTEST | gemini-3.8-flash-tts · Puck | TTS-B, max_output_tokens 250 | 출력 한도 16,384토큰 | 275 / 549 | **선택안 A**(승인 요청 가능) |
| S3-FLUX-1~3 | fal-ai/flux/schnell | 720×1280(1MP로 올림), 1장 | 고정 단가 | 6 ×3 | 계정 준비 후 |
| S3-NANO-1~3 | gemini-3.1-flash-lite-image | — | 근거 없음 | UNBOUNDED | HOLD |
| S4-WANI2V-ROTATE/FALL | wan/v2.6/image-to-video/flash | 5s, **720p**, 무음, prompt expansion off | 고정 단가 $0.05/s | 463 ×2 | 계정 준비 후 |
| S4-RUNWAY-ROTATE/FALL | gen4_turbo | 5s, 720:1280 | 25 credits | 463 ×2 | 계정 준비 후 |
| S4-WANT2V-FALL | wan/v2.6/text-to-video | 5s, **720p**, 9:16, multi_shots off | 고정 단가 $0.10/s | 926 | 계정 준비 후 |

## 6. 총 실험비와 S2 보류 근거

| 구분 | 호출 | 최대(2026 단가) | 최대(2027 단가) | 예상 |
|---|---:|---:|---:|---:|
| S1 | 6 | 73 | 73 | 약 35 |
| S3 (FLUX) | 3 | 17 | 17 | 17 |
| S4 | 5 | 2,777 | 2,777 | 약 2,777(고정 단가) |
| **S1+S3+S4 소계** | 14 | **2,867** | **2,867** | |
| S2 7회 (보수적) | 7 | **1,921** | **3,842** | 약 52 / 103 |
| S2에 배정 가능한 잔여(3,500 − 2,867) | | **633** | **633** | |

**S2 7회는 HOLD다.**
- Gemini TTS에서 `max_output_tokens`가 오디오 출력을 제한한다는 공식 근거가 없다. 그래서 호출당 최대액은 문서상 출력 한도 16,384토큰(오디오 약 655초 분량)으로 잡아야 한다.
- 이렇게 잡은 7회 최대액(1,921원)이 총 상한의 잔여분(633원)을 넘는다.
- 예상 비용은 약 52원이지만, 원칙상 예상치로 승인할 수 없다.

**사용자 선택안**:

| 선택안 | 내용 | 최대(2026) | 총계 | 비고 |
|---|---|---:|---:|---|
| **A. 출력 제한 검증 후 진행** | ① CAPTEST 1회: 약 22초 분량의 TTS-B를 max_output_tokens 250(약 10초)으로 요청한다. 오디오가 잘리고 청구 출력이 ≤250인지 확인한다. ② 결과를 본 사용자가 출력 상한을 인정하면, 7회를 상한 기준 최대액으로 다시 승인한다 | ① 275 + ② 144 = **419** | **3,286** | 3,500원 안. 재생성 여유 214원. 한 번의 관측일 뿐 공식 보장은 아니므로 ②로 갈지는 사용자가 판단한다. 2027 단가면 549 + 287로 총 3,703원이 되어 초과하므로 **경계 시각 전에 끝내야 한다** |
| B. 총 상한 상향 | S2 7회를 보수적 최대로 승인 | 1,921 | 4,788 (2027: 6,709) | 사용자가 정한 "3,500원 유지"와 충돌 |
| C. 공식 근거를 기다림 | Google 문서 갱신이나 지원 답변으로 오디오 출력 상한을 확인한 뒤 진행 | — | — | 일정 지연 |
| D. 부분 실행 | 잔여 633원 안에서 2회만(예: CFULL + C3) | 550 | 3,417 | 장면별 비교가 불완전해짐 |

권장은 A다. 이 결과는 S2만의 문제가 아니라 **운영 원가에도 직결된다**(9절 C04).

**S2를 제외한 승인 요청 가능 합계**: 14회, 최대 2,867원. 선택안 A의 CAPTEST를 넣으면 15회, 3,141원이다. 재생성 여유는 359원이다.

## 7. 공급자별 미확인 사항

- **Gemini TTS**
  - 출력 상한이 오디오에 적용되는지 확인되지 않았다(6절).
  - 스타일 필드 이름(`speech_metadata.style`)은 요약본에서만 확인했다.
  - GA 여부가 표기되어 있지 않다.
  - 가격 변경의 시간대가 표기되어 있지 않다.
  - TTS 전용 실패 과금 문장은 없고 일반 결제표만 있다.
- **Gemini 텍스트**
  - 출력 상한 문서가 Interactions API 표기다. generateContent의 같은 필드에 같은 의미가 적용되는지 호출 직전에 확인한다.
  - thinking을 완전히 끌 수는 없고 minimal이 가장 낮은 단계다.
- **Gemini 이미지**: HOLD(이전과 같음).
- **OpenAI**: Services Agreement의 출력 권리를 다시 확인하지 않았다.
- **Anthropic**: 최소 충전액과 rate limit 등급을 확인하지 못했다. 날짜 스냅샷이 없어 응답의 model 필드를 기록한다.
- **fal**
  - 진행 중 작업 취소 시 과금은 UNKNOWN이다. 422는 과금될 수 있다.
  - I2V 세로 출력이 입력 이미지 비율을 따르는지 확인하지 못했다.
  - 최소 충전액을 확인하지 못했다.
- **Runway**: FAILED 과금 여부와 크레딧 만료를 확인하지 못했다.
- **공통**
  - 제출 멱등성과 응답을 잃은 뒤의 복구는 UNKNOWN이다.
  - 한국 계정의 부가세 적용, 리전, 계정별 실제 단가와 한도는 계정이 준비된 뒤 콘솔에서 확인한다.

## 8. 실험 표본과 평가 기준

- **S1**: TOPIC-A(자전 정지), TOPIC-B(달의 망치·깃털). FACT는 NASA로 고정했다. 세 모델에 같은 프롬프트와 JSON 스키마를 쓴다.
- **S2**: Gemini Puck으로 TTS-A(전문용어), TTS-B(숫자·단위·약어), TTS-C 코미디 대본(전체 1회, 장면별 4회)을 합성한다.
  - 연출은 STYLE-BASE에 장면별 조정을 붙이고, 대본 안에 `<short pause>`, `<sigh>`, `<laugh>` 같은 태그를 넣는다.
  - 평가 8개 항목: 대화체 자연스러움, 능청 유머, 감정·억양, 농담·반전 타이밍, 전문용어 발음, 장면 사이 목소리 일관성, 실제 음성 길이, 실제 청구 비용. **자막 정렬 항목은 제거했다.**
- **S3·S4**: 공통 그림체를 적용한 세로 프롬프트 3개, 회전·낙하 동작. 카메라만 움직인 결과는 운동 점수 1점이다.
- 평가표는 `S0/evaluation.md`에 있다.

## 9. Codex 인계 항목 (기존 설계와 달라진 사항)

공통 설계·계약·migration은 Codex 담당이다. 아래는 사용자 결정과 S0 발견에 따른 변경 제안이다. 반영하려면 사용자 승인이 필요하다.

| ID | 변경 | 영향을 받는 기존 문서 | 제안 |
|---|---|---|---|
| C01 | TTS 공급자·음성을 채널 설정으로 관리 | V2-05_IFNOVA_CONTENT(단일 음성 설정), V2-05_PROVIDER_SPIKE(Chirp 기본안), 06_AI_PROVIDER_AND_PRICING | 채널 프로필 불변 설정에 `ttsProvider`, `ttsModel`, `ttsVoice`, `ttsLanguage`, `ttsSpeakerMode`, `ttsStyleBase`, `ttsFallbackProviders`(비활성)를 둔다. IFNOVA는 gemini-3.8-flash-tts / Puck / ko-KR / single. 프로필 해시와 영수증에 남긴다. 코드에 하드코딩하지 않는다 |
| C02 | 장면별 음성 연출의 동적 조정 | V2-05_IMPLEMENTATION_PLAN(SCENES 필드) | SCENES 출력에 장면별 `voiceStyleAdjustment`와 허용 인라인 태그를 추가한다. 태그는 허용 목록만 통과시키는 검증을 한다. AI 출력을 실행 입력으로 쓰지 않는다는 원칙은 유지한다 |
| C03 | **IFNOVA 자막 OFF, 채널별 토글** | V2-05_IFNOVA_CONTENT(자막 강조, 번인/SRT 출력 프로필, 초기 인수의 자막), V2-04 ASS·SRT 계약 | 채널 설정에 `subtitlesEnabled`(IFNOVA 기본 false)를 둔다. false일 때의 처리: ① 자막 생성·SRT·ASS 단계는 `SKIPPED`(사유: CHANNEL_SUBTITLES_DISABLED)로 기록하고 실패로 보지 않는다. ② 렌더에 자막 필터를 넣지 않는다. ③ QC의 자막 항목은 해당 없음으로 처리한다. ④ manifest에 subtitles=OFF를 남긴다. ⑤ 재시작 복구 때도 SKIP 상태를 그대로 유지한다. V2-04 자막 코드와 계약은 지우지 않는다. 장면 자막 강조 필드는 선택 항목으로 바꾼다. STT·forced alignment는 범위에서 뺀다. YouTube 자동 자막·번역·더빙은 V2-06 |
| C04 | **비용 예약 원칙: 문서화된 하드 한도나 고정 단가만 인정** | V2-05_IMPLEMENTATION_PLAN(비용 예약), 공통 비용 계약 | 근거 없는 상한(예: 오디오에 적용되는지 확인되지 않은 max_output_tokens)으로는 예약하지 않는다. **운영 영향**: Gemini TTS는 호출 1회의 보수적 예약이 275원(2027년 549원)이다. 장면마다 TTS를 따로 부르면 장면 6개 기준 1,650원(2027년 3,294원)이 예약되어 영상당 3,000원 상한을 위협한다. 그래서 TTS 호출 수를 최소화(대본 전체 1회 합성)하는 설계를 검토하거나, 출력 상한의 공식 근거를 확보해야 한다. S2의 CFULL과 C1~C4 비교 결과를 근거로 쓴다 |
| C05 | 가격표 적용 기간 | 공통 가격표·비용 계약 | 가격 항목에 `effectiveFromUtc`/`effectiveToUtc`, `statedPeriod`, `tzStated`, 출처, 확인 시각을 둔다. 시간대가 없으면 UTC+14 경계를 쓰고, 경계 전후 24시간은 높은 단가로 예약한다. 항목은 추가만 하고 덮어쓰지 않는다. 영수증에 적용한 항목 ID를 남긴다 |
| C06 | 공급자·모델 변경 | V2-05_PROVIDER_SPIKE, V2-05_SPIKE_CLAUDE_HANDOFF | S1 Haiku 4.5를 Haiku 5.5로 교체(`output_config.effort`, temperature 등 생략). Nano Lite HOLD. Chirp 3 HD와 ElevenLabs는 대체 후보로 보류. 1차 공급자 조합의 TTS를 Gemini TTS로 변경 |
| C07 | Gemini 유료 등급은 선불(최소 $5) | V2-05_SPIKE_CLAUDE_HANDOFF(계정 표) | 후불 가정을 정정한다. Gemini 크레딧 잔액도 비용 경계 확인 대상에 넣는다. 키 하나(`GEMINI_API_KEY`, auth key)를 텍스트·TTS에 공통으로 쓰고, Cloud TTS는 별도 API다 |
| C08 | 필수 파라미터 검증 | V2-05 어댑터 설계 | fal Wan `resolution`(기본 1080p), T2V `aspect_ratio`·`multi_shots`처럼 기본값이 비용이나 규격을 바꾸는 필드는 어댑터에서 반드시 명시하게 하고, 빠지면 발송을 거부한다 |
| C09 | 스파이크 호출 목록·금액 갱신 | V2-05_PROVIDER_SPIKE, V2-05_SPIKE_CLAUDE_HANDOFF, TASKS.md, STATE.md, DECISIONS.md | 이 보고서 5~6절 기준으로 갱신한다(통합 담당 Codex). TASKS에 `V2-05-SPIKE | Claude Code | S0 IMPLEMENTED, S1~S4 사용자 승인 대기`를 추가한다 |

## 10. S1~S4 착수 가능 여부

**지금은 착수할 수 없다.** 사용자의 계정 준비와 최종 호출 승인이 모두 필요하다.

| 실험 | 계획 상태 | 착수 조건 |
|---|---|---|
| S1 | 준비 완료(6회, 최대 73원) | Gemini·OpenAI·Anthropic 계정, 선불 충전, 키. 호출 승인 |
| S2 | **HOLD**(7회). 선택안 A의 CAPTEST만 승인 요청 가능(최대 275원) | 6절의 A~D 중 선택. A를 택하면 경계 시각 전에 실행 |
| S3 | FLUX 3회 준비 완료(최대 17원), Nano HOLD | fal 충전. 호출 승인 |
| S4 | 준비 완료(5회, 최대 2,777원) | S3 채택 이미지, fal·Runway 충전. 호출 승인 |

## 11. 검증과 비밀정보

- 비용표는 BigInt 정수 연산 스크립트로 다시 만들었다(`call-plan.json` schema v3). 원화는 올림이며, 2026과 2027 단가를 모두 계산했다. 표본 글자 수와 입력 토큰 추정은 스크립트로 냈다. 작업 폴더 해시는 `manifest.json`에 있다.
- 비밀정보 검사: 키 패턴(sk-, sk-ant-, AIza, ya29.)과 개인 이메일을 grep했고 일치하는 것이 없었다. 저장소 변경은 이 보고서 하나다. 키, 생성 미디어, 결제 증빙은 없다.

## 12. 다음 작업 (다른 PC에서 재개할 때)

1. 사용자가 6절의 S2 선택안과 실험별 호출 승인을 결정한다.
2. 사용자가 계정·선불 충전·키를 준비하고 키를 세션에만 주입한다. 자동 충전은 끈다.
3. 콘솔에서 계정별 실제 단가, 한도, 부가세를 확인한다. 호출 직전에는 가격 페이지를 다시 확인하고 `priceTable`을 갱신한다.
4. 스파이크 전용 실행 스크립트를 작업 폴더에서 돌린다. 제품 코드는 쓰지 않는다. 재시도 0, 순차 실행, `call-plan.json`의 `executionOrder`를 따른다.
5. 호출마다 `calls.jsonl`, `manifest.json`, `evaluation.md`를 채운다.
6. 다른 PC에서는 `C:\Users\rlead\AI-Studio-V2-05-Spike\`를 통째로 복사하고 해시를 대조한다.

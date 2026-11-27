# V2-05 S0 공급자 스파이크 무과금 준비 보고서

**IMPLEMENTED (S0 문서·로컬 준비, 사용자 수용)** · 2026-11-27 · 담당 Claude Code · 브랜치 `v2/05-spike` · 기준 `4539848`(설계 SHA `5d6fa55`) · [인수인계](../v2/V2-05_SPIKE_CLAUDE_HANDOFF.md) · [스파이크 계약](../v2/V2-05_PROVIDER_SPIKE.md)

S0에서 보낸 유료 호출, 생성 요청, 테스트 요청은 모두 0건이다. 계정 생성·결제·충전도 하지 않았고, 제품 코드·migration·DB는 건드리지 않았다. 공개 문서만 읽었다. 원문 근거와 상태(VERIFIED / NOT_VERIFIED / UNKNOWN)는 작업 폴더의 `S0/sources.md`에 있다.

## 0. 사용자 결정 반영 (2026-11-27)

사용자가 S0 결과를 수용하고 아래 변경을 지시했다. **유료 호출과 충전은 여전히 승인되지 않았다.** 계정과 결제는 사용자가 직접 준비하며, 사용자의 최종 호출 승인 뒤에 S1~S4를 시작한다.

| 항목 | 이전 | 반영 |
|---|---|---|
| 실험비 상한 S1 / S2 / S3 / S4 | 150 / 40 / 250 / 2,700원 | **150 / 50 / 300 / 3,000원** |
| 전체 실험비 상한 제안 | 3,140원 | **3,500원** |
| 일반 운영 영상당 상한 | 3,000원 | 3,000원(변경 없음) |
| T2V 비교(S4-WANT2V-FALL) | 유지 여부 미정 | **유지** |
| S1 Anthropic 비교 후보 | claude-haiku-4-5-20251001 | **claude-haiku-5-5** |
| Chirp 3 HD 단가 | NOT_VERIFIED | 공개 $30/1M 문자를 사용자가 확인 |
| S3 Gemini 이미지(Nano Lite) | 조건부 | 최대 청구액을 입증할 수 없어 **HOLD**(3회) |
| fal Wan 해상도 | 720p 명시 | 모든 Wan 호출에 `resolution: "720p"`를 명시하도록 실행 규칙에 고정 |

## 1. 작업 폴더 (저장소 밖)

`C:\Users\rlead\AI-Studio-V2-05-Spike\` — OneDrive 동기화 폴더 밖에 두었다. 생성 미디어와 청구 증빙은 이 폴더에만 저장하고 Git에는 넣지 않는다.

```
S0/ sources.md · call-plan.json · calls.jsonl(빈 원장) · evaluation.md(빈 평가표) · manifest.json · report.md(이 보고서 사본)
    samples/ script-topics.md · s1-script-prompt.md · tts-samples.md · tts-samples.json · image-prompts.md · motion-prompts.md
S1/ S2/ S3/ S4/  calls.jsonl(빈 원장). 실행 결과는 각 폴더에 저장
```

`calls.jsonl`의 한 줄 형식은 `{"callId","sentAtUtc","provider","model","requestIdOrJobId","status":"SUCCEEDED|FAILED|TIMEOUT|CANCELLED|UNKNOWN","billedUnits","billedUsd","evidenceRef","outputFiles":[{"path","sha256","bytes"}],"notes"}`다. 키, 토큰, 서명 URL, 결제 개인정보는 기록하지 않는다.

## 2. 필요한 계정과 가입 링크 (사용자 준비)

| 공급자 | 실험 | 가입·콘솔 | 결제 방식 |
|---|---|---|---|
| Google AI Studio (Gemini API) | S1 | https://aistudio.google.com/apikey | Cloud 결제 계정을 연결한 유료 등급(후불). 무료 등급의 텍스트는 학습에 쓰이므로 사용하지 않는다 |
| Google Cloud (Text-to-Speech) | S2 | https://console.cloud.google.com/ → API 라이브러리에서 Cloud Text-to-Speech API 사용 설정 | 위와 같은 Cloud 결제 계정(후불) |
| OpenAI API | S1 | https://platform.openai.com/ | 선불 크레딧 |
| Anthropic (Claude Console) | S1 | https://platform.claude.com/ | 선불 크레딧 |
| fal | S3, S4 | https://fal.ai/dashboard | 선불 크레딧 |
| Runway Developer | S4 | https://dev.runway.com/ | 선불 크레딧(프로젝트 단위) |

Gemini와 Cloud TTS는 같은 스파이크 전용 Google Cloud 프로젝트 하나로 묶을 것을 권장한다. Nano Lite가 보류되었으므로 S3에서 Gemini는 쓰지 않는다. ElevenLabs는 기본 호출 목록에 없으므로 가입하지 않아도 된다.

## 3. API 키 발급 절차와 환경변수 이름

| 환경변수 | 발급 위치·절차 |
|---|---|
| `GEMINI_API_KEY` | AI Studio → Get API key → 스파이크 전용 Cloud 프로젝트를 선택해 키 생성 → 프로젝트 결제 상태가 Paid인지 확인 |
| `GOOGLE_CLOUD_PROJECT` (+ ADC) | 키 대신 ADC를 쓴다. gcloud CLI 설치 후 `gcloud auth application-default login`, `gcloud auth application-default set-quota-project <프로젝트ID>`. 자격증명 파일은 `%APPDATA%\gcloud\`에 저장되며 저장소 밖이다 |
| `OPENAI_API_KEY` | Platform → 스파이크 전용 Project 생성 → API keys → 프로젝트 범위 키 생성 |
| `ANTHROPIC_API_KEY` | Console → 스파이크 전용 Workspace 생성 → API Keys → 워크스페이스 범위 키 생성 |
| `FAL_KEY` | fal 대시보드 → Keys → 키 생성(`key_id:key_secret` 형식 한 줄) |
| `RUNWAYML_API_SECRET` | dev.runway.com → 프로젝트 생성 → API Keys 탭 → 키 생성. 키는 생성할 때 한 번만 보인다 |

키는 비밀번호 관리자에 보관하고, 실행할 PowerShell 세션에만 넣는다. `setx`나 `.env` 파일, 채팅 입력은 쓰지 않는다.

```powershell
# 화면·기록에 남지 않게 세션 변수로만 주입한다 (변수 이름만 바꿔 반복)
$s = Read-Host -AsSecureString "GEMINI_API_KEY"
$env:GEMINI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
Remove-Variable s
[bool]$env:GEMINI_API_KEY   # True만 확인. 값은 출력하지 않는다
```

계정마다 권장하는 지출 차단 설정:
- 자동 충전(auto-recharge, auto-reload)을 모두 끈다.
- OpenAI는 프로젝트 예산, Anthropic은 워크스페이스 지출 한도를 최소로 설정한다.
- Google Cloud는 예산 알림을 설정한다. 알림은 지출을 막지 못하므로 호출 수 제한은 실행기에서 건다.

## 4. 결제와 최소 충전 금액

| 공급자 | 최소 결제 | 상태 | 비고 |
|---|---|---|---|
| OpenAI | $5 (기본 $10) | VERIFIED | 1년 만료, 환불 불가 |
| Runway | $10 | VERIFIED | 1 credit = $0.01 |
| Anthropic | 미확인 | NOT_VERIFIED | Console Billing에서 확인. 1년 만료, 환불 불가 |
| fal | 미확인 | NOT_VERIFIED | 대시보드 Billing에서 확인. 365일 만료. 잔액이 임계값 미만이면 계정이 잠김 |
| Google (Gemini + TTS) | 선불 없음(후불 카드 등록) | NOT_VERIFIED | 신규 계정 무료 체험 크레딧 금액은 확인하지 못함 |

**실험 소비액과 카드 결제액은 다르다.** 실험이 실제로 쓰는 금액은 최대 2,900원이다. 하지만 선불 공급자 네 곳의 최소 충전액은 확인된 것만 $15(OpenAI $5 + Runway $10, 약 27,770원)이고, Anthropic과 fal의 최소액이 더해진다. 남은 크레딧은 환불되지 않고 1년 안에 만료된다.

## 5. 실험별 호출 목록과 최대 청구액

환산 가정: 1,650 KRW/USD × 부가세 1.10 × 해외 카드 수수료 1.02 = **1,851.3 KRW/USD**(전부 포함, 보수적). 크레딧과 무료량을 차감하기 전의 정가로 계산했고, 원 단위 미만은 올렸다. 전체 파라미터는 `S0/call-plan.json`에 있다.

| 호출 ID | 모델·SKU | 고정 파라미터 | 최대 USD | 최대 원 | 상태 |
|---|---|---|---:|---:|---|
| S1-GEM-A/B | gemini-3.1-flash-lite | 입력 ≤6k, maxOutputTokens 2000, thinking minimal, 도구 없음 | 0.0045 ×2 | 9 ×2 | 계정 준비 후 |
| S1-OAI-A/B | gpt-5.4-mini-2026-03-17 | 입력 ≤6k, max_output_tokens 2000(추론 포함), effort low | 0.0135 ×2 | 25 ×2 | 계정 준비 후 |
| S1-ANT-A/B | **claude-haiku-5-5** | 입력 ≤6k, max_tokens 2000, effort low, temperature·top_p·top_k 생략 | 0.0016 ×2 | 3 ×2 | 계정 준비 후 |
| S2-CHIRP-A/B | ko-KR-Chirp3-HD-Algieba | ≤300 청구 문자(표본은 159자, 141자), text 입력 | 0.009 ×2 | 17 ×2 | 계정 준비 후 |
| S3-FLUX-1~3 | fal-ai/flux/schnell | 720×1280(1MP로 올림), 1장, 4 steps | 0.003 ×3 | 6 ×3 | 계정 준비 후 |
| S3-NANO-1~3 | gemini-3.1-flash-lite-image | 9:16, 1024px | UNBOUNDED | — | **HOLD** |
| S4-WANI2V-ROTATE/FALL | wan/v2.6/image-to-video/flash | 5s, **resolution 720p**, generate_audio false, prompt expansion off | 0.25 ×2 | 463 ×2 | 계정 준비 후 |
| S4-RUNWAY-ROTATE/FALL | gen4_turbo | 5s, ratio 720:1280 (25 credits) | 0.25 ×2 | 463 ×2 | 계정 준비 후 |
| S4-WANT2V-FALL | wan/v2.6/text-to-video | 5s, **resolution 720p**, aspect_ratio 9:16, multi_shots false | 0.50 | 926 | 계정 준비 후 |

## 6. 총 실험비 상한

| 실험 | 승인 요청 호출 | 보류 | 최대 USD | 환율만 적용 | 전부 포함 | 상한 | 판정 |
|---|---:|---:|---:|---:|---:|---:|---|
| S1 | 6 | 0 | 0.0392 | 65 | 73 | 150 | 이내 |
| S2 | 2 | 0 | 0.018 | 30 | 34 | 50 | 이내 |
| S3 | 3 | 3 | 0.009 | 15 | 17 | 300 | 이내 |
| S4 | 5 | 0 | 1.50 | 2,475 | 2,777 | 3,000 | 이내 |
| 합계 | **16** | 3 | 1.5662 | 2,585 | **2,900** | **3,500** | 이내 |

- 원래 계획한 19회 가운데 16회만 승인 요청 대상이다. 보류한 Nano 3회는 승인 요청과 합계에서 뺐다.
- 실험 상한은 일반 운영 영상당 상한(3,000원)과 별개다. S4 단독 상한 3,000원은 영상 한 편 제작비가 아니라 5초 클립 5개를 비교하는 비용이다.
- 보류한 Nano를 나중에 다시 넣을 경우의 참고값: 모델 출력 한도 4,096토큰 전부를 이미지 단가로 계산하면 $0.12313/회(228원)이고 3회면 684원이다. 이 경우 S3 상한 300원을 넘는다. 출력 한도가 실제로 적용된다는 근거도 없다.

## 7. 공급자별 미확인 사항과 실행 전 재확인 항목

실행 전 공통 재확인(계정이 준비된 뒤 사용자 콘솔에서):
- 계정에 실제 적용되는 단가와 할인·무료량
- 모델 접근 권한과 리전 가용성
- 계정 등급별 rate limit과 토큰 한도
- 부가세 적용 여부
- 자동 충전이 꺼져 있는지

공개 문서만으로는 이 항목들을 확인할 수 없다.

- **Gemini (S1)**
  - maxOutputTokens에 thinking이 포함되는지 확인하지 못했다.
  - 다른 모델 일부는 2026-12-01에 가격이 오른다. 호출 직전에 다시 확인한다.
- **Gemini 이미지 (S3, HOLD)**
  - thinking을 끌 수 없고 예산 파라미터가 없다.
  - 9:16 토큰 수가 공개되어 있지 않다.
  - 요청당 이미지 수를 제어하는 방법을 확인하지 못했다.
  - 출력 한도 4,096토큰이 이미지와 thinking에 적용되는지 근거가 없다.
  - 다시 시도하려면 공식 문서나 공급자 답변으로 요청당 상한을 입증해야 한다.
- **Google TTS**
  - 공개 단가는 사용자가 확인했다. 계정 적용 단가와 무료량은 확인하지 못했다.
  - SSML 지원 여부가 문서끼리 충돌하므로 text 입력만 쓴다.
  - timepoint 지원은 UNKNOWN이다.
  - 정확한 음성 이름은 실행 전에 무료 voices.list로 확인한다.
- **OpenAI**
  - reasoning 토큰 과금 문구를 원문으로 확인하지 못했다. 상한은 output 단가로 계산했다.
  - Services Agreement의 출력 권리를 다시 확인하지 않았다.
- **Anthropic Haiku 5.5**
  - 날짜 스냅샷이 없어 alias만 쓴다. 재현성을 위해 응답의 model 필드를 기록한다.
  - adaptive thinking이 기본으로 켜져 있다.
  - 모델 페이지에는 max_tokens에 thinking이 포함되는지 적혀 있지 않다. 그래서 **S1에서는 S1-ANT-A를 가장 먼저 실행**하고 usage를 확인한다. 청구 출력이 2,000토큰을 넘으면 초과 청구로 보고 S1의 나머지 호출을 모두 중단한다. 이때 노출액은 출력 128K 기준 최대 약 $0.0646(120원)이며 S1 상한 150원 안에 들어간다. 이 호출을 나중에 실행하면 최악의 경우 9+25+120 = 154원으로 상한을 넘으므로 순서를 바꾸지 않는다.
  - rate limit 등급과 최소 충전액을 확인하지 못했다.
- **fal**
  - 진행 중 작업을 취소할 때의 과금은 UNKNOWN이다. 422는 과금될 수 있다.
  - Wan I2V 무음 할인율은 문서 안에서 서로 충돌한다. 그래서 표시 단가를 상한으로 썼다.
  - I2V 세로 출력이 입력 이미지 비율을 따르는지 확인하지 못했다.
  - Wan의 resolution 기본값은 1080p다. 모든 Wan 호출에 `720p`를 명시한다. 실행기는 이 필드가 없으면 발송을 거부한다.
  - 최소 충전액을 확인하지 못했다.
- **Runway**
  - RUNNING 상태에서 취소하면 환불되지 않는다.
  - FAILED 작업의 과금 여부와 크레딧 만료는 NOT_VERIFIED다. `cost.credits`로 사후 확인한다.
  - 출력 URL은 24~48시간 뒤 만료되므로 즉시 내려받는다.
- **공통**
  - 모든 SKU에서 제출 멱등성과 응답을 잃은 뒤의 복구는 UNKNOWN이다. 그래서 자동 재제출을 금지한다.

## 8. 실험 표본과 평가 기준

- **S1**: TOPIC-A "지구가 1초 만에 자전을 멈춘다면"과 TOPIC-B "달에서 망치와 깃털을 떨어뜨리면"이다. FACT는 NASA NSSDCA와 NASA Science 페이지로 고정했다. 적도 속도 약 1,674km/h(465m/s)는 NASA의 반지름과 자전 주기로 계산한 값으로 표시했다. NASA 페이지에서 확인되지 않은 1971년은 대본에 쓰지 않는다. 세 모델(Gemini Flash-Lite, GPT-5.4 mini, Haiku 5.5)은 같은 프롬프트와 JSON 스키마를 쓴다.
- **S2**: 159자와 141자의 표본 두 개다. 숫자, 소수, 단위(km, m/s²), 약어(NASA), 고유명사(아폴로 15호)를 넣었고, 핵심 용어 9개의 기대 발음표를 붙였다. 문장 cue 6개는 silencedetect로 잰 값과 사람이 표시한 값을 비교하며, 합격 기준은 편차 ≤250ms다.
- **S3**: 공통 STYLE 블록을 적용한 세로 프롬프트 3개다. 지금은 FLUX만 실행하므로 공급자 간 비교 없이 FLUX 품질만 판정한다(3개 중 2개 이상 합격). IMG-2와 IMG-3의 FLUX 채택본이 S4 시작 이미지가 된다.
- **S4**: ROTATE(정지 후 머그가 관성으로 이탈)와 FALL(동시 착지 후 어깨 으쓱)이다. I2V 2종에는 같은 시작 이미지 해시를 쓰고, FALL은 T2V 1회를 추가한다. 카메라는 고정하고 negative prompt를 붙였다.
- **평가표**: `S0/evaluation.md`. 각 항목 1~5점이며 합격선은 4/5다. 카메라만 움직인 결과는 운동 점수 1점이다.

## 9. S1~S4 착수 가능 여부

**지금은 착수할 수 없다.** 사용자의 계정 준비와 최종 호출 승인이 모두 필요하다.

| 실험 | 계획 상태 | 착수 조건 |
|---|---|---|
| S1 | 준비 완료 | Google·OpenAI·Anthropic 계정, 결제, 키. 호출 승인(최대 73원, 상한 150원) |
| S2 | 준비 완료 | Cloud TTS API 사용 설정과 ADC, 계정 단가 확인. 호출 승인(최대 34원, 상한 50원) |
| S3 | FLUX 3회 준비 완료, Nano 3회 HOLD | fal 충전. 호출 승인(최대 17원, 상한 300원) |
| S4 | 준비 완료 | S3 채택 이미지, fal·Runway 충전. 호출 승인(최대 2,777원, 상한 3,000원) |

## 10. 검증과 비밀정보

- 비용표는 BigInt 정수 연산 스크립트로 다시 만들었고 원화는 올림 처리했다. 결과는 `call-plan.json`에 있다. 표본 글자 수도 스크립트로 셌다. 작업 폴더 파일의 SHA-256은 `manifest.json`에 있다.
- 비밀정보 검사: 키 패턴(sk-, AIza 등)과 개인 이메일을 grep했고 일치하는 것이 없었다. 저장소에서 바뀐 것은 이 보고서 파일 하나다. 생성 미디어, 키, 결제 증빙은 없다.
- 공통 파일(STATE.md, TASKS.md, DECISIONS.md)은 통합 담당자(Codex)가 반영한다. 제안 내용은 다음과 같다.
  - TASKS.md에 `V2-05-SPIKE | Claude Code | S0 IMPLEMENTED(사용자 수용), S1~S4 계정 준비·호출 승인 대기`를 추가한다.
  - DECISIONS.md에 S1 Anthropic 후보를 Haiku 5.5로 바꾼 것, 실험 상한 3,500원(운영 3,000원 유지), Nano Lite HOLD를 기록한다.
  - [스파이크 계약](../v2/V2-05_PROVIDER_SPIKE.md)과 [인수인계](../v2/V2-05_SPIKE_CLAUDE_HANDOFF.md)의 호출 목록과 금액을 이 보고서 기준으로 갱신한다.

## 11. 다음 작업 (다른 PC에서 재개할 때)

1. 사용자가 계정과 결제를 준비하고 키를 세션에만 주입한다.
2. 7절의 공통 재확인 항목을 콘솔에서 점검하고, 호출 직전에 가격 페이지를 다시 확인한다.
3. 사용자에게 최종 호출 승인을 받고 `call-plan.json`의 approvalStatus를 갱신한다.
4. 실행기는 스파이크 전용 스크립트로 작업 폴더에서 돌린다. 제품 코드는 쓰지 않는다. 재시도 0, 순차 실행으로 S1 → S2 → S3 → S4 순서로 진행한다. S1 안에서는 S1-ANT-A를 첫 호출로 둔다(7절).
5. 각 호출 뒤 `calls.jsonl`, `manifest.json`, `evaluation.md`를 채운다.
6. 표본 원문은 작업 폴더에만 있다. 다른 PC에서는 `C:\Users\rlead\AI-Studio-V2-05-Spike\`를 통째로 복사하고 `S0/manifest.json`의 해시와 대조한다.

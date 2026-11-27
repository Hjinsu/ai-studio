# V2-05 공급자 검증·원가·스파이크

**PROPOSED** · 2026-11-27 KST · [기존 조사](06_AI_PROVIDER_AND_PRICING.md) · [상세 계획 D02/D05/D13](V2-05_IMPLEMENTATION_PLAN.md)

공식 공개 문서에서 확인한 API와 요금이다. USD 표준 요금 기준이며 한국 계정/리전, 약관 스냅샷, 실제 청구, 유료 테스트는 **NOT_VERIFIED**. 문서에 보장 근거를 찾지 못한 동작은 **UNKNOWN**으로 기록한다. 동기 SKU는 아래 사업 단계에서 동기 응답만 채택한다. 분실된 응답을 같은 요청으로 검색하거나 취소할 수 있다는 보장은 UNKNOWN이다. 전 SKU의 제출 멱등성 및 응답 분실 후 고객 키 조회는 UNKNOWN이며 공통 계약상 자동 재제출 금지다.

| 역할·공급 경로 및 모델 ID | 공개 요금 / 요청 한계 | 한국어·권리 및 복구 특이사항 |
|---|---|---|
| Gemini API `gemini-3.1-flash-lite` | 입력 $0.25, 출력 $1.50/100만 토큰, 추론 포함. 검색 별도. 최소 금액 반올림 UNKNOWN | 한국어 텍스트 품질 NOT_VERIFIED. 생성 콘텐츠와 검색 grounding의 저장·표시 조건은 별개. [가격](https://ai.google.dev/gemini-api/docs/pricing) · [약관](https://ai.google.dev/gemini-api/terms) |
| OpenAI API `gpt-5.4-mini` | 입력 $0.75, 출력 $4.50/100만 토큰. 검색 별도. 최소 금액 UNKNOWN | 한국어 대본 품질 NOT_VERIFIED. 이 설계는 동기 응답 기준. 출력 권리는 서비스 계약 및 제3자 권리 확인. [모델](https://developers.openai.com/api/docs/models/gpt-5.4-mini) · [계약](https://openai.com/policies/services-agreement/) |
| Claude API `claude-haiku-4-5-20251001` | 입력 $1, 출력 $5/100만 토큰. 기존 모델로 비교용. 최소 금액 UNKNOWN | 한국어 품질 NOT_VERIFIED. 동기 Messages 기준. 적용 Commercial Terms 및 입력 권리 확인. [모델](https://platform.claude.com/docs/en/models/haiku-4-5/overview) · [약관](https://www.anthropic.com/legal/commercial-terms) |
| Gemini API Nano Banana `gemini-3.1-flash-lite-image` | 1K 예시 $0.0336/장 + 입력 토큰 및 생성 텍스트/추론. 9:16 선택 후 실제 크기·토큰 NOT_VERIFIED | 한국어 프롬프트 품질 NOT_VERIFIED. 출력·사용 약관 및 입력 권리 확인. [모델](https://aistudio.google.com/docs/models/gemini-3.1-flash-lite-image) · [가격](https://ai.google.dev/gemini-api/docs/pricing) |
| fal API `fal-ai/flux/schnell` | $0.003/MP, 출력 픽셀을 MP 단위 올림. 720×1280은 1MP, 1080×1920은 3MP | 영문 프롬프트 사용, 한국어 NOT_VERIFIED. 상업 사용 표기 및 fal queue. [모델·가격·권리](https://fal.ai/models/fal-ai/flux/schnell) |
| Runway API `gen4_turbo` | I2V, 2~10초 정수, 720×1280 가능, 5 credits/초 × $0.01. 최소 $0.10/클립 | 영문 프롬프트, 한국어 NOT_VERIFIED. task ID로 GET, 삭제/취소 API. 실패·취소 과금 및 상업 약관의 해당 계정 적용 NOT_VERIFIED. [API](https://docs.dev.runwayml.com/api/) · [입력](https://docs.dev.runwayml.com/assets/inputs/) · [요금](https://docs.dev.runwayml.com/guides/pricing/) |
| fal API Pika 2.2 `fal-ai/pika/v2.2/text-to-video`, `fal-ai/pika/v2.2/image-to-video` | T2V 5/10초, 720/1080p, 9:16, 5초 $0.20/$0.45. I2V 세부 요금·세로 출력 NOT_VERIFIED | 영문 사용, 한국어 NOT_VERIFIED. fal queue, 상업 사용 표기 및 조건. [T2V](https://fal.ai/models/fal-ai/pika/v2.2/text-to-video) · [I2V](https://fal.ai/models/fal-ai/pika/v2.2/image-to-video) |
| fal API Wan `wan/v2.6/image-to-video/flash`, `wan/v2.6/text-to-video` | 5/10/15초, 720/1080p. I2V $0.05/$0.075/초, T2V $0.10/$0.15/초. 최저 720p $0.25/$0.50. T2V 9:16 지원, I2V는 세로 시작 이미지의 실출력 검증 | 영문·중문 프롬프트, 한국어 NOT_VERIFIED. fal queue 및 상업 사용 표기. [I2V Flash](https://fal.ai/models/wan/v2.6/image-to-video/flash) · [T2V](https://fal.ai/models/wan/v2.6/text-to-video/api) · [권리](https://fal.ai/wan-2.6) |
| Gemini API Veo `veo-3.1-lite-generate-preview` | I2V/T2V 후보, 720p $0.05/초·1080p $0.08/초, 4/6/8초 제한과 9:16 조합은 SKU별 확인. 예상 720p 최저 $0.20 | 영문 사용, 한국어 NOT_VERIFIED. long-running operation 조회. 성공 생성만 과금 안내. 취소·분실 복구 UNKNOWN, preview 약관 및 출력 권리 검토. [영상 API](https://ai.google.dev/gemini-api/docs/video) · [가격](https://ai.google.dev/gemini-api/docs/pricing) |
| fal API Kling `fal-ai/kling-video/v2.5-turbo/pro/image-to-video`, `fal-ai/kling-video/v2.5-turbo/pro/text-to-video` | 5/10초, I2V $0.35/5초+$0.07/추가초. T2V 요금 및 출력 해상도 계약 NOT_VERIFIED | 영문 사용, 한국어 NOT_VERIFIED. fal queue, 해당 SKU의 상업 사용 및 계정 조건 확인. [I2V](https://fal.ai/models/fal-ai/kling-video/v2.5-turbo/pro/image-to-video) · [T2V](https://fal.ai/models/fal-ai/kling-video/v2.5-turbo/pro/text-to-video) |
| Google Cloud TTS Chirp 3 HD, 시험 음성 `ko-KR-Chirp3-HD-Algieba` | $0.00003/문자, 표준 합성 동기. LINEAR16 등. 상한 입력 및 인코딩은 어댑터 활성화 때 고정 | ko-KR 공식 지원. 미디어 활용은 Cloud 약관 준수. 반환된 타임스탬프 사용 가능 여부 NOT_VERIFIED. [음성](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd) · [가격](https://cloud.google.com/text-to-speech/pricing) · [사용](https://docs.cloud.google.com/text-to-speech/docs/basics) |
| ElevenLabs TTS `eleven_flash_v2_5` | 40,000 문자 상한, API 0.5~1 credits/문자(요금제별). 음성 ID 및 실제 원화 단가 NOT_VERIFIED | 한국어 지원 및 동기 문자 타임스탬프 API. 상업 사용 가능 유료 플랜·보이스 권리 확인. [모델](https://elevenlabs.io/docs/overview/capabilities/text-to-speech) · [타임스탬프](https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps/) · [요금](https://elevenlabs.io/pricing) |

fal 공통 계약은 request ID로 상태·결과 조회 및 취소다. 문서상 성공 출력 과금, 서버 오류와 큐 대기 시간 무료이며 일부 422는 처리 시간으로 청구될 수 있다. 진행 작업 취소 신호가 최종 무과금을 보장하는지 UNKNOWN. ID 분실이나 URL 만료 후 재발급, 요청 단위 영수증 귀속, 계정별 리전/최대 청구는 스파이크 확인 대상이다. 동기 LLM·이미지·TTS의 실패/취소 과금도 명시적으로 입증될 때까지 UNKNOWN으로 둔다. [큐 제출](https://fal.ai/docs/api-reference/inference-api/queue/submit-path) · [조회](https://fal.ai/docs/api-reference/inference-api/queue/result) · [취소](https://fal.ai/docs/api-reference/inference-api/queue/cancel) · [과금](https://fal.ai/docs/documentation/model-apis/pricing) · [FAQ](https://fal.ai/docs/documentation/model-apis/faq)

## 최소 조합과 비용

1차 제안은 **Gemini Flash-Lite + Nano Banana Lite + Chirp 3 HD + fal Wan 2.6 I2V Flash**다. 공급사 계열은 Google과 fal 두 곳이며 별도 Gemini/Cloud 결제 SKU를 사용한다. 기존 조사 정책대로 공식 공개 자료나 보존권이 확인된 원문만 조사에 사용한다. 검색권 미확정이면 소재 자동 검색을 보류하고 확인된 원문을 입력해 스파이크를 진행한다. 핵심 동작은 I2V 3컷으로 시작하고, 클립 하나를 Wan T2V로 바꿔 가치와 비용을 비교한다. 절차적 회전/궤도 표시와 still을 나머지에 배치한다. 음악/SFX는 권리 확인된 기존 자산 또는 로컬 합성으로 가정한다.

| 50초 가정 | 계산 | USD |
|---|---|---:|
| LLM·검수 | 44k 입력 + 15k 출력 × 공개 토큰 단가 | 0.0335 |
| 조사 | 승인된 검색/자료 조회 비용 배정 | 0.0500 |
| 9:16 still | 6장 × $0.04, Lite 1K 가격에 부대 토큰 추정 포함. I2V 시작 이미지 포함 | 0.2400 |
| 한국어 음성 | 합산 600 청구 문자 × $0.00003 | 0.0180 |
| I2V 720p | 3 × 5초 × $0.05 | 0.7500 |
| 합계 | 1 USD당 1,650 KRW 계산값(예시 환율+부대비용 포함 가정) | 1.0915 |

합산 약 **1,801원**. T2V 5초 한 컷 대체 시 약 **2,214원**. 이 금액은 공개 요금 기반 예측이며 운영에서는 operation별 올림과 청구 증거를 적용한다. 30초 I2V 구성은 같은 가정에서 약 3,039원으로 상한을 넘는다. 60/20/20은 고정하지 않는다. 실제 소재·토큰·이미지·환율·부대비용 최대 예약이 3,000원 안에 들어오고 계정별 청구 증거를 확인할 수 있을 때만 조건부 운영 가능하다. 정액 구독, 충전 최소액, 로컬 운영비는 별도 표시. 실패·재생성 후의 한도 초과와 불명 과금은 다음 발송을 차단한다.

## 최소 기술 스파이크

동일 근거·프롬프트·입력 이미지와 평가표를 고정한다. 1,650원/USD 계산값 기준, 호출별 산출물 SHA·가격·시각·청구 확인을 기록한다. 아래는 **미승인** 추정이며 1~6 합산 약 5,100원 이내 예상(별도 1편 인수 포함, ElevenLabs 구독/충전 최소액 제외). 실제 최대 지출과 호출 목록은 별도 승인받는다.

| 실험 | 표본·예상 비용 | 합격 기준 |
|---|---|---|
| S1 한국어 대본 | 동일 근거 2편 × Gemini/GPT/Claude, 전체 입력 12k·출력 4k/모델. 약 120원 | 2편 모두 출처 없는 수치 0, 구조화 계약 통과, 사람 평가에서 사건/코미디/한국어 각각 4/5 이상인 모델 선택 |
| S2 TTS | 300문자 표본 2개, Chirp 약 30원. ElevenLabs 300~600 credits 및 플랜 비용 NOT_VERIFIED, 별도 승인 선택 | 두 표본 핵심 용어·숫자·단위 오독 0, 자연스러움 4/5 이상, 사람 검증 타이밍과 표본 cue 차이 ≤250ms |
| S3 이미지 | 동일 세로 프롬프트 3개 × FLUX/Nano. 약 230원 | 최소 2/3에서 세로·주 피사체 안전영역, 이상 왜곡 없음, I2V 시작 프레임 적합 |
| S4 동일 동작 영상 | 같은 시작 이미지와 5초 동작 2종 × Wan Flash/Runway, Wan T2V 1회. 약 2,475원 | 2종 중 2종에서 카메라만 이동한 경우를 제외한 의도된 운동 확인, 세로 QC/시간 연속성 통과, 청구 단위와 응답 일치 |
| S5 혼합 렌더 | 생성 결과/기존 허용 fixture 재사용, API 0원 | still/clip/절차적 입력으로 40~60초 1080×1920 CFR30, 오디오·자막·해시 및 보호 실행기, scratch 압박 회귀 통과 |
| S6 영상당 비용 | 별도 승인된 1편, 약 1,801~2,214원 | 실제 제작 원가와 모든 시도 합계 ≤3,000원. 불명 정산 0. 기술 QC와 사용자 인수 통과 |
| S7 비동기 복구 | 모의 큐와 격리 DB, API 0원. 실제 job ID 재조회는 S4 실행에 결합 | B01/B02 포함 모든 경계에서 중복 submit·정산 0, 분실 응답의 UNKNOWN 유지 및 ID 있는 작업의 재시작 조회 증명. 공급사 단위 실패·취소 과금이 불명하면 활성화 보류 |

공식 SKU 확인, 계정/리전과 권리 확인, 1편 최종 실청구 대조, 스타일·음성의 사용자 선택 순서로 결과를 기록한다. 예비 비용 안에서의 재생성도 별도 발송 승인 범위에 명시한다.

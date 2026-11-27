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

## 공급자 후보 조합과 비용 비교

1차 제안은 **Gemini Flash-Lite + Nano Banana Lite + Chirp 3 HD + fal Wan 2.6 I2V Flash**다. 공급사 계열은 Google과 fal 두 곳이며 별도 Gemini/Cloud 결제 SKU를 사용한다. 기존 조사 정책대로 공식 공개 자료나 보존권이 확인된 원문만 조사에 사용한다. 검색권 미확정이면 소재 자동 검색을 보류하고 확인된 원문을 입력해 스파이크를 진행한다. 가격 비교용 50초 사례에서 5초 I2V 3컷(총 15초)을 가정하고, 한 컷을 Wan T2V로 바꾼 사례와 비교한다. 30초 AI 영상 및 60/20/20 구성도 비교 사례다. 실제 제작의 장면 수·길이·이미지 장수·매체 비율·공급자 배분은 SCENES와 서버 검증 결과로 정한다. 음악/SFX는 권리 확인된 기존 자산 또는 로컬 합성으로 가정한다.

| 50초 가정 | 계산 | USD |
|---|---|---:|
| LLM·검수 | 44k 입력 + 15k 출력 × 공개 토큰 단가 | 0.0335 |
| 조사 | 승인된 검색/자료 조회 비용 배정 | 0.0500 |
| 9:16 still | 6장 × $0.04, Lite 1K 가격에 부대 토큰 추정 포함. I2V 시작 이미지 포함 | 0.2400 |
| 한국어 음성 | 합산 600 청구 문자 × $0.00003 | 0.0180 |
| I2V 720p | 3 × 5초 × $0.05 | 0.7500 |
| 합계 | 1 USD당 1,650 KRW 계산값(예시 환율+부대비용 포함 가정) | 1.0915 |

합산 약 **1,801원**. T2V 5초 한 컷 대체 시 약 **2,214원**. 이 금액은 공개 요금 기반 예측이며 운영에서는 operation별 올림과 청구 증거를 적용한다. 30초 I2V 구성은 같은 가정에서 약 3,039원으로 상한을 넘는다. 모든 수량·비율은 비용 비교 가정이다. 실제 소재·토큰·이미지·환율·부대비용 최대 예약이 3,000원 안에 들어오고 계정별 청구 증거를 확인할 수 있을 때만 조건부 운영 가능하다. 정액 구독, 충전 최소액, 로컬 운영비는 별도 표시. 실패·재생성 후의 한도 초과와 불명 과금은 다음 발송을 차단한다.

## 기술 스파이크 순서와 호출 상한

**구현 전 S0~S4.** S0는 공식 SKU·요금·과금 상한·계정과 권리 확인, 로컬 표본 및 평가표 준비이며 유료 호출은 0건이다. S1~S4는 아래 호출 목록과 최대액을 사용자가 별도로 승인하고 해당 SKU의 청구 상한이 검증된 후에만 실행한다. 표의 KRW 금액은 실험별 지출 상한 제안이다. 공개 단가와 1,650 KRW/USD 가정에 따른 예상 비용과 구분한다.

| 실험 | 유료 호출 목록 및 한계 | 예상 / 승인 요청 상한 | 합격 기준 |
|---|---|---|---|
| S1 한국어 대본 | Gemini Flash-Lite·GPT-5.4 mini·Claude Haiku 4.5 각 2회. 요청당 입력 ≤6k·출력 ≤2k 토큰(청구되는 추론 포함), 유료 검색·도구 호출 0 | 약 120원 / 150원 | 두 소재 모두 과학 사실·근거 일치, 한국어·첫 3초·전개·상황 개그·결말을 사람이 평가해 각 4/5 이상 |
| S2 TTS | Chirp 3 HD 지정 시험 음성 2회 × 최대 300 청구 문자. ElevenLabs는 플랜·음성·최대 크레딧 가격 확인 후 별도 호출 승인 | 약 30원 / 40원 | 핵심 용어 오독 0, 자연스러움 4/5 이상, 표본 cue 편차 ≤250ms |
| S3 이미지 | FLUX Schnell·Nano Banana Lite 각각 동일 프롬프트 3회 × 출력 1장. 9:16, FLUX ≤1MP, Nano Lite 1K 및 이미지·텍스트/추론 청구 상한 확인 | 약 230원 / 250원 | 3개 중 최소 2개에서 세로 구도, 공통 그림체·색감·조명·피사체 일관성 및 I2V 적합 확인 |
| S4 영상 | 행성 회전과 낙하 상황 개그 각 1회 × Wan Flash I2V와 Runway Gen-4 Turbo I2V. 동일 시작 이미지 재사용. 낙하 동작 Wan T2V 1회. 총 5회, 모두 5초·720p·세로, 출력 각 1개 | $1.50 기준 약 2,475원 / 2,700원 | 두 동작 모두 피사체 운동과 개그 타이밍·연속성 확인, 청구 단위와 증거 일치. 받은 job ID의 재조회는 동일 작업으로 검증 |

S1~S4 제안 상한 합계는 3,140원이다. 첫 요청 전 호출별 SKU·수량·최대 외화 비용·환율/세금/수수료를 고정하고, 보수적 최대액 또는 청구 근거를 확인할 수 없거나 실험 상한을 넘으면 해당 호출을 보류한다. 실패·타임아웃·취소·식별자 유실·초과 청구·권리 또는 규격 불일치 시 해당 실험의 후속 유료 호출을 중단한다. 이미 발송된 결과의 조회와 청구 확인은 이어가며 자동 재제출과 대체 SKU 발송은 금지한다.

**공통 구현과 통합 S7.** 모의 공급자와 격리 DB로 B01/B02, job ID 저장 전후 장애, 증거 기반 UNKNOWN 운영 복구, 중복 정산 및 stale lease를 검증한다. 유료 호출은 0건이다.

**공통 계약 및 제품 구현 후 S5~S6.** S5는 보유 표본·fixture로 실제 보호 혼합 렌더와 무과금 동적 계획을 검증한다. 서로 다른 두 소재와 잔액 부족 사례에서 TTS 실측 뒤 계획 확정, 핵심 연출 보존, 미확정 비용과 활성 예약 포함, 적합안 부재 시 발송 0건을 확인한다. 40~60초 1080×1920 CFR30, 타임라인·오디오·자막·해시 및 자원 보호를 검증한다.

S6는 별도 승인된 실제 영상 1편이다. SCENES 기반으로 발굴·조사·대본·장면·TTS와 후속 미디어·메타데이터·검수의 유료 효과 목록을 SKU·호출별 최대액과 함께 단계별로 제출한다. TTS 실측 전에는 고비용 미디어 호출을 승인 목록에 확정하지 않는다. 기존 지출·미확정 비용을 포함해 **3,000원**이 최대이며, 1,801~2,214원은 비교 시나리오다. 실제 목록 및 최대 청구액 검증 전에는 발송을 보류한다. 실패·불명 또는 초과 청구 시 후속 발송을 멈추고 재생성은 별도 승인받는다. 불명 정산 0건, 기술 QC, 대본 재미와 영상 연출 재미의 별도 판정 및 사용자 인수가 합격 조건이다.

기존 약 5,100원은 S1~S6 비교 시나리오의 예상치다. 승인된 금액은 없으며 ElevenLabs 플랜·충전 최소액은 별도 확인 대상이다.

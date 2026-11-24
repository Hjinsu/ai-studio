# AI Provider
Text/Image/Voice/Video/Research 인터페이스 분리. 공통 Request: jobId,requestKey,model,parameters,deadline; Result: externalId,usage,assets,estimatedCost,actualCost?,status,error. 비동기 submit/poll/cancel 지원, provider별 지원 기능 명시. Fake provider deterministic fixtures 필수.
초기 후보 GPT/Claude/Gemini, OpenAI Image/FLUX/Nano Banana, TTS OpenAI/ElevenLabs, Video Kling/Veo 등. 실제 모델명·단가·라이선스·지역·quota는 미확정. 연동 직전 공식 문서로 확인하고 model registry에 version/date/source 기록. API 키는 서버 secret resolver. 타임아웃은 UNKNOWN 외부 상태로 처리하며 조회 불가 시 사람 확인. 대체 모델 사용 전 예산/라이선스 재검증.
가격 단위 입력/출력 token, image, seconds, characters별로 기록. mock 기본, 유료 호출은 명시적 허가.

# V2-04 인수용 FAKE/로컬 미디어 fixture 생성기

생성물(이미지·낭독·음원·`catalog.json`)은 Git 밖에 둔다. 여기에는 재현용 생성기와 cue 분할 정의만 둔다.

```bash
A=C:/Users/<you>/Downloads/AI-Studio-V2-04-Acceptance   # 저장소 밖
# 준비: $A/tools/piper/piper.exe (Piper 2023.11.14-2), $A/downloads/voice/en_US-ljspeech-high.onnx(+json),
#       $A/downloads/font/NotoSansKR-Bold.otf (noto-cjk 정적 Bold, OFL)
java GenerateImages.java "$A/fixtures/ifnova-v204-1/images" "$A/downloads/font/NotoSansKR-Bold.otf"
node generate-catalog.mjs <repo-root> "$A"
```

- `narration-chunks.json`: 장면별 자막 cue 단위 원문 분할(이어 붙이면 SCRIPT 원문과 정확히 같아야 함)과 휴지 길이. cue마다 따로 합성해 실제 샘플 오프셋으로 시간표를 만든다(`VERIFIED_FIXTURE`).
- 카탈로그는 이미지 프롬프트 SHA-256, 낭독 원문·음성·언어로 매핑하고 모든 파일을 SHA-256·크기·권리로 고정한다.
- 실행 설정: `ai-studio.production.media.fixture-catalog=ifnova-v204-1`, `voice-id=piper-en_US-ljspeech-high-1`, `product.fixture-root=$A/fixtures`, `font-path`·`font-sha256`.

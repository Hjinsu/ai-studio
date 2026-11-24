# 영상 제작
DISCOVER(수동 P0) → RESEARCH → SCRIPT → STORYBOARD → GENERATE_MEDIA(장면별) → SUBTITLE → RENDER → METADATA → QUALITY_CHECK → APPROVAL → PUBLISH → ANALYTICS.
대본 문장별 근거 ID와 사실/가상 구분. 장면 순서, 음성, visual prompt, duration 목표, transition 저장. TTS 실제 길이를 ffprobe로 측정해 타임라인 재계산. 자막 타임코드는 실제 음성 기반, UTF-8 SRT, 겹침 금지. 이미지 crop/pad, pan/zoom, licensed BGM ducking. 렌더: 각 장면 H264 yuv420p 30fps AAC 동일 규격으로 변환 후 concat, loudness 확인, faststart MP4. FFmpeg ProcessBuilder 인자 배열, timeout, stderr 제한, exit code 확인.
manifest에는 revision, 모든 asset SHA256, 자막, 썸네일, 제목/설명, disclosure, render config 포함. 장면 수정 시 새 revision, 변경되지 않은 asset 재사용 가능. 검수: 해상도·코덱·duration·decode·audio·자막·중복·출처. 실패 시 재렌더 또는 수동 조치.

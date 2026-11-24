# 유튜브 연동
OAuth 2.0 authorization code, state, 필요한 최소 scope, refresh token 암호화. 연결 후 실제 channel ID 확인. 기본 AI_STUDIO_YOUTUBE_UPLOAD_ENABLED=false.
승인 revision/hash 확인 → resumable upload(private 명시) → external video ID 저장 → processing 상태 확인 → thumbnail/captions → 공개 전 metadata/disclosure/예약 재확인 → publishAt 또는 수동 공개. 예약은 UTC 미래 시각, private 조건 확인. 업로드 세션 URL은 비밀로 저장. 응답 유실 시 세션/영상 조회로 복구, 자동 중복 업로드 금지. 승인 변경 시 예약 취소/비공개 유지 절차.
공식 문서 현재 확인: videos.insert/update에 containsSyntheticMedia, publishAt 지원. 현재 공식 문서는 과거 미검증 프로젝트 private 제한이 철회되었다고 설명하나 실제 프로젝트 권한/정책/쿼터는 연동 시 재검증. 공개 가능하더라도 이 시스템은 승인 전 private 강제. Analytics는 권한/지표 호환성 검증 후 수집, 수익 미승인 시 NULL.
https://developers.google.com/youtube/data/reference/rest/v3/videos/insert
https://developers.google.com/youtube/data/reference/rest/v3/videos
https://developers.google.com/youtube/data/revision_history
https://developers.google.com/youtube/analytics

## 결정 반영 (2026-11-25)
- ADR-018: publish_target(게시 대상), publish_job(승인 revision의 대상별 게시), publish_attempt(업로드 시도 이력) 분리. 대상별 게시 중복 방지, 시도 이력 보존. 업로드 결과가 불확실하면 세션/영상 조회로 확인하기 전 재업로드 금지. 구현 T08.

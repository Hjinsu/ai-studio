# 테스트
unit: 상태 전이, 비용 계산, retry, prompt schema, hash, 승인 무효화. integration: MariaDB Testcontainers, Flyway clean install/upgrade, 경쟁 worker claim, lease fencing, 동시 예산 예약, idempotency, crash recovery. contract: fake HTTP timeout/429/400/응답 유실, OAuth mock. media: fixture image/audio로 MP4 렌더, ffprobe 해상도/길이/stream, SRT 타이밍. UI: 채널 생성→제작→검수→승인→mock 게시, stale approval 거절. security: CSRF, path traversal, SSRF, secret redaction.
CI Windows/Linux 가능 범위, Gradle test, npm ci/test/build, fake E2E. 실제 API smoke test는 별도 opt-in, 최대 비용 지정, private only. DoD: 결과 기록, 실패 재현, 문서 갱신. 현재 테스트는 아직 실행되지 않음.

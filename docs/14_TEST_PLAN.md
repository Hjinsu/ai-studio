# 테스트
unit: 상태 전이, 비용 계산, retry, prompt schema, hash, 승인 무효화. integration: MariaDB Testcontainers, Flyway clean install/upgrade, 경쟁 worker claim, lease fencing, 동시 예산 예약, idempotency, crash recovery. contract: fake HTTP timeout/429/400/응답 유실, OAuth mock. media: fixture image/audio로 MP4 렌더, ffprobe 해상도/길이/stream, SRT 타이밍. UI: 채널 생성→제작→검수→승인→mock 게시, stale approval 거절. security: CSRF, path traversal, SSRF, secret redaction.
CI Windows/Linux 가능 범위, Gradle test, npm ci/test/build, fake E2E. ADR-011: GitHub Actions, 단위(`test`)와 통합(`integrationTest`, Testcontainers MariaDB) Gradle task 분리, Docker 필요 테스트는 Linux job만. 통합 테스트 미실행 시 전체 통과로 기록 금지. 프론트는 lint/typecheck/test/build. 실제 API smoke test는 별도 opt-in, 최대 비용 지정, private only. DoD: 결과 기록, 실패 재현, 문서 갱신. 실행 결과는 STATE.md에 기록한다.

## 결정 반영 (2026-11-25)
- 통합 테스트(Docker 필요, Linux CI): `DatabaseBootstrapIT`(신규 DB V1→V2 초기화, collation, 세션 UTC, health), `DatabaseUpgradeIT`(V1 상태 DB를 최신으로 업그레이드, 불허 DB에서 Flyway 차단).
- 단위 테스트: `MigrationConventionTest`(파일명 규칙, 적용된 migration SHA-256 불변, CHARSET+COLLATE 규칙), `ErrorDispatchTest`(필터 단계 오류의 공통 형식).
- T02 필수 검증 목록과 방법: [plans/T02_PLAN.md](plans/T02_PLAN.md) 4절.

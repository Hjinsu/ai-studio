# 공식 참조 및 확인 범위
2026-11-25 확인: Claude 프로젝트 skill 위치 및 Codex repository skill 위치, AGENTS.md 지원, YouTube videos insert/update 필드 및 과거 private 제한 철회 문서.
- https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview
- https://learn.chatgpt.com/docs/build-skills
- https://learn.chatgpt.com/docs/customization/overview
- https://developers.google.com/youtube/data/reference/rest/v3/videos/insert
- https://developers.google.com/youtube/data/revision_history
가격, 개별 AI API 기능, YPP 세부 정책, SDK 및 버전 호환성은 미검증. 구현 단계에서 확인 후 기록한다.

## T01 고정 버전 (2026-11-25, Maven Central/npm registry/services.gradle.org/Docker Hub/nodejs.org 메타데이터 확인)
| 항목 | 버전 | 선택 근거 |
|---|---|---|
| Java | 21 (Gradle toolchain, 자동 provision: foojay-resolver-convention 1.0.0) | AGENTS |
| Gradle Wrapper | 9.8.2 | 최신 9.9.2는 2026-11-19 출시 직후라 직전 안정 패치 선택 |
| Spring Boot | 4.1.3 | 4.2.0(2026-11-19)은 MyBatis starter 미지원. mybatis-spring-boot-starter 4.1.0이 Boot 4.1.0 기준 |
| mybatis-spring-boot-starter | 4.1.0 | |
| Flyway / MariaDB JDBC / Testcontainers | Boot 4.1.3 BOM 관리(12.4.0 / 3.5.11 / 2.0.5) | 호환성은 Boot BOM에 위임 |
| MariaDB (Compose/CI 이미지) | 11.8.8 | 11.8.9는 2026-11-20 출시 직후. 로컬 설치본은 11.8.2 |
| Node.js | 24 LTS (로컬 24.19.0 확인, CI는 `.nvmrc`) | 로컬 설치본과 일치 |
| TypeScript | 6.0.3 | 7.x는 typescript-eslint(peer <6.1.0) 미지원 |
| React / Vite / Vitest / ESLint | 19.3.1 / 8.3.8 / 5.1.1 / 10.15.0 | package.json 정확 버전 + package-lock.json |
| GitHub Actions | checkout v7.0.1, setup-java v6.3.0, setup-node v7.1.0, gradle/actions v6.4.1, gitleaks v8.30.1 | |

### MariaDB 버전 차이 (2026-11-25 확인)
| 환경 | 버전 | 비고 |
|---|---|---|
| 로컬(Windows 설치본) | 11.8.2 (2025-07 릴리스) | 기본 collation_server `utf8mb4_uca1400_ai_ci`, global time_zone SYSTEM(앱 세션은 `+00:00`) |
| Compose / CI Testcontainers | 11.8.8 | 같은 11.8 LTS 계열 패치 차이(11.8.3~11.8.8 버그·보안 수정 미반영) |
- Flyway 12.4.0(Boot 4.1.3 관리)은 기동 시 "MariaDB 11.8은 검증 버전(11.7)보다 새 버전" 경고를 출력한다. 두 환경 공통이며 V1 적용은 정상. Flyway 업그레이드 시 재확인.
- 로컬 11.8.2를 11.8.8로 올릴지는 사용자 결정(로컬 DB 서버 공용 사용 중이므로 임의 업그레이드 금지).

- https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.3/
- https://repo1.maven.org/maven2/org/mybatis/spring/boot/mybatis-spring-boot/4.1.0/
- https://services.gradle.org/versions/all
- https://hub.docker.com/_/mariadb/tags
- https://nodejs.org/dist/index.json

## T02 의존성 후보 확인 (2026-11-25, Maven Central/npm registry, 스파이크는 scratchpad에서 실행 후 미커밋)
| 항목 | 버전 | 확인 내용 |
|---|---|---|
| Spring Security | 7.1.3 (Boot 4.1.3 BOM) | starter-security, starter-security-test BOM 관리 |
| Caffeine | 3.2.4 (Boot BOM) | rate limit 저장소 |
| openapi-request-validator-core/-mockmvc | 3.0.0 | swagger-request-validator 3.0.0이 이 좌표로 이전. Java 21, Spring 7.0.7 빌드, swagger-parser 2.1.41, json-schema-validator 2.0.1. OpenAPI 3.1 초안 검증 스파이크 17/17 |
| openapi-typescript | 7.13.0 | peer typescript ^5.x — 프로젝트 TS 6.0.3과 ERESOLVE. TS 5.9.3 별도 도구 디렉터리에서 생성, 결과는 TS 6 strict 통과 |
| @redocly/cli | 2.83.0 | OpenAPI 초안 lint |
| springdoc-openapi | 3.2.1 | Boot 4.2.0 기준(미채택) |
- https://repo1.maven.org/maven2/com/atlassian/oai/openapi-request-validator/3.0.0/
- https://www.npmjs.com/package/openapi-typescript

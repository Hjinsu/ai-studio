# 보안
초기 단일 관리자, 비밀번호 Argon2id 또는 BCrypt, session cookie HttpOnly/Secure/SameSite, CSRF, 로그인 rate limit. HTTPS 배포. OAuth state 및 redirect allowlist. secret 환경변수/secret store, token AEAD 암호화 및 키 버전/회전. 로그에서 token/key/session URL 제거.
SSRF: research URL protocol allowlist, private IP/redirect 검사, 응답 크기 제한. 파일 상대경로 검증, MIME/size, FFmpeg sandbox 및 시간/메모리 제한. SQL parameter binding, 외부 텍스트 prompt injection 격리. DB 최소 권한. 감사 로그. 백업 암호화 및 복원 테스트. Git에는 실제 계정 데이터/영상/토큰 금지. CI에서 secret scan. Actuator는 health만 노출하며 상세/구성요소 정보 비공개(ADR-008). 환경변수는 `AI_STUDIO_` 접두사, 누락 시 기동 중단하며 오류에는 변수 이름만 출력(ADR-013).

## 결정 반영 (2026-11-25)
- ADR-023: Spring Security 세션 기반 관리자 인증, BCrypt, CSRF 활성, 세션 쿠키 HttpOnly·SameSite=Lax, 유휴 만료 30분. local(HTTP) Secure=false, 운영(HTTPS) Secure=true. 로컬 서버는 loopback(127.0.0.1) 바인딩(T01 적용). 초기 관리자 비밀번호 하드코딩 금지. 구현 T02.
- ADR-024: 모든 DB profile(local/docker/prod)에서 URL 사전 검사와 Flyway 직전 `SELECT DATABASE()` 검사로 허용 DB(`ai_studio`)가 아니면 즉시 중단. 테스트는 격리 DB(Testcontainers `ai_studio_test` 등)만 허용. 필터 단계 오류도 공통 오류 형식. T01 적용.
- ADR-025: 로컬 전용 DB는 ALL PRIVILEGES 허용. 운영은 Flyway 전용 migration 계정(`AI_STUDIO_DB_MIGRATION_USER/PASSWORD`)과 앱 계정 분리(T01 설정 적용, 운영 미검증). Flyway 전에 migration 계정과 애플리케이션 계정의 실제 접속 DB를 모두 검사.

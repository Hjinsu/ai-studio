# 보안
초기 단일 관리자, 비밀번호 Argon2id 또는 BCrypt, session cookie HttpOnly/Secure/SameSite, CSRF, 로그인 rate limit. HTTPS 배포. OAuth state 및 redirect allowlist. secret 환경변수/secret store, token AEAD 암호화 및 키 버전/회전. 로그에서 token/key/session URL 제거.
SSRF: research URL protocol allowlist, private IP/redirect 검사, 응답 크기 제한. 파일 상대경로 검증, MIME/size, FFmpeg sandbox 및 시간/메모리 제한. SQL parameter binding, 외부 텍스트 prompt injection 격리. DB 최소 권한. 감사 로그. 백업 암호화 및 복원 테스트. Git에는 실제 계정 데이터/영상/토큰 금지. CI에서 secret scan.

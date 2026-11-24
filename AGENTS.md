# 공통 개발 계약
- ChatGPT는 설계 담당, Claude/Codex는 구현 담당. 사용자 승인 없는 핵심 설계 변경 금지. 충돌 시 작업 중단 후 DECISIONS.md에 제안 기록.
- 시작할 때 README.md, AGENTS.md, STATE.md, DECISIONS.md, 관련 docs를 읽고 git status 및 최근 커밋 확인. 기존 변경을 덮어쓰지 않는다.
- STATE.md의 첫 미완료 작업 하나를 선택해 구현·테스트한다. 작업 ID와 관련 요구사항을 명시한다. 완료 후 변경 파일, 테스트 명령/결과, 미해결 사항, 다음 작업을 STATE.md에 기록한다. 테스트 미실행을 성공으로 표시하지 않는다.
- Java 21, Spring Boot, Gradle Wrapper, React TypeScript, MariaDB, MyBatis, Flyway, Quartz, FFmpeg. 모듈러 모놀리스, DB 큐. 버전은 최초 구현 시 호환성 검증 후 고정한다. Redis/Kafka 불필요.
- 모든 경로는 저장소 상대경로 또는 환경변수. Windows/Linux 지원. UTF-8. 비밀키·OAuth 토큰·생성 영상·DB 덤프는 Git 금지. 실제 유료 호출/공개 게시 기본 비활성. 사용자가 허용한 경우에만 실행.
- DB migration 수정 금지(적용 후 신규 migration 추가). UTC 저장, 화면은 채널 timezone. 금액 DECIMAL, 통화 명시. API 오류 코드 고정. 외부 서비스 타임아웃 후 무조건 재호출 금지.
- 승인된 콘텐츠 revision과 파일 hash만 게시. 변경 시 승인 무효화. 자동 검수는 사람 검토 대체 불가.
- 공식 API 문서와 가격은 연동 직전 재확인. 추측한 SDK/모델/API 기능을 구현하지 않는다. 외부 의존 테스트는 mock 기본.
- 완료 조건: 구현+테스트+문서 갱신+비밀정보 검사. 커밋/푸시는 사용자 지시에 따름.

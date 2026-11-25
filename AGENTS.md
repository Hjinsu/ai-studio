# 공통 개발 계약
- ChatGPT는 설계 담당, Claude/Codex는 구현 담당. 사용자 승인 없는 핵심 설계 변경 금지. 충돌 시 작업 중단 후 DECISIONS.md에 제안 기록.
- 시작할 때 README.md, AGENTS.md, STATE.md, DECISIONS.md, 관련 docs를 읽고 git status 및 최근 커밋 확인. 기존 변경을 덮어쓰지 않는다.
- TASKS.md와 사용자 지시에서 자신에게 배정된 작업 하나를 확인하고 구현·테스트 또는 리뷰한다. 배정이 없거나 선행 조건이 충족되지 않으면 임의로 다음 작업을 선택하지 않는다. 작업 ID와 관련 요구사항을 명시한다. 완료 후 변경 파일, 테스트 명령/결과, 미해결 사항, 다음 작업을 STATE.md에 기록한다(병렬 작업은 아래 통합 담당자 규칙을 따른다). 테스트 미실행을 성공으로 표시하지 않는다.
- Java 21, Spring Boot, Gradle Wrapper, React TypeScript, MariaDB, MyBatis, Flyway, Quartz, FFmpeg. 모듈러 모놀리스, DB 큐. 버전은 최초 구현 시 호환성 검증 후 고정한다. Redis/Kafka 불필요.
- 모든 경로는 저장소 상대경로 또는 환경변수. Windows/Linux 지원. UTF-8. 비밀키·OAuth 토큰·생성 영상·DB 덤프는 Git 금지. 실제 유료 호출/공개 게시 기본 비활성. 사용자가 허용한 경우에만 실행.
- DB migration 수정 금지(적용 후 신규 migration 추가). UTC 저장, 화면은 채널 timezone. 금액 DECIMAL, 통화 명시. API 오류 코드 고정. 외부 서비스 타임아웃 후 무조건 재호출 금지.
- 승인된 콘텐츠 revision과 파일 hash만 게시. 변경 시 승인 무효화. 자동 검수는 사람 검토 대체 불가.
- 공식 API 문서와 가격은 연동 직전 재확인. 추측한 SDK/모델/API 기능을 구현하지 않는다. 외부 의존 테스트는 mock 기본.
- 완료 조건: 구현+테스트+문서 갱신+비밀정보 검사. 커밋/푸시는 사용자 지시에 따름.
- 프론트엔드 UI는 `docs/design/UI_DESIGN_SYSTEM.md`의 디자인 토큰과 공통 컴포넌트(`frontend/src/ui`, `frontend/src/layout`)를 사용한다. 임의 색·간격·새 UI 라이브러리를 추가하지 않는다(필요하면 ADR 제안). 금액·환율은 문자열(`frontend/src/lib/decimal.ts`)로만 다루고 Number로 변환하지 않는다. 모든 데이터 화면은 로딩·빈 상태·오류·권한·세션 만료를 처리하고, 비용 관련 위험 작업은 확인 대화상자를 쓴다. 라이트·다크·모바일을 함께 검증한다.

## Claude Code / Codex 협업 규칙

- 두 에이전트는 동등한 구현·테스트·리뷰 담당자이며 사용자 승인과 승인된 ADR을 따른다.
- 세션 시작 시 `TASKS.md`, `docs/guides/CODEX_COLLABORATION.md`를 기존 필수 문서와 함께 읽는다. 작업 ID, 담당, 기준 커밋, 브랜치, 수정 허용 범위 및 선행 조건을 확인한다. 파일이 없거나 배정이 불명확하면 사용자에게 확인한다.
- 교대 개발은 이전 담당자의 작업 종료 및 인수인계 확인 후 진행한다. 병렬 개발은 별도 Git worktree 및 브랜치를 사용하고 수정 범위를 분리한다. 같은 작업 트리를 동시에 수정하지 않는다.
- 공통 파일(STATE.md, DECISIONS.md, TASKS.md, OpenAPI, migration, 빌드 설정 및 lockfile)의 변경은 지정된 통합 담당자가 조율한다. 병렬 작업자는 자신의 작업 보고서에 변경 제안과 인수인계를 남기고 공통 파일 갱신은 통합 담당자에게 맡긴다.
- 독립 리뷰는 지정된 커밋을 기준으로 제품 코드를 읽기 전용으로 검토한다. 보고서는 `docs/reviews/` 또는 사용자에게 제출하며 결함 수정은 별도 배정 후 진행한다.
- 다른 담당자의 변경을 무단으로 덮어쓰거나 삭제하지 않는다. 강제 푸시, reset --hard, 무단 stash/clean 및 main 직접 변경을 금지한다. 커밋·푸시는 사용자 승인 범위 내에서만 수행한다.
- 작업 종료 또는 사용량 제한 시 변경 파일, 브랜치와 커밋, 실제 테스트 결과, 미검증 사항, DB migration 적용 상태 및 정확한 다음 작업을 기록한다. 비밀정보 값은 기록하지 않는다.
- 통합 테스트는 작업 DB나 업무 DB가 아닌 격리된 Testcontainers 또는 별도 임시 MariaDB에서 수행한다. `docs/guides/LOCAL_TEST_GUIDE.md`를 따른다.
- 기존 기능과 안전 규칙을 유지하고 테스트 우회·실패 은폐를 금지한다. 변경 범위에 맞는 회귀 테스트와 실제 빌드 산출물 및 사용자 실행 경로를 검증한다.
- 구현 완료는 IMPLEMENTED로 보고한다. 최신 PR HEAD의 CI, 독립 리뷰 및 필요한 사용자 검증을 확인하고 최종 사용자 승인 후 DONE으로 전환한다. main 병합, 다음 단계 착수, 실제 유료 API 호출, 지출 허용 및 YouTube 업로드는 각각 승인 범위를 확인한다.

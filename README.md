# AI Studio — 설계 기준선 v0.1
AI 영상 자동 제작 및 유튜브 운영 플랫폼. 현재 T01 bootstrap(백엔드/프론트 골격, Flyway V1·V2, health, 공통 오류 형식, 전용 DB 보호, CI)만 구현되어 있다. 진행 상황은 [STATE.md](STATE.md).
## 시작
1. 이 폴더를 Git 저장소 루트에 둔다(숨김 폴더 포함). 기존 프로젝트라면 충돌 검토 후 병합한다.
2. Git으로 비공개 원격 저장소에 저장한다. 다른 PC에서 clone/pull 후 동일 루트에서 Claude Code 또는 Codex를 실행한다.
3. Claude Code: `/ai-studio` 또는 “STATE.md의 다음 작업 진행”. Codex: `$ai-studio` 또는 같은 요청.
4. 각 PC에 Git, JDK 17 이상(Gradle 실행용, 컴파일용 JDK 21은 Gradle toolchain이 자동 준비), Node.js 24 LTS, MariaDB 11.8, FFmpeg/FFprobe(T05부터)를 설치한다. Docker는 선택(Compose 실행 및 통합 테스트용, ADR-010). 고정 버전은 [docs/16_SOURCES.md](docs/16_SOURCES.md).
5. `.env.example`을 `.env`로 복사하고 필요한 값만 로컬에 입력한다. 토큰과 DB/미디어는 Git으로 동기화하지 않는다. 별도 암호화 백업/공유 스토리지 필요.
6. 한 작업 종료 후 STATE.md 갱신, 검토 후 commit/push. 다른 PC에서는 pull 후 재개. 동시에 같은 작업을 수정하지 않는다.
## 로컬 실행 (저장소 루트 기준)
1. MariaDB 11.8에 전용 DB/계정 생성: `mariadb -u root -p` 후 [infra/mariadb/create-local-database.sql](infra/mariadb/create-local-database.sql) 내용을 비밀번호만 바꿔 실행. 다른 DB는 건드리지 않는다.
2. `.env.example`을 `.env`로 복사하고 `AI_STUDIO_DB_PASSWORD`를 입력한다. 필수: `AI_STUDIO_DB_URL`, `AI_STUDIO_DB_USER`, `AI_STUDIO_DB_PASSWORD`(누락 시 기동 중단). 프로젝트 변수는 모두 `AI_STUDIO_` 접두사이며, 같은 PC의 다른 프로젝트용 `DB_*` 변수는 사용하지 않는다(ADR-013). 모든 DB profile은 허용 DB(`ai_studio`, 테스트는 `ai_studio_test`)가 아니면 Flyway 전에 중단한다(ADR-024). prod는 migration 계정 변수도 필수(ADR-025).
3. 백엔드: `./gradlew :backend:bootRun` (Windows: `gradlew.bat`). profile 기본값 `local`, 127.0.0.1에만 바인딩, 시작 시 Flyway 적용(V1, V2). 확인: `curl http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`.
4. 프론트: `cd frontend && npm ci && npm run dev` (백엔드로 `/api`, `/actuator` 프록시, 주소는 `AI_STUDIO_BACKEND_URL`로 변경 가능).
5. 테스트: `./gradlew :backend:test`(단위), `./gradlew :backend:integrationTest`(Docker 필요, 없으면 실패), `cd frontend && npm run lint && npm run typecheck && npm test && npm run build`.
6. Docker 사용 시(선택): `docker compose --env-file .env -f infra/docker-compose.yml up --build`.
## 문서
[설계 목차](docs/00_INDEX.md), [작업 상태](STATE.md), [결정 기록](DECISIONS.md), [공통 계약](AGENTS.md).
## 초기 설정
단일 영어 과학 채널은 제안값. 쇼츠 40~60초, 롱폼 8~12분, 월 API 예산 30만원은 가설이며 실측 전 확정 비용이 아니다. 실제 계정 연동/게시/수익화는 미검증.

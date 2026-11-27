# 로컬 테스트 가이드 (Docker 없는 Windows PC 포함)
작성 2026-11-25 · 근거: ADR-011, ADR-024, AGENTS.md(작업 DB·업무 DB 변경 금지)

## 1. 원칙
- 통합 테스트는 **격리된 테스트 전용 DB**만 사용한다. 기본은 Testcontainers(Docker, CI Linux job).
- Docker가 없으면 **작업용 MariaDB(3306)와 별개의 임시 MariaDB 인스턴스**(예: 127.0.0.1:3307, 임시 데이터 폴더)를 띄워 `AI_STUDIO_IT_DB_*`로 지정한다. 테스트가 `ai_studio_test_*` DB와 테스트 계정을 직접 만든다.
- 3306 작업 DB(`ai_studio`)와 다른 업무용 DB를 테스트 대상으로 지정하지 않는다. 임시 인스턴스 비밀번호는 임시값으로 하고 저장소에 기록하지 않는다.
- 이미 적용된 Flyway migration은 수정하지 않는다(새 V 파일 추가).

## 2. 임시 MariaDB 실행 (Windows, MariaDB 11.8 설치 기준)
경로는 예시다. `<TMP>`는 저장소 밖 임시 폴더, `<PW>`는 임시 비밀번호.
```bash
BIN="/c/Program Files/MariaDB 11.8/bin"
mkdir -p <TMP>
"$BIN/mariadb-install-db.exe" --datadir=<TMP>/itdb --port=3307 --password=<PW>
"$BIN/mariadbd.exe" --defaults-file=<TMP>/itdb/my.ini --bind-address=127.0.0.1 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
  --default-time-zone=+00:00 --max-connections=300 --console      # 백그라운드로 실행
"$BIN/mariadb.exe" -h 127.0.0.1 -P 3307 -u root -p<PW> -e "SELECT VERSION(), @@collation_server, @@time_zone"
```
서버 옵션(문자셋·collation·UTC·연결 수)은 CI 컨테이너(`TestDatabases`)와 같다.

## 3. 테스트 실행
```bash
# 단위(DB 불필요)
./gradlew.bat :backend:test
# 통합(임시 인스턴스 지정)
AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<PW> \
  ./gradlew.bat :backend:integrationTest
```
- `integrationTest`는 `bootJar`를 먼저 만든다(AdminCliJarIT가 실제 jar로 관리자 CLI를 실행).
- 결과 XML: `backend/build/test-results/{test,integrationTest}/`. 명세(`docs/api/openapi.yaml`)·운영 권한 템플릿(`infra/mariadb/prod-app-grants.sql`)은 Gradle 테스트 입력으로 선언되어 바뀌면 다시 실행된다.
- 프론트(대문자 드라이브 경로에서 실행): PowerShell `Set-Location 'C:\...\ai-studio\frontend'; npm run lint; npm run typecheck; npm test; npm run build`.
  소문자 드라이브(`c:\...`)를 작업 경로로 vitest를 실행하면 vitest 모듈이 두 번 로드되어 "failed to find the current suite"로 모든 파일이 실패한다(환경 문제, 코드와 무관).
- OpenAPI 계약·생성 타입: `cd frontend/tools/openapi-typescript && npm ci && npm run lint:openapi && npm run check`. 명세 변경 후에는 `npm run generate`로 `frontend/src/api/generated/schema.d.ts`를 재생성해 커밋한다(직접 수정 금지).

## 4. 백엔드+프론트 연동 확인(선택)
작업 DB에 migration을 적용하지 않으려면 임시 인스턴스에 `ai_studio` DB를 만들고 환경변수로 `.env` 값을 덮어쓴다(OS 환경변수가 `.env`보다 우선).
```bash
"$BIN/mariadb.exe" -h 127.0.0.1 -P 3307 -u root -p<PW> -e "CREATE DATABASE ai_studio CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
AI_STUDIO_DB_URL=jdbc:mariadb://127.0.0.1:3307/ai_studio AI_STUDIO_DB_USER=root AI_STUDIO_DB_PASSWORD=<PW> ./gradlew.bat :backend:bootRun
cd frontend && npx vite --host 127.0.0.1 --port 5173      # 프록시 → 127.0.0.1:8080
```
관리자는 대화형 터미널(cmd/PowerShell)에서 `java -jar backend/build/libs/<jar> admin create --login <id>`(또는 `--login=<id>`, 콘솔 숨김 입력, ADR-031)로 만든다. 콘솔이 없는 실행(IDE 출력 창, 파이프)은 거부된다(종료 코드 2).

## 5. 종료·정리
```bash
"$BIN/mariadb-admin.exe" -h 127.0.0.1 -P 3307 -u root -p<PW> shutdown   # 정상 종료
rm -rf <TMP>/itdb                                                         # 필요 시 데이터 삭제
```
bootRun·vite 프로세스도 종료하고 8080·5173 포트가 비었는지 확인한다(`Get-NetTCPConnection -State Listen -LocalPort 8080,5173`).

## V2-04 실제 앱 검증

- 위 절차로 loopback 격리 MariaDB에 `ai_studio`를 만들고 V13까지 적용한다. 업무 앱과 겹치지 않는 앱·Vite 포트를 선택한다. 실제 앱용 jar와 FFmpeg·ffprobe·글꼴·카탈로그의 실행 인수는 [제품 보고서 §6](../reports/V2-04_PRODUCT_REPORT.md)를 따른다. 기존 Workspace와 분리된 최대 32 GiB 전용 scratch 볼륨에서 검증용 하위 디렉터리를 사용한다.
- 관리자 CLI는 대화형 숨김 입력 환경에서 실행한다. 비대화형 인수에서 CLI가 종료 코드 2를 반환하면 격리 DB에만 앱과 같은 BCrypt(12) 해시로 테스트 관리자 행을 만들어 실제 로그인 경로를 검증할 수 있다. 임시 비밀번호 파일과 산출물은 Git 밖에 둔다.
- `AI_STUDIO_BACKEND_URL`로 Vite의 백엔드를 지정한다. `frontend`에서 `LIVE_URL`, `LIVE_PASSWORD_FILE`, `LIVE_OUT`을 지정해 `node e2e/live-media.mjs`를 실행한다. 두 소재 전체 실행 두 번과 `LIVE_TOPICS=neutron-star` 실행 한 번으로 다섯 편을 확인한다. 결과 디렉터리는 배치마다 고유하게 둔다. 소재 선택 뒤 11개 단계, 브라우저 재생·seek·다운로드 SHA, QC와 scratch 잔존을 확인한다. 검증 사이에는 앱이 정상 정리하도록 둔다.

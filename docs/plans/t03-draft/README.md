# AI Studio T03 — 개발 인수 패키지
작성: 2026-11-25 KST / v0.1 / 설계 제안, 구현 승인 전

## 집에서 시작하기
1. 회사의 T02 종료 보고서로 PR #1 병합 및 최신 main CI를 확인한다. 병합 완료 시 `git fetch origin; git switch main; git pull --ff-only origin main`. 미병합이면 기존 작업 브랜치에서 종료 절차를 마친다.
2. 기존 개발 DB를 백업하고 회사 덤프를 복원한다. Flyway V1~V5 보존. Java21, Node24, MariaDB11.8, AI_STUDIO_DB_URL/USER/PASSWORD 확인. 비밀/덤프 Git 금지.
3. 백엔드 ` .\gradlew.bat :backend:bootRun`, 별도 터미널 `cd frontend; npm.cmd ci; npm.cmd run dev`. localhost:5173 및 localhost:8080/actuator/health 확인. 기존 IFNOVA, 정책, 관리자, 지출 차단 확인.
4. 이 패키지를 저장소의 임시 검토 경로(예: docs/plans/t03-draft/)에 복사한다. 기존 문서를 덮어쓰지 않는다.
5. T03_HANDOFF.md를 Codex 또는 Claude에게 전달한다. 처음은 T03-A 계약 대조 및 승인 요청이다. 사용자 승인 후 개발한다.

## 파일
T03_PLAN / DATABASE_DESIGN / STATE_MACHINE / JOB_ENGINE_DESIGN / COST_INTEGRATION / API_DESIGN / UI_DESIGN / TEST_PLAN / DECISIONS / HANDOFF.

## 출처와 한계
사용자 첨부 최신 T03_PLAN.md, DECISIONS(2).md, T02_DDL_DRAFT.sql 및 openapi.yaml(이전 첨부 초안), 초기 docs/03_DATABASE.md, docs/08_AUTOMATION.md 및 대화 설계안. 최신 main의 실제 Java 코드·DDL·API·CI는 직접 검증하지 못했다. 특히 초기 논리 모델의 automation_job 및 상태명은 최신 제안과 차이가 있어 명시적 ADR 승인 후만 변경한다. 신규 D01~D12는 아직 승인되지 않았다. 실제 마이그레이션 및 제품 코드는 이 패키지에 포함되지 않는다.

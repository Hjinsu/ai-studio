# Claude Code 프로젝트 지침

@AGENTS.md

프로젝트 스킬 `ai-studio`를 사용한다. STATE.md를 먼저 확인한다.

UI 작업은 `docs/design/UI_DESIGN_SYSTEM.md`를 먼저 읽는다(규칙은 AGENTS.md).

## 협업 지침

공통 개발 및 협업 계약은 위에서 가져오는 `AGENTS.md`를 기준으로 한다. 백엔드·프론트엔드 제품 구현, 단위·통합 테스트 작성과 자신의 구현 결함 수정을 맡는다. v2 제품 기준은 [docs/v2](docs/v2/README.md)다. 첫 배정 후보 V2-01에서 고정 단계 실행·취소·재시작 복구와 자체 테스트를 맡는다. Codex는 공통 DB/API/포트와 비용 경계를 맡는다. 배정 및 착수는 TASKS.md의 선행 조건을 따른다. 개발 중 발견한 설계 문제의 근거와 영향을 보고하고 사용자 승인 후 반영한다.

세션 시작 시 `TASKS.md`와 `docs/guides/CODEX_COLLABORATION.md`를 확인하고 자신에게 배정된 작업만 수행한다. Codex와 교대하거나 병렬 작업할 때는 공통 계약의 인수인계 및 작업 트리 분리 규칙을 준수한다.

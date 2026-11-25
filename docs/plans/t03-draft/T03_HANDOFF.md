# Codex / Claude 개발 착수 지시서
AI Studio T03 인수 작업. 첨부 설계 패키지는 v0.1 제안이며 사용자 승인 전 제품 변경 금지. 저장소의 AGENTS.md, CLAUDE.md, STATE.md, TASKS.md, DECISIONS.md, docs/00_INDEX.md, 협업/로컬 실행 가이드, 기존 docs/plans/T03_PLAN.md를 먼저 읽는다. T02 DONE 및 PR #1 main 병합/CI 실제 확인. 집 PC 경로는 현재 저장소 루트 사용, 회사 경로 하드코딩 금지.

## 지금 수행할 T03-A
- git status, 현재 branch/HEAD, remote, PR 상태, CI, 미커밋 변경 확인. T02 미종료면 우선 종료 절차만 보고.
- 실제 V1~V5 migration과 mapper/domain/service, BudgetMapper.reservationBucketIssues, LockRetryingTransactions, request/event key, 현재 OpenAPI/생성 타입, 초기 DB/automation docs, Quartz 구성 대조.
- 패키지의 10가지 제약/12개 D안과 기존 ADR의 충돌·누락·추가 결정 목록 작성. 실제 schema/API 이름과 상태명으로 상세 계약 수정 제안. 기존 파일 덮어쓰기 금지.
- DB 컬럼/제약/인덱스/DDL 초안, 상태 전이표, 잠금 순서, 장애 경계/복구 판정, OpenAPI 변경안, UI 및 테스트 계획을 저장소 기존 문서 구조에 맞게 정리. 작업 분담 및 공유 파일 소유자 지정. 문서 전용 변경 범위를 사용자에게 보고.
- `docs/reports/T03_DESIGN_REVIEW.md`에 기준 HEAD, 확인 근거 파일/행, 충돌, 선택지/추천, 미결 사항, 승인 필요 D안, 테스트 계획 기록. 문서 커밋/푸시는 사용자 및 저장소 규칙에 따른다.
- **사용자에게 설계 승인 요청 후 정지.** 채팅에서 문서화 요청은 D01~D12 일괄 승인 또는 제품 구현 승인이 아니다. 이미 별도 명시 승인된 결정은 그 근거를 기록하고 중복 질문하지 않는다.

## 승인 후 실행
T03-A 계약 승인 및 T02 main 병합 확인 후에만 새 T03 브랜치/워크트리 생성, B→C→D→E→F 진행. backend/shared contract 소유자 지정, 다른 에이전트는 승인된 API로 UI 구현. 단계별 보고/테스트. 기존 V1~V5 수정 금지, 사용자 개발 DB 테스트 금지, 비밀/덤프 Git 금지, 실제 유료 API/지출 허용/게시 금지. Fake 검증은 격리 DB에서만 비용 허용 설정. 최종 T03 DONE/main merge는 사용자 승인 후, T04 착수는 별도 승인.

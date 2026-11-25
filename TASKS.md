# AI Studio — 작업 배정표

> 사용자 승인 후 사용. 현재 실제 Git 상태와 배정 여부를 확인하여 갱신한다. 단계별 공식 진행 상태는 STATE.md, 설계 결정은 DECISIONS.md를 기준으로 한다.

## 상태 정의

`TODO` 대기 · `IN_PROGRESS` 작업 중 · `BLOCKED` 중단/승인 대기 · `IMPLEMENTED` 구현 완료/검증 대기 · `REVIEW` 검토 중 · `DONE` 최종 승인 완료.

## T02 배정 초안

| ID | 작업 | 담당 제안 | 상태 | 수정 범위 | 선행 조건 |
|---|---|---|---|---|---|
| T02-UI | 통합 UI 개선·디자인 시스템 | Claude Code | IMPLEMENTED (UI `5d53065`, 통합 보완 `8319672`, 승인 2026-11-25, 사용자 재검증 대기) | frontend, UI 디자인 문서 | 기존 UI 개선 지시 승인 |
| T02-RV | 백엔드 독립 리뷰 및 gitleaks 예외 검토 | Codex | 완료(사용자 보고, 기준 `fa32535`) | 리뷰 보고서만 | 검토 대상 커밋 지정 |
| T02-RV-X | 백엔드 수정 교차 리뷰(`fa32535..6a32c64`) | Claude Code | 완료 — `docs/reviews/T02_CLAUDE_BACKEND_REVIEW.md`, 통합 승인 권고(조건부) | 리뷰 보고서만 | T02-FIX 완료 |
| T02-FIX | 리뷰 결함 수정(RV-01~09) | Codex | IMPLEMENTED(사용자 보고: `fix/t02-backend` `6a32c64`, PR #2, CI 45549256094 6/6) | 백엔드·계약 | 결함 및 수정 범위 승인 |
| T02-INT | PR #1에 PR #2(`6a32c64`) 통합·협업 문서 반영·통합 검증 | Claude Code | IMPLEMENTED (병합 `07f8963`, 협업 문서 `67d50b9`, UI 보완 `8319672`; 통합 HEAD `ab68286` CI 45557933535 7/7) | 통합·문서·UI 보완 | ADR-064 통합 승인 |
| T02-UI-RV | 통합 후보 UI 독립 리뷰 | Codex | 완료 — `docs/reviews/T02_CODEX_FRONTEND_REVIEW.md`(Git 참조 `refs/reviews/t02-ui-rv`), 기준 `8319672`, UI-RV-01~06 | 리뷰 보고서만 | 통합 완료 |
| T02-UI-FIX | UI-RV-01~06·UI-F01/F02/F03/F04/F05/F07/F11/F12/F13 수정, 안전 검토 A01~A03 | Claude Code | IMPLEMENTED — `docs/reports/T02_UI_FIX_REPORT.md`, 기준 `6f4589f`, 커밋 `646179a`, CI run 45567226915 7/7 | `frontend/`, 문서(API·DB 변경 금지) | 사용자 수정 승인(2026-11-25) |
| T02-UI-FIX-RV | T02-UI-FIX 표적 교차 리뷰 | Codex | TODO(요청됨) — 범위: `6f4589f..646179a`의 `frontend/`, 확인: UI-RV-01~06 해소, A01 응답 유실 처리와 P-T02-05 판단, A02·A03, UI-F 표시 문구, 회귀 테스트 강도 | 리뷰 보고서만(`docs/reviews/`) | T02-UI-FIX 커밋·CI |
| T02-VERIFY | 통합·회귀·CI 및 사용자 검증 | 사용자 | 1차 완료(사용자 보고: 주요 화면·데이터 보존·정합성 정상) → T02-UI-FIX 반영 화면 재확인 대기 | 테스트 및 보고서 | UI 및 결함 수정 완료 |
| T02-CLOSE | 최종 승인 및 PR 병합 | 사용자 | TODO | 상태·결정 기록 | 최신 HEAD CI 및 최종 검토 |
| T03 | 콘텐츠 revision·DB 작업 큐·lease·재시도 | 미배정 | BLOCKED | 설계 후 배정 | T02 DONE 및 T03 설계 승인 |

## 작업 상세 템플릿

- 작업 ID:
- 담당:
- 상태:
- 목표 및 완료 조건:
- 기준 커밋 / 브랜치 / worktree:
- 수정 허용 경로:
- 수정 금지 경로:
- 선행 작업 및 승인 ADR:
- 테스트 계획:
- 결과 보고서:
- 마지막 커밋 / PR / CI:
- 미해결 사항 / 다음 작업:

## 운영 규칙

한 작업의 구현 담당은 한 명으로 지정한다. 병렬 작업은 별도 worktree와 겹치지 않는 수정 범위를 사용한다. 공통 파일 수정은 통합 담당자가 관리한다. 작업을 시작하기 전에 배정 및 기준 커밋을 기록하고, 작업 종료 시 결과와 인수인계를 갱신한다. 사용자 승인 없이 TODO를 DONE으로 바꾸지 않는다.

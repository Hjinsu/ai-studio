# AI Studio — 작업 배정표

> 사용자 승인 후 사용. 현재 실제 Git 상태와 배정 여부를 확인하여 갱신한다. 단계별 공식 진행 상태는 STATE.md, 설계 결정은 DECISIONS.md를 기준으로 한다.

## 상태 정의

`TODO` 대기 · `IN_PROGRESS` 작업 중 · `BLOCKED` 중단/승인 대기 · `IMPLEMENTED` 구현 완료/검증 대기 · `REVIEW` 검토 중 · `DONE` 최종 승인 완료.

## v2 현재 배정

| ID | 담당 | 상태 | 범위와 선행 조건 |
|---|---|---|---|
| V2-00 | Codex | DONE · 사용자 승인 | v2 문서 전용 기준선. 분기 원점 `dab7013c4557ba87d4440dfc86417344bb2c4e1d`. [검토](docs/v2/V2-00_REPOSITORY_REVIEW.md) |
| V2-01 | Claude Code 실행 / Codex 비용·공통 계약 | DONE · 사용자 최종 승인 | FAKE 11단계와 비용 안전성 검증, 설계·실행기·비용 독립 리뷰 완료. [PR #4](https://github.com/Hjinsu/ai-studio/pull/4)로 v2/integration 병합. [종료 기록](STATE.md#v2-01--최종-승인-및-종료) |
| V2-02 | Claude Code 생성·평가·선정·UI / Codex 저장·계약·비용·검증 | DONE · 사용자 최종 승인 | 독립 재검토 APPROVE 및 HOLD 해소. [PR #5](https://github.com/Hjinsu/ai-studio/pull/5)로 v2/integration 병합. [종료 기록](STATE.md#v2-02--최종-승인-및-종료-2026-11-26-kst-done) |
| V2-03 | Claude Code 제품 / Codex 공통·안전성·결합·F05 | DONE · 사용자 최종 승인 | [PR #6](https://github.com/Hjinsu/ai-studio/pull/6)을 `v2/integration`에 `0aa11d0`으로 병합했다. [종료 및 비차단 후속](STATE.md#v2-03--최종-승인-및-종료-2026-11-27-kst-done) |
| V2-04-COMMON | Codex | IMPLEMENTED | `v2/04-common`, V13 및 공통 계약. [검증·인수 기록](STATE.md) |
| V2-04-SAFETY | Codex | IMPLEMENTED | `v2/04-safety`. Linux 보호, 장애 안전성 및 scratch 예약 수명주기. [검증 보고서](docs/reports/V2-04_SAFETY_REPORT.md) |
| V2-04-PRODUCT | Claude Code | IMPLEMENTED | 실제 앱 기술 검증 및 사용자 인수 PASS. Product `925d736225828c720f11bc95569f84a6a17165f3`. [보고서](docs/reports/V2-04_PRODUCT_REPORT.md) |
| V2-04-INTEGRATION | Codex | IMPLEMENTED | v2/04-integration. B01·B02 RESOLVED, 독립 리뷰 APPROVE. CI 확인 후 사용자 최종 병합 승인 대기. [통합 보고서](docs/reports/V2-04_INTEGRATION_REPORT.md) |

[품질·수익화 요구사항](docs/v2/05_CONTENT_QUALITY_AND_MONETIZATION.md)은 V2-02~08의 단계별 인수 기준에 적용한다. V2-01은 검수·재생성까지 동일 제작 요청 비용 경계를 제공한다. FFmpeg·FFprobe 준비는 V2-04에 포함한다.

T02/T03 표는 과거 배정 기록이다. T03-C/D는 WIP로 보존한다.

## T02 배정 이력

| ID | 작업 | 담당 제안 | 상태 | 수정 범위 | 선행 조건 |
|---|---|---|---|---|---|
| T02-UI | 통합 UI 개선·디자인 시스템 | Claude Code | IMPLEMENTED (UI `5d53065`, 통합 보완 `8319672`, 승인 2026-11-25, 사용자 재검증 대기) | frontend, UI 디자인 문서 | 기존 UI 개선 지시 승인 |
| T02-RV | 백엔드 독립 리뷰 및 gitleaks 예외 검토 | Codex | 완료(사용자 보고, 기준 `fa32535`) | 리뷰 보고서만 | 검토 대상 커밋 지정 |
| T02-RV-X | 백엔드 수정 교차 리뷰(`fa32535..6a32c64`) | Claude Code | 완료 — `docs/reviews/T02_CLAUDE_BACKEND_REVIEW.md`, 통합 승인 권고(조건부) | 리뷰 보고서만 | T02-FIX 완료 |
| T02-FIX | 리뷰 결함 수정(RV-01~09) | Codex | IMPLEMENTED(사용자 보고: `fix/t02-backend` `6a32c64`, PR #2, CI 45549256094 6/6) | 백엔드·계약 | 결함 및 수정 범위 승인 |
| T02-INT | PR #1에 PR #2(`6a32c64`) 통합·협업 문서 반영·통합 검증 | Claude Code | IMPLEMENTED (병합 `07f8963`, 협업 문서 `67d50b9`, UI 보완 `8319672`; 통합 HEAD `ab68286` CI 45557933535 7/7) | 통합·문서·UI 보완 | ADR-064 통합 승인 |
| T02-UI-RV | 통합 후보 UI 독립 리뷰 | Codex | 완료 — `docs/reviews/T02_CODEX_FRONTEND_REVIEW.md`(Git 참조 `refs/reviews/t02-ui-rv`), 기준 `8319672`, UI-RV-01~06 | 리뷰 보고서만 | 통합 완료 |
| T02-UI-FIX | UI-RV-01~06·UI-F01/F02/F03/F04/F05/F07/F11/F12/F13 수정, 안전 검토 A01~A03 | Claude Code | IMPLEMENTED — `docs/reports/T02_UI_FIX_REPORT.md`, 기준 `6f4589f`, 커밋 `646179a`, CI run 45567226915 7/7 | `frontend/`, 문서(API·DB 변경 금지) | 사용자 수정 승인(2026-11-25) |
| T02-UI-FIX-RV | T02-UI-FIX 표적 교차 리뷰 | Codex | 완료 — `6f4589f..646179a`, RVX-01~03 보고, `docs/reviews/T02_CODEX_UI_FIX_REVIEW.md` 및 `refs/reviews/t02-ui-fix-rv` | 리뷰 보고서 | T02-UI-FIX 커밋·CI |
| T02-UI-FIX-EXT | 세션 경계 및 정산 안전성(RVX-01~03) | Codex | IMPLEMENTED — `53b305b` 독립 검토에서 RVX-01·03과 RVX-02 지연 HTTP 통과, RVX-02-CB 후속 배정 | 프론트엔드 및 보고·결정 문서 | 사용자 승인 |
| T02-FINAL-PATCH | RVX-02-CB mutation 콜백 세대 보강 | Codex | DONE — `55ea852`, 별도 검토 PASS 및 최종 승인 | 직접 관련 프론트엔드 및 보고·결정 문서 | MEDIUM 잔여 위험 보강 승인 |
| T02-VERIFY | 통합·회귀·CI 및 사용자 검증 | 사용자 | 사용자 보고 화면 14/14 및 최종 변경 화면 재확인 6/6 완료 | 테스트 및 보고서 | UI 및 결함 수정 완료 |
| T02-FINAL-CLOSE | 최종 승인 기록 및 PR #1 병합 | 사용자 승인 · Codex 실행 | DONE — 2026-11-25 최종 종료 승인. [PR #1](https://github.com/Hjinsu/ai-studio/pull/1)에서 병합과 검사 추적 | 상태·결정·보고서, PR 및 Git 정리 | 최종 독립 검토 및 문서 HEAD CI 성공 후 병합 |
## T03 배정 이력

| ID | 작업 | 담당 | 상태 | 산출물·선행 조건 |
|---|---|---|---|---|
| T03-A1 | 도메인·작업 엔진 설계 검토 | Claude Code | 검토 완료 | [리뷰](docs/reviews/T03_CLAUDE_DESIGN_REVIEW.md) |
| T03-A2 | 비용 안전성·데이터 정합성 설계 검토 | Codex | 검토 완료 | [리뷰](docs/reviews/T03_CODEX_DESIGN_REVIEW.md) |
| T03-A3 | 도메인·작업 엔진 설계 수정안 | Claude Code | 수정안 작성 완료 | [수정안](docs/plans/t03-revision/T03_DOMAIN_ENGINE_REVISION.md), Claude Code의 마지막 설계 작업 |
| T03-A4 | 비용 안전성·영속 복구 설계 수정안 | Codex | 수정안 작성 완료 | [수정안](docs/plans/t03-revision/T03_COST_RECOVERY_REVISION.md) |
| T03-A5 | 통합 설계 검토 | ChatGPT · Codex | 저장소 검증 완료, FD-01~16 승인 | [통합 리뷰](docs/reviews/T03_INTEGRATED_DESIGN_REVIEW.md) |
| T03-A6 | 최종 구현 명세 및 착수 준비 | Codex | DONE (ChatGPT APPROVE, 사용자 최종 명세 승인) | [최종 명세](docs/plans/t03-final/README.md) |
| T03-B | 콘텐츠·도메인 DB | Claude Code | DONE (PR #3의 최종 HEAD 전체 CI 성공 및 t03/integration 병합 시 효력; 병합 전 IMPLEMENTED) | V6 및 RV-B-01~06 수정 검증 완료. [제품 보고](docs/reports/T03_B_FIX_REPORT.md), [재리뷰](docs/reviews/T03_B_FIX_CODEX_REVIEW.md) |
| T03-B-INT | 제품 OpenAPI, 생성 타입 및 인수인계 | Codex | 검증 완료 및 종료 승인 | [통합 보고](docs/reports/T03_B_INTEGRATION_REPORT.md), PR #3 |
| T03-B-CLOSE | 종료 문서·검증·병합 | Codex | 사용자 승인 | [최종 HEAD 검사 및 병합](https://github.com/Hjinsu/ai-studio/pull/3) |
| T03-C | 작업 실행 엔진 | Claude Code | BLOCKED | V7, Claude Code, 제안 브랜치 `t03/c-queue` 및 별도 worktree. B 참조 키·D 인터페이스, [C-G01~10](docs/plans/t03-final/T03_IMPLEMENTATION_HANDOFF.md#t03-c-필수-인수-조건), 착수 승인 |
| T03-D | 비용 안전성·영속 복구 | Codex | BLOCKED | B/C 참조 키와 인터페이스, 착수 승인 |
| T03-E | 관리자 UI | Claude Code | BLOCKED | OpenAPI 및 타입 계약, 착수 승인 |
| T03-F | 독립 리뷰·통합 검증 | Codex | BLOCKED | B~E 검증 가능한 HEAD, Codex 구현분은 별도 리뷰 세션 |

A3·A4 완료 표시는 수정안 작성 상태에 한정된다. 설계와 제품 구현 착수에는 각각 사용자 승인이 필요하다.

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

한 작업의 구현 담당은 Claude Code 또는 Codex 한 명으로 지정한다. Codex가 Claude Code 구현을 독립 리뷰한다. Codex 구현은 구현 세션과 분리된 Codex 세션에서 리뷰한다. 병렬 작업은 별도 worktree와 겹치지 않는 수정 범위를 사용한다. 공통 파일 수정은 통합 담당자가 관리한다. 작업을 시작하기 전에 배정 및 기준 커밋을 기록하고, 작업 종료 시 결과와 인수인계를 갱신한다. 사용자 승인 없이 TODO를 DONE으로 바꾸지 않는다.

# T03 계획 — 선행 조건과 필수 설계 제약 (초안)
작성 2026-11-25 · 상태: **설계 제약만 기록, T03 미착수**(T02 DONE 및 T03 설계 승인 전 제품 코드 개발 금지) · 근거: T02 통합 승인, [T02_CLAUDE_BACKEND_REVIEW](../reviews/T02_CLAUDE_BACKEND_REVIEW.md) §7, DECISIONS ADR-064·065, P-T02-04

T03 범위(STATE.md): 콘텐츠 revision, DB 작업 큐, lease, 재시도. T03 상세 설계는 아래 제약을 모두 충족해야 승인 대상이 된다.

## 필수 설계 제약 (T02 결과에서 도출)
| # | 제약 | 근거(T02 코드·결정) | T03 설계에서 정해야 할 것 |
|---|---|---|---|
| 1 | **CONTENT_TYPE·LIFETIME 예산 bucket 연결 규칙과 정합성 검사 SQL을 함께 확장한다.** | `BudgetMapper.reservationBucketIssues`는 SYSTEM·CHANNEL의 DAY·MONTH 연결만 유효로 본다. 콘텐츠 bucket(`content_id`)·LIFETIME 정책을 예약에 연결하면 무효 연결로 판정되어 **모든 신규 지출이 차단**된다(RV-07). | 콘텐츠 예산 연결 개수·기간 규칙, 검사 SQL 변경, 기존 예약과의 호환 |
| 2 | **상위 활성 트랜잭션 안에서 예산 재시도 경계를 호출하지 않는다.** | `LockRetryingTransactions.execute`는 활성 트랜잭션에서 `IllegalStateException`(RV-09). 예약·정산은 자체 최상위 트랜잭션으로만 실행된다. | 작업 큐 상태 전이와 예산 호출의 트랜잭션 경계(호출 순서, 실패 시 상태) |
| 3 | **작업 큐와 비용 예약·정산의 일관성을 멱등 키·재시도·복구 절차로 설계한다.** | 2에 따라 큐 상태와 예산을 한 트랜잭션으로 묶을 수 없다. 예약 `request_key`, 이벤트 `event_key` 멱등성(RV-01)이 결합 수단이다. | 작업별 request/event key 생성 규칙, 중간 실패·재시도 시 재사용, 고아 예약 탐지·정산(RECONCILIATION_REQUIRED 포함) |
| 4 | **`reserve:`·`reserve-v2:` 접두사는 내부 예약 전용으로 유지한다.** | 정산 이벤트가 두 접두사를 새로 쓰면 400(RV-03). | provider·작업 이벤트 키 접두사 규칙 |
| 5 | **예산 판정 재계획 소진 503을 재시도 정책에 반영한다.** | 기간 경계를 3회 넘으면 `SERVICE_UNAVAILABLE`(RV-02). | 작업 큐의 재시도 분류(일시 오류 vs 비즈니스 오류: BUDGET_EXCEEDED·POLICY_VIOLATION·PREREQUISITE_NOT_MET은 재시도 금지) |
| 6 | **정합성 검사의 전체 스캔과 control 잠금 시간을 검토한다.** | 검사가 전체 예약을 스캔하는 동안 control X로 예약·정산이 멈춘다. | 예약 규모별 측정, 검사 주기·범위, 큐 처리 지연 허용치 |
| 7 | **운영 프록시의 신뢰 범위와 실제 클라이언트 IP 처리 방식을 설계한다.** | 로그인 제한·동시 인증 상한이 `remoteAddr` 기준(ADR-045, P-T02-04 CR-01, 운영 배포 전 해결 조건). | 신뢰 프록시 목록, 전달 헤더 처리, 한도 재설정 |
| 8 | **실제 비용 발생 전 정합성 오류의 진단과 승인 기반 수동 복구 절차를 마련한다.** | 정합성 ERROR(특히 `RESERVATION_BUCKET_LINK_MISMATCH`) 발생 시 자동 수정·복구 경로가 없다(P-T02-04 CR-02, 실제 유료 API 연동 전 조건). | 진단 쿼리, 승인 절차, 보정 방식(신규 migration 또는 관리 명령, 원장 불변 유지) |
| 9 | **다중 인스턴스 도입 시 시계 동기화와 분산 로그인 제한을 재검토한다.** | `LoginRateLimiter`·`LoginAdmission`은 인스턴스 메모리, RV-07 링크 검사는 정책·예약 `created_at`을 같은 시계로 비교한다. | 시각 원천(DB 시각 등), 분산 제한 저장소 필요성 |

## 유지되는 T02 계약
- 비용 원장 불변(UPDATE/DELETE 금지, 정정은 조정·환불 이벤트), 지출 차단 기본값, Fail Closed(SYSTEM DAY·MONTH 필수, 정합성 ERROR 차단), 잠금 순서(control → channel → policy → bucket → reservation), 기존 V1~V5 migration 불변.
- 실제 provider 호출은 T07 승인 전 금지. T03의 비용 연동은 fake provider로만 검증한다.

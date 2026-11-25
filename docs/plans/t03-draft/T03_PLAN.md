# T03 상세 계획 v0.1 (제안)
목표: 콘텐츠 버전과 실행 단위, DB 큐, lease, 재시도, 장애 복구, 콘텐츠 예산을 구현하여 T04~T10 실행 기반 확보. T02 DONE 승인됨; 실제 PR 병합 및 main HEAD 확인 필수.

## 범위
콘텐츠 등록/보관, 불변 revision, run, DAG, job/attempt/event, worker, fake executor, 격리 fake provider, LIFETIME budget, 영속 수동 정산 요청, 관리자 UI. 실제 조사·대본·스토리보드 T04, 미디어 T05, 검수 T06, 유료 T07, 게시 T08. 주장/근거 및 미디어 모델의 연결 계약은 ADR-015/016과 일치하도록 대조하되 선행 구현 강제하지 않는다. Redis/Kafka 미도입.

## 필수 선행 제약 (기존 T03_PLAN 10개)
1. CONTENT_TYPE LIFETIME 연결과 reservationBucketIssues 동시에 확장; 과거 T02 링크 호환.
2. LockRetryingTransactions.execute는 외부 활성 TX에서 금지.
3. 큐/비용은 영속 operation + 키 + 복구로 연결.
4. reserve: 및 reserve-v2: 예약 전용.
5. 예산 재계획 소진 503 재시도; 비즈니스 오류 자동 재시도 금지.
6. 전체 정합성 스캔/control X 잠금 측정.
7. 신뢰 프록시 명시적 목록, 전달 헤더 위조 방지; 운영 배포 전 해결.
8. 정합성 오류 진단/승인 기반 수동 복구; 실제 비용 전 검증.
9. DB UTC 시각, 단일 인스턴스 유지; 확장 전 분산 로그인 및 시계 재검토.
10. P-T02-05 서버 영속 수동 정산 식별/조회/복구; 실제 비용 전 완료, T03 구현 제안.

## 단계와 종료 게이트
| ID | 결과물 | 게이트 |
|---|---|---|
| A | 실제 DDL/코드/API와 대조, ADR/ERD/전이표/OpenAPI 확정 | 사용자 승인, 변경 범위 고정 |
| B | V6 이후 migration, 콘텐츠/revision/run | 깨끗한 DB + V5 업그레이드 테스트 |
| C | DAG/큐/lease/취소/재시도/fake executor | 동시성/장애 테스트 |
| D | 비용 LIFETIME/operation/수동 정산 복구 | 비용 원장·정합성·장애 테스트 |
| E | 관리자 화면 | 계약·접근성·세션 경계 테스트 |
| F | 통합/독립 리뷰/사용자 확인 | CI 전체, 보고서, 사용자 승인 후 DONE/merge |

A에서 계약을 먼저 고정하고 B/C/D 백엔드 담당 1인, E UI 담당 별도 브랜치/워크트리 가능. 공유 파일(OpenAPI, migration, 상태 문서)은 소유자를 한 명 지정한다. T02 보류 UI/API 항목 무단 확대 금지.

## 완료 조건
기존 회귀 + 신규 테스트 통과, 격리 DB에서 중복/lease 만료/재시작/원장 복구 검증, 지출 차단 유지, 실제 provider/게시 0회, 사용자 검증 및 독립 교차 리뷰. 미실행 검증은 명시. STATE/TASKS/DECISIONS/인덱스/보고서 갱신, 사용자 승인 후만 DONE. 기존 V1~V5 불변, T04 자동 착수 금지.

# V2-03 공통 계약 및 저장·안전성 인수인계

2026-11-26 KST · 사용자 착수 승인 · Codex

- 통합 기준 `c4832b7eca2cbf89893bce92fa2b980263cf695e`에 V2-02 병합 및 DONE 포함.
- 공통 기준 `610b9f88fb57e762c939e0bdc03734d3d609913b`를 `origin/v2/03-common`에 푸시했다. `v2/03-product`와 `v2/03-safety`를 이 SHA에서 생성했다.
- 기존 설계 worktree의 원본은 보존하고 승인 기술 결정을 공통 브랜치의 복사본에 반영했다. [승인 설계 및 Claude 착수 지시](../v2/V2-03_IMPLEMENTATION_PLAN.md#구현-분담-및-claude-착수-지시)

## 공통 계약

| 경로 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V12__create_production_stage_result.sql`, `infra/mariadb/prod-app-grants.sql` | 테이블 1개, 소유권·불변성·본문 최대 128KiB, 운영 계정 컬럼 UPDATE 권한 |
| `production/contract` | RESEARCH·SCRIPT·SCENES 타입, 공급자·자료 검증 경계, 차단/권고, 해시와 발화 시간 계약 |
| `production/api`, `production/planning/PlanningResultMapper.java` | 선택적 신규 파이프라인 `v2-planning-1`, 과거 요청 호환, 요청 소유자 전용 결과 조회와 FAKE 표시 |
| `docs/api/openapi.yaml`, `frontend/src/api/generated/schema.d.ts` | 결과 조회 명세와 생성 타입 |
| 백엔드 단위·통합 테스트 | 플라이웨이·운영 권한·기존 파이프라인 회귀, 발화 경계, UTF-8 크기 및 해시, 조회 계약 |

공통 SHA에서 `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest`가 통과했다. 단위 212건, 격리 MariaDB 11.8 binlog ON 통합 223건이다. 프론트 lint·typecheck·build와 Vitest 89건, Edge Mock API E2E 13건, OpenAPI lint(기존 경고 2건) 및 생성 타입 일치 검사를 통과했다.

## 저장·비용·복구

- `production/cost/ProductionCostService.java`는 기존 요청 락·step fencing·예약과 dispatch를 재사용한다. 예약과 고정 입력을 함께 저장하고, 응답 본문과 정산 증거를 같이 커밋한다. 응답 후 정산 장애에서는 저장된 증거로 재생한다. 발송 후 결과 불명 상태에서는 동일 효과를 다시 발송하지 않는다.
- `production/planning/PlanningExecutionService.java`와 `PlanningResultMapper.java`는 SELECT의 적용된 DISCOVER 영수증, 요청의 불변 config·Revision, 앞 단계의 채택 영수증과 본문 해시를 대조한다. SCRIPT 비교 자료는 같은 채널의 최근 채택 대본 최대 20개다. SCENES의 내레이션과 자막 원문은 서버에서 고정 대본으로부터 도출한다.
- 단계 적용은 실제 비용 포트의 락과 영수증 적용을 사용한다. 검수 기록과 단계 성공/실패 변경은 실행기가 같은 fenced 트랜잭션에서 호출할 계약이다.
- `ProductionPortsIT`에서 계약 대역을 실제 저장·비용 포트에 연결했다. 정산 전 장애·새 lease 재생, stale lease 거부, 동일 효과 두 호출, 결과 불변성, 세 단계 데이터 계보 및 결과 API 응답을 검증한다.

안전성 구현의 전체 실행에서 단위 212건, 격리 통합 224건 및 bootJar가 통과했다. 마지막 보강 후 `gradlew.bat :backend:integrationTest --tests com.aistudio.production.cost.ProductionPortsIT`가 격리 MariaDB에서 11건 통과했다. 단계 적용 롤백, 응답 없는 채택 거부 및 FAKE의 VERIFIED 표시 차단을 포함한다. Git 공백·비밀정보 패턴 검사에서 발견 사항이 없었다.

## 다음 작업 및 남은 검증

Claude는 제품 worktree에서 FAKE 공급자·오프라인 자료 fixture, 사실/가상·중복·근거 검수, 장면 기획, 실행기 연결과 제작 상세 UI를 구현한다. 저장·비용 파일은 Codex 담당이다. P2의 주장 누락·의미상 중복 fixture와 P3의 FAKE 결과 표시를 제품 회귀에 포함한다. 신규 파이프라인 설정과 실행기의 기존 버전 claim을 함께 검증한다.

제품·안전성 통합 환경에서 두 과학 소재 E2E, 필수 차단과 개선 권고, 라이트·다크·모바일, 최신 CI 및 별도 세션 독립 리뷰가 남았다. 실제 공급자 연결 및 의미 검증은 V2-05에서 승인 후 수행한다. V12는 격리 DB에서만 적용했다. 사용자 DB, 유료 API, YouTube, 통합 브랜치 병합을 수행하지 않았다.

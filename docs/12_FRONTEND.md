# 관리자 UI
좌측 메뉴: Dashboard, Channels, Topics, Production, Review, Publish, Analytics, Budget, Providers, Settings.
Production 목록: 채널/상태/기간 필터, revision, 단계별 progress, 비용, 실패 사유. 상세: 영상 플레이어, scene timeline, 대본/근거/asset/자막, 재생성 요청, 비용 확인. Review: 체크 결과, license, disclosure, manifest hash, 승인/반려 사유. Publish: 승인 일치 여부, private/예약 상태, 현지시간 및 UTC. Budget: 실제/예약/예상 구분. Provider: secret 값 표시 금지.
React Query 또는 동등한 서버 상태 관리, 타입은 OpenAPI와 일치. 로딩/빈 상태/실패/권한 제한 표준화. 위험 작업 확인 모달. 외부 호출 버튼 기본 비활성. 접근성 키보드/label 지원.

## 결정 반영 (2026-11-25)
- ADR-021: 서버 타입은 OpenAPI 3.1 `docs/api/openapi.yaml`에서 생성(openapi-typescript 우선 후보).
- ADR-023: 개발은 Vite proxy(`/api`, `/actuator` → 백엔드), 운영은 Spring Boot가 React 빌드 결과를 정적 파일로 제공해 동일 출처로 구성. 화면 시간은 사용자/채널 시간대.

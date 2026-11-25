# T03 최종 구현 명세

기준 main `47ef69731a599c570846c1482dcabf8ead8fe2e4` · T03-A6 · 2026-11-25 KST. FD-01~16은 사용자 승인 결정이다. ChatGPT 최종 검토 APPROVE와 사용자 최종 명세 승인을 기록했다. T03-B는 FV-01~06을 조건으로 착수할 수 있다. C~F는 BLOCKED다.

적용 순서는 사용자 승인 FD 및 신규 ADR의 명시된 변경분 → 변경되지 않은 승인 ADR과 T02 제품 계약 → 이 패키지의 상세 계약이다. A3/A4, 기존 T03 초안 및 A1/A2/A5 리뷰는 검토 이력이다. 실제 구현과 검증은 배정 및 승인 범위에 따른다.

- [계획](T03_FINAL_PLAN.md) · [DB](T03_FINAL_DATABASE.md) · [상태](T03_FINAL_STATE_MACHINE.md)
- [잠금](T03_FINAL_LOCK_TRANSACTION.md) · [비용과 복구](T03_FINAL_COST_RECOVERY.md)
- [API](T03_FINAL_API.md) · [UI](T03_FINAL_UI.md) · [검증 추적](T03_FINAL_TEST_PLAN.md)
- [인수인계·배정](T03_IMPLEMENTATION_HANDOFF.md) · [SQL 초안](T03_SCHEMA_DRAFT.sql) · [OpenAPI 초안](T03_OPENAPI_DRAFT.yaml)

SQL은 리뷰용이며 Flyway 경로에 배치하거나 실행하지 않는다. OpenAPI 초안은 기존 계약에 합칠 변경 범위다. 제품 파일과 기존 OpenAPI는 현행 상태로 유지된다. 검증은 정적 대조에 한정한다.

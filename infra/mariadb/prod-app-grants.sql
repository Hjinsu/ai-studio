-- 운영 애플리케이션 계정 최소 권한 템플릿 (ADR-025, ADR-026, ADR-058). 비밀번호는 포함하지 않는다.
-- 사용: @APP_USER@, @APP_HOST@, @DB@ 를 실제 값으로 바꿔 migration 이후 DBA가 실행한다. 계정 생성(IDENTIFIED BY)은 별도.
-- DELETE·DDL·SUPER 권한 없음. 원장·감사·불변 이력은 SELECT/INSERT만. 통합 테스트(ProductionGrantsIT)가 이 파일을 그대로 적용해 검증한다.
GRANT SELECT, INSERT, UPDATE ON @DB@.admin_user TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.admin_bootstrap TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.audit_event TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.channel TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.channel_config TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, UPDATE ON @DB@.budget_control TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.exchange_rate TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.budget_policy TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.budget_policy_revision TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.budget_bucket TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.budget_reservation TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.budget_reservation_bucket TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.cost_log TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT ON @DB@.flyway_schema_history TO '@APP_USER@'@'@APP_HOST@';
-- T03-B(V6) 콘텐츠 도메인. topic·content_revision·queue_command_log는 불변·추가 전용(트리거와 이중 방어).
-- content·content_run은 상태 행. channel_config UNIQUE 추가는 migration 계정 소관(앱 권한 변경 없음).
GRANT SELECT, INSERT ON @DB@.topic TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.content TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.content_revision TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.content_run TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT ON @DB@.queue_command_log TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.production_request TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.production_step TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.provider_operation TO '@APP_USER@'@'@APP_HOST@';
GRANT SELECT, INSERT, UPDATE ON @DB@.production_discovery TO '@APP_USER@'@'@APP_HOST@';

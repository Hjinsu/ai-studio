-- T01 초기 기준선 (ADR-009). 도메인 테이블은 만들지 않는다. 도메인 테이블은 T02부터 V2 이후 신규 migration으로 추가한다.
-- 적용된 migration은 수정하지 않는다.
-- AI Studio 전용 DB의 기본 문자셋을 utf8mb4로 고정한다 (docs/03_DATABASE.md). collation은 서버 기본값을 따른다.
ALTER DATABASE CHARACTER SET = utf8mb4;
